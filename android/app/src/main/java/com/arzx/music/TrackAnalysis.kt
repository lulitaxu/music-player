package com.arzx.music

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device BPM + musical key analysis for Mix (0.6.19).
 *
 * HONESTY RULES (the feature's whole point): values are computed
 * from the track's real audio or not at all. Audio is available
 * only when the bytes are on the device — a completed download
 * ([Downloads.localFile]) or a local file the user added. The
 * app has no stream cache, so a streamed track is NEVER analyzed
 * from a stream and NEVER assigned a value from metadata, a
 * title guess, or a catalog lookup: it shows "--" in Mix mode
 * until it is downloaded. Results are cached per track key, so
 * analysis runs once per track.
 *
 * METHOD (standard, dependency-free DSP):
 *  - Decode to mono PCM with MediaExtractor + MediaCodec
 *    (platform codecs only), resampled to 22,050 Hz by linear
 *    interpolation, capped at the first 240 seconds.
 *  - BPM: spectral-flux onset envelope (2,048-point FFT, hop
 *    512), autocorrelation over the lag range for ~35–240 BPM,
 *    with a mild Gaussian tempo prior centred on 120 BPM to
 *    resolve the usual half/double-time ambiguity.
 *  - Key: energy-weighted chromagram over the same frames,
 *    correlated (Pearson) against the Krumhansl–Schmuckler
 *    major/minor profiles over all 24 rotations; the winner's
 *    root + mode map to a Camelot code (see [camelotCode]).
 * Both are estimates — good enough to order a playlist by,
 * which is all Mix claims.
 *
 * PERFORMANCE (Helio G95 bar): everything runs on ONE
 * background thread, one track at a time, streaming — the PCM
 * is never held whole (the onset envelope for 240s is ~10k
 * floats). No UI-thread work, no playback-thread work, and a
 * job can be cancelled between decode chunks.
 */
data class TrackAnalysis(
    val bpm: Double,
    /** Pitch class of the tonic, 0 = C … 11 = B. */
    val root: Int,
    val minor: Boolean,
    val camelot: String
) {
    fun keyName(): String = KEY_NAMES[((root % 12) + 12) % 12] + if (minor) " minor" else " major"
    fun summary(): String = "${bpm.toInt()} BPM · $camelot"
}

object AnalysisStore {
    private const val PREFS = "arzxsh_analysis"
    private const val KEY = "items"

    @Volatile
    private var cache: MutableMap<String, TrackAnalysis>? = null

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): Map<String, TrackAnalysis> {
        cache?.let { return it }
        val map = HashMap<String, TrackAnalysis>()
        val raw = prefs(ctx).getString(KEY, null)
        if (raw != null) {
            try {
                val o = JSONObject(raw)
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    try {
                        val v = o.getJSONObject(k)
                        val root = v.getInt("root").coerceIn(0, 11)
                        val minor = v.getBoolean("minor")
                        map[k] = TrackAnalysis(
                            v.getDouble("bpm"), root, minor,
                            camelotCode(root, minor)
                        )
                    } catch (_: Exception) {
                        // Skip the bad row; the rest stand.
                    }
                }
            } catch (_: Exception) {
                // Wholly unparseable: start empty, keep nothing.
            }
        }
        cache = map
        return map
    }

    fun get(ctx: Context, track: Track): TrackAnalysis? = all(ctx)[trackKey(track)]

    @Synchronized
    fun put(ctx: Context, track: Track, a: TrackAnalysis) {
        val map = HashMap(all(ctx))
        map[trackKey(track)] = a
        cache = map
        val o = JSONObject()
        for ((k, v) in map) {
            o.put(k, JSONObject().apply {
                put("bpm", v.bpm)
                put("root", v.root)
                put("minor", v.minor)
            })
        }
        prefs(ctx).edit().putString(KEY, o.toString()).apply()
    }
}

// ------------------------------------------------------------ audio availability

/** Where a track's audio bytes genuinely live on this device. */
sealed interface AnalysisSource {
    class FromFile(val file: File) : AnalysisSource
    class FromUri(val uri: Uri) : AnalysisSource
}

/** The audio for analysis, or null when the bytes are not on
 *  the device (a stream we have never downloaded). */
fun analysisSourceFor(ctx: Context, track: Track): AnalysisSource? = when {
    track.source == SOURCE_LOCAL ->
        try {
            AnalysisSource.FromUri(Uri.parse(track.id))
        } catch (_: Exception) {
            null
        }
    else -> Downloads.localFile(ctx, track)?.let { AnalysisSource.FromFile(it) }
}

// ------------------------------------------------------------ analyzer

object TrackAnalyzer {
    const val TARGET_RATE = 22050
    /** Analysis window cap: the first four minutes carry the
     *  tempo and the key of anything Mix will ever reorder. */
    const val MAX_SECONDS = 240

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Keys queued or running right now (main-thread confined:
     *  enqueue is called from the UI, the job's completion is
     *  posted back to the main thread). */
    private val inFlight = HashSet<String>()

    /** Cancel flag for the currently running job, if any. */
    @Volatile
    private var cancelCurrent = false

    /** Completion callback (track key), always on the main
     *  thread. Success or failure both arrive here; the store
     *  says which. Identity-kept by the activity like its other
     *  listeners, so a stale instance never unregisters a newer
     *  instance's callback. */
    @Volatile
    var listener: ((String) -> Unit)? = null

    fun isAnalyzing(track: Track): Boolean = trackKey(track) in inFlight

    /** Queues analysis for [track]. False when there is nothing
     *  to do: already analyzed, already queued, or no audio on
     *  the device. */
    fun enqueue(ctx: Context, track: Track): Boolean {
        val key = trackKey(track)
        if (key in inFlight) return false
        if (AnalysisStore.get(ctx, track) != null) return false
        val src = analysisSourceFor(ctx, track) ?: return false
        inFlight += key
        val appCtx = ctx.applicationContext
        executor.execute {
            cancelCurrent = false
            var result: TrackAnalysis? = null
            try {
                result = AudioDsp.analyze(appCtx, src) { cancelCurrent }
            } catch (_: Throwable) {
                result = null
            }
            if (result != null) {
                AnalysisStore.put(appCtx, track, result)
            }
            mainHandler.post {
                inFlight -= key
                listener?.invoke(key)
            }
        }
        return true
    }

    /** Cancels the running job (queued jobs still run; each is
     *  seconds-to-a-minute and self-terminating). Used when the
     *  user leaves Mix mode: the decode stops at the next chunk
     *  instead of burning CPU on a screen nobody is looking at. */
    fun cancelRunning() {
        cancelCurrent = true
    }
}

// ------------------------------------------------------------ DSP

private object AudioDsp {

    private const val TARGET_RATE = TrackAnalyzer.TARGET_RATE
    private const val FFT_SIZE = 2048
    private const val HOP = 512
    private const val FPS = 22050.0 / HOP // onset frames per second ≈ 43.07

    fun analyze(ctx: Context, src: AnalysisSource, cancelled: () -> Boolean): TrackAnalysis? {
        val acc = Accumulator()
        decodeInto(ctx, src, acc, cancelled)
        if (cancelled()) return null
        val bpm = estimateBpm(acc.onsetEnvelope()) ?: return null
        val key = estimateKey(acc.chroma) ?: return null
        return TrackAnalysis(bpm, key.first, key.second, camelotCode(key.first, key.second))
    }

    // ------------------------------------------------ decode

    private fun decodeInto(
        ctx: Context,
        src: AnalysisSource,
        acc: Accumulator,
        cancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            when (src) {
                is AnalysisSource.FromFile -> extractor.setDataSource(src.file.absolutePath)
                is AnalysisSource.FromUri -> extractor.setDataSource(ctx, src.uri, null)
            }
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i
                    format = f
                    break
                }
            }
            val fmt: MediaFormat = format ?: return
            if (trackIdx < 0) return
            extractor.selectTrack(trackIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1
            var resampler = StreamResampler(sampleRate, TARGET_RATE)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputSamples = 0L
            val maxSamples = TARGET_RATE.toLong() * TrackAnalyzer.MAX_SECONDS
            val chunk = ArrayList<Float>(8192)
            // A wedged codec (no input accepted, no output
            // produced) must end the job, not spin it forever:
            // ~5s of consecutive no-progress dequeues bails out.
            var stall = 0

            while (!cancelled() && outputSamples < maxSamples && stall < 500) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx) ?: continue
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                // Only real output counts as progress: input
                // being accepted while nothing ever comes out is
                // exactly the wedge this guard exists for.
                if (outIdx >= 0) stall = 0 else stall++
                when {
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            chunk.clear()
                            // PCM16 is what platform decoders emit
                            // for the formats this app downloads;
                            // anything else ends analysis honestly.
                            val bytes = ByteArray(info.size)
                            buf.get(bytes)
                            val frames = info.size / (2 * channels.coerceAtLeast(1))
                            var o = 0
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (ch in 0 until channels.coerceAtLeast(1)) {
                                    val lo = bytes[o].toInt() and 0xFF
                                    val hi = bytes[o + 1].toInt()
                                    sum += ((hi shl 8) or lo).toFloat() / 32768f
                                    o += 2
                                }
                                chunk.add(sum / channels.coerceAtLeast(1))
                            }
                            val arr = FloatArray(chunk.size) { chunk[it] }
                            resampler.process(arr) { s ->
                                acc.addSample(s)
                                outputSamples++
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        val newRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newCh = if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else channels
                        if (newRate != sampleRate) {
                            sampleRate = newRate
                            resampler = StreamResampler(sampleRate, TARGET_RATE)
                        }
                        channels = newCh
                    }
                }
            }
        } catch (_: Throwable) {
            // Undecodable file (corrupt, exotic codec): analysis
            // simply has no result; the caller records nothing.
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------ onset + chroma accumulation

    private class Accumulator {
        private val window = FloatArray(FFT_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
        }
        private val frame = FloatArray(FFT_SIZE)
        private var fill = 0
        private var prevMag: FloatArray? = null
        private val onset = ArrayList<Float>(11000)
        val chroma = DoubleArray(12)
        private var frames = 0

        fun addSample(s: Float) {
            frame[fill++] = s
            if (fill == FFT_SIZE) {
                processFrame()
                // Slide by one hop: keep the overlap.
                System.arraycopy(frame, HOP, frame, 0, FFT_SIZE - HOP)
                fill = FFT_SIZE - HOP
            }
        }

        private fun processFrame() {
            val re = FloatArray(FFT_SIZE)
            val im = FloatArray(FFT_SIZE)
            for (i in 0 until FFT_SIZE) re[i] = frame[i] * window[i]
            fft(re, im)
            val mag = FloatArray(FFT_SIZE / 2)
            for (k in 0 until FFT_SIZE / 2) {
                mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            }
            // Spectral flux (positive change only), voice band.
            var flux = 0.0
            val prev = prevMag
            for (k in 2 until FFT_SIZE / 2) {
                val d = mag[k] - (prev?.get(k) ?: 0f)
                if (d > 0) flux += d
            }
            onset.add(flux.toFloat())
            prevMag = mag
            // Chroma: bins 55 Hz..5 kHz into pitch classes.
            for (k in 2 until FFT_SIZE / 2) {
                val freq = k * TARGET_RATE.toDouble() / FFT_SIZE
                if (freq < 55.0 || freq > 5000.0) continue
                val pc = ((Math.round(12.0 * log2(freq / 440.0)) + 9) % 12).toInt()
                chroma[((pc + 12) % 12)] += mag[k].toDouble()
            }
            frames++
        }

        fun onsetEnvelope(): FloatArray = FloatArray(onset.size) { onset[it] }

        companion object {
            private const val TARGET_RATE = 22050
        }
    }

    // ------------------------------------------------ tempo

    private fun estimateBpm(env: FloatArray): Double? {
        val n = env.size
        if (n < FPS.toInt() * 20) return null // under ~20s of audio: no honest tempo
        var mean = 0.0
        for (v in env) mean += v
        mean /= n
        var energy = 0.0
        val e = FloatArray(n) { i -> (env[i] - mean).toFloat().also { energy += it * it } }
        if (energy < 1e-9) return null // silence / flat: no tempo to find
        // Lag range: 240 BPM (10.8 frames) down to 35 BPM (73.9).
        val minLag = (FPS * 60.0 / 240.0).toInt().coerceAtLeast(4)
        val maxLag = (FPS * 60.0 / 35.0).toInt().coerceAtMost(n / 2)
        if (maxLag <= minLag) return null
        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in 0 until n - lag) s += e[i] * e[i + lag]
            val bpm = 60.0 * FPS / lag
            // Gaussian prior in octaves around 120 BPM, sigma
            // 0.55: prefers the musically likely octave without
            // forbidding genuinely slow or fast tracks.
            val oct = log2(bpm / 120.0)
            val prior = exp(-(oct * oct) / (2 * 0.55 * 0.55))
            val score = s / (n - lag) * (0.35 + 0.65 * prior)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return null
        // Parabolic interpolation for a sub-frame peak.
        fun ac(lag: Int): Double {
            var s = 0.0
            for (i in 0 until n - lag) s += e[i] * e[i + lag]
            return s / (n - lag)
        }
        var refined = bestLag.toDouble()
        if (bestLag in (minLag + 1) until maxLag) {
            val a = ac(bestLag - 1)
            val b = ac(bestLag)
            val c = ac(bestLag + 1)
            val denom = a - 2 * b + c
            if (denom != 0.0) refined = bestLag + 0.5 * (a - c) / denom
        }
        val bpm = 60.0 * FPS / refined
        return if (bpm in 30.0..250.0) Math.round(bpm * 2) / 2.0 else null
    }

    // ------------------------------------------------ key

    private val KS_MAJOR = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    )
    private val KS_MINOR = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    )

    /** Returns (root pitch class, minor) or null for silence. */
    private fun estimateKey(chroma: DoubleArray): Pair<Int, Boolean>? {
        var total = 0.0
        for (v in chroma) total += v
        if (total < 1e-9) return null
        val c = DoubleArray(12) { chroma[it] / total }
        var bestCorr = Double.NEGATIVE_INFINITY
        var best: Pair<Int, Boolean>? = null
        for (minor in listOf(false, true)) {
            val profile = if (minor) KS_MINOR else KS_MAJOR
            for (root in 0 until 12) {
                // Profile rotated so its tonic sits on `root`.
                val rotated = DoubleArray(12) { i -> profile[(i - root + 12) % 12] }
                val corr = pearson(c, rotated)
                if (corr > bestCorr) {
                    bestCorr = corr
                    best = root to minor
                }
            }
        }
        return best
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) {
            ma += a[i]
            mb += b[i]
        }
        ma /= n
        mb /= n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val den = sqrt(da * db)
        return if (den == 0.0) 0.0 else num / den
    }

    // ------------------------------------------------ FFT + resampler

    /** Iterative radix-2 FFT, in place. Size must be a power of 2. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cwr = 1f
                var cwi = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Streaming linear-interpolation resampler across chunks. */
    private class StreamResampler(srcRate: Int, dstRate: Int) {
        private val step = srcRate.toDouble() / dstRate
        private var buf = FloatArray(0)
        private var readPos = 0.0

        fun process(input: FloatArray, emit: (Float) -> Unit) {
            if (input.isEmpty()) return
            val merged = FloatArray(buf.size + input.size)
            System.arraycopy(buf, 0, merged, 0, buf.size)
            System.arraycopy(input, 0, merged, buf.size, input.size)
            buf = merged
            while (true) {
                val i = readPos.toInt()
                if (i + 1 >= buf.size) break
                val frac = (readPos - i).toFloat()
                emit(buf[i] * (1f - frac) + buf[i + 1] * frac)
                readPos += step
            }
            val consumed = readPos.toInt()
            if (consumed > 0) {
                buf = buf.copyOfRange(consumed, buf.size)
                readPos -= consumed
            }
        }
    }
}
