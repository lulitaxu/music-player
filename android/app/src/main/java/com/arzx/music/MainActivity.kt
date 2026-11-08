package com.arzx.music

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.AudioEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.animation.ValueAnimator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors

const val SOURCE_AUDIUS = "audius"
const val SOURCE_YOUTUBE = "youtube"
const val SOURCE_LOCAL = "local"
/** Sleep-timer fade ramp (0.6.8): the final 30 seconds of a
 *  timed sleep timer ramp the volume to zero — no hard cut. */
const val SLEEP_FADE_MS = 30_000L

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Int,
    val artworkSmall: String? = null,
    val artworkLarge: String? = null,
    /** Catalog this track came from: [SOURCE_AUDIUS] or [SOURCE_YOUTUBE]. */
    val source: String = SOURCE_AUDIUS,
    /** YouTube Music channel id of the track's artist, when known. */
    val artistId: String? = null
)

/**
 * Artwork loader for the UI layer: downloads artwork on a background
 * pool, caches bitmaps, and guards against view recycling by tagging
 * each ImageView with the URL it is waiting for.
 *
 * v5.1 jank pass: bitmaps are decoded AT the size they will be shown
 * (two-pass sampled decode into size buckets), never at the source's
 * full resolution — YouTube's large thumbnails are 2268px squares,
 * and decoding those for a 52dp row was the artwork half of the
 * scroll/touch jank on the Helio G95. Cache keys carry the bucket,
 * so the same URL can live at row size and Now Playing size side by
 * side.
 *
 * 0.6.3 (audit S3): the bitmap cache is BYTE-sized now (it was a
 * 128-ENTRY count cache of ~1.1MB row bitmaps ≈ up to ~140MB of
 * heap — a prime suspect for whole-app GC heaviness). A URL-keyed
 * byte cache sits under every decode, so one network fetch feeds
 * ALL size buckets of an image (a track's art used to be downloaded
 * up to 5 times: row, mini, hero, blur, ambient — plus a 6th by
 * the playback service, which now reads through this loader too).
 * Hero/backdrop/ambient work runs on its own single thread so a
 * screenful of row decodes can never queue ahead of the player art.
 * Visible outside this file since 0.6.3 for [PlaybackService].
 */
object ArtworkLoader {
    /** ~28MB of decoded bitmaps, LRU by byte count. */
    private val cache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** ~8MB of source bytes, one entry per URL, shared by every
     *  decode bucket of that URL. */
    private val bytesCache = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** Row/list decodes. */
    private val pool = Executors.newFixedThreadPool(3)

    /** Hero art, blurred backdrops and ambient sampling: the
     *  player's surfaces never wait behind list rows. */
    private val heroPool = Executors.newSingleThreadExecutor()

    /** Bytes currently held by the in-memory artwork cache. */
    fun cacheBytes(): Long = cache.snapshot().values.sumOf { it.byteCount.toLong() }

    /** Drops every cached bitmap (Settings → Clear cache). */
    fun clearCache() {
        cache.evictAll()
        bytesCache.evictAll()
    }

    /** Decode bucket (long-edge px) for a requested display size. */
    private fun bucketFor(targetPx: Int): Int = when {
        targetPx <= 128 -> 128
        targetPx <= 256 -> 256
        targetPx <= 512 -> 512
        targetPx <= 1024 -> 1024
        else -> 2048
    }

    fun load(url: String?, into: ImageView) = load(url, into, null)

    /**
     * Loads [url]; on failure tries [fallbackUrl] before giving up.
     * (v4 loaded exactly one URL: when the small YouTube thumbnail
     * failed, the mini player rendered an empty box even though the
     * large artwork was right there.)
     */
    fun load(url: String?, into: ImageView, fallbackUrl: String?) {
        val lp = into.layoutParams
        val target = if (lp != null && lp.width > 0) lp.width else 512
        load(url, into, fallbackUrl, target)
    }

    /** [targetPx] is the display size the caller knows (row height,
     *  mini player size, Now Playing width); decode size follows it. */
    fun load(url: String?, into: ImageView, fallbackUrl: String?, targetPx: Int) =
        load(url, into, fallbackUrl, targetPx, null)

    /** [onReady] fires on the UI thread only when this load's bitmap
     *  was actually applied to the view (never for a stale load a
     *  newer one superseded) — Now Playing uses it to fade the hero
     *  art in on track changes. */
    fun load(
        url: String?,
        into: ImageView,
        fallbackUrl: String?,
        targetPx: Int,
        onReady: (() -> Unit)?
    ) {
        if (url.isNullOrEmpty()) {
            if (!fallbackUrl.isNullOrEmpty() && fallbackUrl != url) {
                load(fallbackUrl, into, null, targetPx, onReady)
            } else {
                into.tag = null
                into.setImageDrawable(null)
            }
            return
        }
        val bucket = bucketFor(targetPx)
        val key = "$url@$bucket"
        into.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            into.setImageBitmap(hit)
            onReady?.invoke()
            return
        }
        into.setImageDrawable(null)
        // Hero-size work jumps the row queue (audit S3).
        val exec = if (bucket >= 1024) heroPool else pool
        exec.execute {
            var bmp = decode(url, bucket)
            if (bmp != null) {
                cache.put(key, bmp)
            } else if (!fallbackUrl.isNullOrEmpty() && fallbackUrl != url) {
                bmp = decode(fallbackUrl, bucket)
                if (bmp != null) cache.put(key, bmp)
            }
            val result = bmp
            into.post {
                if (result != null && into.tag == key) {
                    into.setImageBitmap(result)
                    onReady?.invoke()
                }
            }
        }
    }

    /** Bitmap hand-off for non-view consumers (0.6.3): the
     *  playback service's notification art reads through the SAME
     *  caches instead of running its own private network fetch of
     *  an image the UI already downloaded (audit S3's 6th fetch).
     *  The callback fires on the main thread. */
    fun bitmapFor(url: String, maxPx: Int, onReady: (Bitmap?) -> Unit) {
        val bucket = bucketFor(maxPx)
        val key = "$url@$bucket"
        cache.get(key)?.let { bmp ->
            Handler(Looper.getMainLooper()).post { onReady(bmp) }
            return
        }
        heroPool.execute {
            val bmp = decode(url, bucket)
            if (bmp != null) cache.put(key, bmp)
            Handler(Looper.getMainLooper()).post { onReady(bmp) }
        }
    }

    /** Loads a heavily blurred, tiny version of the artwork for backdrops. */
    fun loadBlurred(url: String?, into: ImageView) {
        if (url.isNullOrEmpty()) {
            into.tag = null
            into.setImageDrawable(null)
            return
        }
        val key = "$url#blur"
        into.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            into.setImageBitmap(hit)
            return
        }
        heroPool.execute {
            // The blur output is 28px wide, but it is generated by
            // DOWNSCALING the caller's (high-resolution) URL — v5.1
            // passes the w1080 hero URL — never by upscaling a
            // thumbnail; a 256px decode keeps the gradients smooth.
            val small = decode(url, 256)
            val blurred = small?.let { blur(it) }
            if (blurred != null) cache.put(key, blurred)
            into.post {
                if (blurred != null && into.tag == key) into.setImageBitmap(blurred)
            }
        }
    }

    /** Dominant vibrant color of [url]'s artwork for the ambient
     *  player glow, sampled from a 256px decode on the pool thread.
     *  The callback fires on the main thread; null means "no usable
     *  color" and the caller keeps the neutral background. One-shot
     *  per artwork — no per-frame work. */
    fun loadAmbientColor(url: String?, onColor: (Int?) -> Unit) {
        if (url.isNullOrEmpty()) {
            onColor(null)
            return
        }
        heroPool.execute {
            val bmp = decode(url, 256)
            val color = bmp?.let { dominantVibrant(it) }
            Handler(Looper.getMainLooper()).post { onColor(color) }
        }
    }

    /** Saturation/value-weighted hue histogram over the bitmap:
     *  near-gray and near-black/white pixels are ignored, the
     *  winning hue bucket is re-emitted at a fixed deep, saturated
     *  value so the glow reads as "the artwork's color, darkened"
     *  (the inspo's indigo wash) under white text. */
    private fun dominantVibrant(bmp: Bitmap): Int? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val buckets = FloatArray(36)
        val hsv = FloatArray(3)
        var x = 0
        while (x < w) {
            var y = 0
            while (y < h) {
                Color.colorToHSV(bmp.getPixel(x, y), hsv)
                val s = hsv[1]
                val v = hsv[2]
                if (s >= 0.25f && v >= 0.12f && v <= 0.95f) {
                    val b = (hsv[0] / 10f).toInt().coerceIn(0, 35)
                    buckets[b] += s * v
                }
                y += 4
            }
            x += 4
        }
        var best = -1
        var bestW = 0f
        for (i in buckets.indices) {
            if (buckets[i] > bestW) {
                bestW = buckets[i]
                best = i
            }
        }
        if (best < 0) return null
        return Color.HSVToColor(floatArrayOf(best * 10f + 5f, 0.62f, 0.42f))
    }

    /** Fetch source bytes, serving repeat buckets of the same URL
     *  from the byte cache (0.6.3): one network fetch now feeds the
     *  row decode, the hero decode, the blur and the ambient sample
     *  of the same image. */
    private fun downloadBytes(url: String): ByteArray? {
        bytesCache.get(url)?.let { return it }
        val bytes = try {
            if (url.startsWith("file:")) {
                // Local artwork extracted at import time lives on disk.
                java.io.FileInputStream(java.io.File(java.net.URI(url))).use { it.readBytes() }
            } else {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    // Some artwork CDNs refuse the default Java user agent;
                    // identify the app like every other request it makes.
                    setRequestProperty("User-Agent", "arzxsh (Android)")
                }
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.use { it.readBytes() }
            }
        } catch (_: Exception) {
            null
        }
        if (bytes != null) bytesCache.put(url, bytes)
        return bytes
    }

    /** Sampled decode: the result's dimensions land within
     *  [maxPx, 2×maxPx) on both axes instead of the source size. */
    private fun decode(url: String, maxPx: Int): Bitmap? {
        val bytes = downloadBytes(url) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxPx &&
                    bounds.outHeight / (sample * 2) >= maxPx
                ) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun blur(src: Bitmap): Bitmap {
        val w = 28
        val h = (src.height * (w.toFloat() / src.width)).toInt().coerceAtLeast(1)
        var bmp = Bitmap.createScaledBitmap(src, w, h, true)
        repeat(3) { bmp = boxBlur(bmp, 5) }
        // 0.6.3 (audit S9): the backdrop darkening is BAKED IN —
        // a per-row multiply from 0.72 (top) to 0.42 (bottom),
        // reproducing what Now Playing's color filter (110/255
        // flat) plus its separate full-screen shade gradient
        // used to composite at draw time on every frame. The
        // cached blur now IS the finished backdrop: the filter
        // and the shade layer are deleted, so the NP backdrop
        // stack renders one layer thinner. 28px wide; the cost
        // is noise, paid once per track.
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val f = if (h <= 1) 0.55f else 0.72f - 0.30f * (y.toFloat() / (h - 1))
            for (x in 0 until w) {
                val c = px[y * w + x]
                px[y * w + x] = Color.rgb(
                    (Color.red(c) * f).toInt(),
                    (Color.green(c) * f).toInt(),
                    (Color.blue(c) * f).toInt()
                )
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (dx in -radius..radius) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val c = px[y * w + xx]
                    r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                }
                out[y * w + x] = Color.rgb(r / n, g / n, b / n)
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }
}

/**
 * One playback engine instance: a Media3 ExoPlayer plus the state
 * the activity's engine semantics need around it.
 *
 * Why ExoPlayer (v5.1): googlevideo stream URLs are bound to the
 * client context that requested them. ExoPlayer's HTTP data source
 * sends the SAME User-Agent as that context, per player instance;
 * MediaPlayer could not set one at all, and Stagefright's default
 * UA got resolved URLs rejected on device (what=-38). [retired]
 * replaces MediaPlayer's listener-detach trick: when a new track
 * starts, the outgoing instance is retired and every callback checks
 * it, so a stale completion or error can never fire into the new
 * track's session. All calls happen on the main thread (ExoPlayer's
 * contract) and are exception-shielded like the MediaPlayer code was.
 */
@UnstableApi
private class EnginePlayer(val exo: ExoPlayer) {
    @Volatile
    var retired = false

    @Volatile
    var readyFired = false

    var listener: Player.Listener? = null

    val isPlaying: Boolean
        get() = try { exo.isPlaying } catch (_: Exception) { false }

    val isEnded: Boolean
        get() = try { exo.playbackState == Player.STATE_ENDED } catch (_: Exception) { false }

    /** Duration in ms; negative (C.TIME_UNSET) until the player knows it. */
    val durationMs: Long
        get() = try { exo.duration } catch (_: Exception) { 0L }

    val positionMs: Long
        get() = try { exo.currentPosition } catch (_: Exception) { 0L }

    val audioSessionId: Int
        get() = try { exo.audioSessionId } catch (_: Exception) { 0 }

    fun setVolume(v: Float) {
        try { exo.volume = v.coerceIn(0f, 1f) } catch (_: Exception) {}
    }

    fun play() {
        try { exo.play() } catch (_: Exception) {}
    }

    fun pause() {
        try { exo.pause() } catch (_: Exception) {}
    }

    fun seekTo(ms: Long) {
        try { exo.seekTo(ms.coerceAtLeast(0L)) } catch (_: Exception) {}
    }

    fun release() {
        retired = true
        try { listener?.let { exo.removeListener(it) } } catch (_: Exception) {}
        try { exo.release() } catch (_: Exception) {}
    }
}

/**
 * Now Playing's ScrollView with drag-to-dismiss (0.6.1). A downward
 * drag that starts in the header/artwork zone WHILE the scroll is
 * at its very top is intercepted and handed to the activity, which
 * translates the whole Now Playing panel with the finger; every
 * other gesture scrolls exactly as a stock ScrollView. Taps are
 * never stolen: interception only happens on a MOVE that has
 * already passed the touch slop downward, so header buttons keep
 * their clicks (a stolen gesture just sends them CANCEL, like any
 * scroll). Velocity is tracked for the fling decision.
 */
private class NpScrollView(context: android.content.Context) : ScrollView(context) {
    /** Zone test, in this view's viewport coordinates: is [y] in
     *  the grabbable header/artwork band? Supplied by the activity
     *  from the real laid-out views. */
    var zoneTest: ((Float) -> Boolean)? = null
    /** Total downward displacement (px) while dragging. */
    var onDrag: ((Float) -> Unit)? = null
    /** Drag released: total displacement + Y velocity (px/s). */
    var onDragEnd: ((Float, Float) -> Unit)? = null

    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (!dragging && scrollY == 0 && e.y - downY > slop &&
                    zoneTest?.invoke(downY) == true
                ) {
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle()
                tracker = null
            }
        }
        return if (dragging) true else super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> onDrag?.invoke((e.y - downY).coerceAtLeast(0f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dy = (e.y - downY).coerceAtLeast(0f)
                tracker?.computeCurrentVelocity(1000)
                val v = tracker?.yVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                dragging = false
                onDragEnd?.invoke(dy, v)
            }
        }
        return true
    }
}

@UnstableApi
class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    /** Playback-only executor. v5 ran stream resolution on the shared
     *  [executor], where a single in-flight DOWNLOAD (minutes long)
     *  blocked every YouTube resolution queued behind it — a real
     *  chunk of the "playback starts slowly" complaint. Resolution,
     *  the retry ladder, radio and the Audius fallback live here;
     *  catalog/search/downloads stay on [executor]. */
    private val playerExecutor = Executors.newSingleThreadExecutor()

    /** Lyrics fetches run on their OWN executor: on the shared one
     *  a long download (minutes) starved the fetch and the overlay
     *  sat at "unavailable"; on the player executor a multi-request
     *  fetch could stall the playback ladder. Isolation both ways. */
    private val lyricsExecutor = Executors.newSingleThreadExecutor()

    /** Stats persistence (0.6.3, audit S10): recents/history JSON
     *  writes land here — single-threaded so write order is call
     *  order — instead of taxing play()'s main-thread prelude. */
    private val statsExecutor = Executors.newSingleThreadExecutor()

    /** Gapless warm/arm work (0.6.3, audit S5): pre-resolution
     *  walks live here, NOT on [playerExecutor], so a background
     *  warm-up can never sit FIFO-ahead of a user's tap-to-play on
     *  the foreground resolution ladder. */
    private val gaplessExecutor = Executors.newSingleThreadExecutor()

    /** Parallel fetch pool (0.6.3, audit S5): Search's five
     *  independent source calls run concurrently here instead of
     *  summing their round trips on the single-thread [executor]. */
    private val fetchPool = Executors.newFixedThreadPool(4)
    private val tracks = mutableListOf<Track>()
    private var player: EnginePlayer? = null
    private var current: Track? = null
    private var queue: List<Track> = emptyList()
    private var queueIndex: Int = -1
    private val recentTracks = mutableListOf<Track>()

    private lateinit var content: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var mini: LinearLayout
    private lateinit var visualizerView: LinearLayout
    private lateinit var miniArtwork: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlayButton: ImageView
    private lateinit var miniNextButton: ImageView

    private lateinit var nowPlaying: FrameLayout
    private lateinit var npBackdrop: ImageView
    private lateinit var npArtwork: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npHeart: ImageView
    private lateinit var npSeek: SeekBar
    private lateinit var npElapsed: TextView
    private lateinit var npRemaining: TextView
    private lateinit var npPlayButton: ImageView

    // 0.6.9 morph state: ONE progress value (0 = collapsed at the
    // mini player, 1 = full Now Playing) drives the panel position,
    // the mini fade and the Now Playing content fade (applyNpMorph).
    private val npMorph = Springs.Value(0f)
    private var npMorphSpring: SpringAnimation? = null
    private var npMorphTarget = 0f

    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false
    private var generation = 0
    private var playToken = 0

    /** Process-ownership id (0.6.3, WS-D Bug A): claimed first
     *  thing in onCreate from [PlayerRegistry]. Every player this
     *  instance creates registers its release against it, and the
     *  async playback entry points refuse to run for a stale id —
     *  a dead instance can neither keep nor mint a player. */
    private var instanceId = 0

    /** This instance's bridge controls, identity-kept so a stale
     *  instance's onDestroy never unregisters a NEWER instance's
     *  controls from under it. */
    private var myControls: PlaybackBridge.Controls? = null

    /** A stop/pause intent that arrived while a tap-to-play was
     *  still resolving (0.6.3, WS-C): the pending start consumes
     *  it and lands prepared-and-paused instead of autoplaying
     *  against the user's explicit stop. Cleared by the next
     *  explicit play() and consumed by startPlaybackInternal. */
    @Volatile
    private var suppressAutoPlay = false

    /** Set by stopPlayback (0.6.3): while stopped, the bridge
     *  stays silent — refreshPlaybackUi must not re-push a paused
     *  snapshot and resurrect the notification the stop just
     *  ended. Cleared by the next real start. */
    @Volatile
    private var bridgeMuted = false
    private var activeTab = "Home"
    private var shuffleOn = false
    private var repeatMode = 0 // 0 = off, 1 = all, 2 = one
    private var playOrder: List<Int> = emptyList()
    /** Built-in EQ (0.6.8): owns the audiofx effects riding the
     *  current player's audio session. Created in onCreate,
     *  synced from refreshPlaybackUi, released in onDestroy. */
    private lateinit var eqController: EqController
    private var sleepDeadline = 0L
    /** 0.6.8 sleep fade: true while the timed sleep timer is
     *  inside its final volume ramp (see beginSleepFade).
     *  Transition code (crossfade, gapless) reads it and stays
     *  out of the way — sleep wins over every transition. */
    private var sleepFadeRunning = false
    /** Length of the current timer's fade ramp: the last 30s,
     *  or the whole timer when a custom duration is shorter. */
    private var sleepFadeWindowMs = 0L

    private lateinit var npShuffleBtn: ImageView
    private lateinit var npRepeatBtn: ImageView
    private lateinit var npShuffleDot: View
    private lateinit var npRepeatDot: View
    private lateinit var npDownloadBtn: ImageView
    private lateinit var npDownloadProgress: ProgressBar
    private lateinit var npSleepLabel: TextView
    /** Sleep glyph in the v6 controls row (tints accent when armed). */
    private lateinit var npSleepBtn: ImageView
    private lateinit var npError: TextView
    /** One-shot Now Playing notice (e.g. the Audius fallback line),
     *  shown when the next stream successfully prepares. */
    private var pendingNpNotice: String? = null
    private val manualIndices = mutableSetOf<Int>()
    private var sleepAfterTrack = false
    private var sleepAfterQueue = false
    private var artWasPlaying = false

    // ---- v5: crossfade (dual player) ----
    /** The outgoing player while a crossfade runs; null otherwise. */
    private var outPlayer: EnginePlayer? = null
    /** Track the CURRENT [player] instance is playing (for the
     *  repeat-one no-fade rule and crossfade bookkeeping). */
    private var activePlayerTrack: Track? = null
    private var fadeStep = 0
    private var fadeTotalSteps = 0
    private val crossfadeTick = object : Runnable {
        override fun run() {
            val out = outPlayer
            val cur = player
            if (out == null || cur == null) return
            fadeStep++
            try {
                val t = (fadeStep.toFloat() / fadeTotalSteps).coerceIn(0f, 1f)
                out.setVolume(1f - t)
                cur.setVolume(t)
                if (fadeStep < fadeTotalSteps) {
                    handler.postDelayed(this, 100)
                } else {
                    finishCrossfadeNow()
                }
            } catch (_: Exception) {
                finishCrossfadeNow()
            }
        }
    }

    // ---- v5.1: YouTube retry ladder + crossfade source ----
    /** The player that was AUDIBLE when the current track was
     *  requested. Held across resolution (and across ladder attempts)
     *  so a crossfade can still start from the track the user is
     *  actually hearing; consumed by the next successful start. */
    private var fadeSource: EnginePlayer? = null
    /** Track the active YouTube ladder belongs to. */
    private var ytTrack: Track? = null
    /** Stage labels still to try, in order (see StreamResolver). */
    private var ytStageLabels: List<String> = emptyList()
    private var ytStage = -1
    /** Known-good cached resolution prepended as stage "CACHE". */
    private var ytCached: StreamResolver.Resolved? = null
    /** One report line per failed stage, printed if the ladder dies. */
    private val ytReports = mutableListOf<String>()
    private var ytQuality = "normal"

    // ---- v5.1: ticker change-guards (jank pass) ----
    /** iOS-style easing (cubic-bezier .32,.72,0,1) for Now Playing
     *  motion: real animator interpolation, never ticker steps. */
    private val iosEase = PathInterpolator(0.32f, 0.72f, 0f, 1f)

    /** Artwork key of the last track drawn into Now Playing, so the
     *  hero fade-in runs on track changes only. */
    private var lastNpArtKey: String? = null

    /** Transient visualizer bar sets (list rows); the mini player's
     *  set is registered permanently. One shared ValueAnimator
     *  pulses them all while playing. */
    private val visualizerBarSets = ArrayList<Array<View>>()

    /** Last decorative-pulse write (0.6.3): the ~30fps throttle. */
    private var lastPulseWriteMs = 0L
    private var miniVisualizerBars: Array<View>? = null
    private var visualizerAnimator: ValueAnimator? = null

    private var lastNpElapsed = ""
    private var lastNpRemaining = ""
    private var lastSleepLabel = ""

    // ---- v5: downloads vault glue ----
    // (0.6.3: the in-flight set + stop modes moved INTO the
    // Downloads store, next to the process-lifetime executor that
    // runs the transfers — see Downloads.start. What stays here
    // is purely view state:)
    /** Progress views of the visible Downloads rows, by track key. */
    private val dlProgressViews = mutableMapOf<String, Pair<ProgressBar, TextView>>()
    /** This instance's downloads UI listener, identity-kept so a
     *  stale instance's onDestroy never unregisters a newer
     *  instance's listener (same rule as myControls). */
    private var myDlListener: ((String, String) -> Unit)? = null

    // ---- v5: listening stats accumulation ----
    private var sessionListenedMs = 0L
    private var lastTickMs = 0L

    // ---- v5: lyrics overlay ----
    private lateinit var lyricsOverlay: FrameLayout
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsList: LinearLayout
    private lateinit var lyricsTitle: TextView
    private lateinit var lyricsStatus: TextView
    private var lyricsSyncedLines: List<LyriTimber> = emptyList()
    private var lyricsLineViews: List<TextView> = emptyList()
    private var lyricsActiveLine = -2
    /** Downloads.key() of the track the overlay currently shows. */
    private var lyricsKey: String? = null
    /** True while a lyrics fetch is in flight (double-tap guard). */
    private var lyricsLoading = false
    /** Track the lyrics overlay is showing (offset controls act on it). */
    private var lyricsTrack: Track? = null
    /** The running lyrics auto-scroll animator (0.6.9): at most
     *  one exists; a user touch on the lyrics scroll cancels it
     *  so the highlight never fights the reader's finger. */
    private var lyricsScrollAnim: ValueAnimator? = null
    /** Spinner shown in the lyrics header while a fetch runs. */
    private lateinit var lyricsSpinner: ProgressBar
    /** Offset row value label in the lyrics overlay. */
    private lateinit var lyricsOffsetLabel: TextView

    // ---- v6: Now Playing lyrics (above-title line + preview card) ----
    private lateinit var npLyricLine: TextView
    private lateinit var npLyricsCard: LinearLayout
    private lateinit var npLyricsCardCur: TextView
    private lateinit var npLyricsCardNext: TextView
    private var npLyrics: LyricsData? = null
    /** Downloads.key() of the track [npLyrics] belongs to. */
    private var npLyricsKey: String? = null
    private var npLyricIdx = -2
    private var lastNpLyricText: String? = null

    // ---- v6: "Playing from {context}" for the Now Playing top bar ----
    private var npContextKind = ""
    private var npContextName = ""
    private lateinit var npContextSmall: TextView
    private lateinit var npContextBig: TextView

    /** Sets the Now Playing "Playing from" context (called by the
     *  screens that own a queue: playlist, album, artist, search…). */
    private fun setPlayContext(kind: String, name: String) {
        npContextKind = kind
        npContextName = name
    }

    // ---- v6: buffering state (mini player + Now Playing) ----
    /** True between a play() request and the player reaching READY
     *  (or BUFFERING in ExoPlayer terms). Drives the buffering
     *  indicators; cleared on ready/error/completion paths. */
    private var buffering = false
    private lateinit var miniProgress: ProgressBar
    private lateinit var npBuffering: ProgressBar
    private var lastMiniProgress = -1

    // ---- v6: skeleton loading placeholders ----
    /** Placeholder blocks currently on screen, pulsed by ONE shared
     *  animator (alpha only — GPU-cheap on the Helio G95). Cleared
     *  whenever a screen rebuilds its content. */
    private val skeletonViews = ArrayList<View>()
    private var skeletonAnimator: ValueAnimator? = null

    // ---- v6: library screen state ----
    /** Active Library filter chip: all | playlists | liked |
     *  downloaded | saved | local. */
    private var libraryFilter = "all"
    /** Library layout: true = 3-column grid (inspo), false = rows. */
    private var libraryGrid = true

    // ---- v6 refinement: ambient player color ----
    /** Dominant vibrant color extracted from the current track's
     *  hi-res artwork (darkened for background use); null until the
     *  first extraction lands or when the artwork has no color. */
    private var ambientColor: Int? = null
    /** Downloads.key() of the track [ambientColor] belongs to. */
    private var ambientKey: String? = null
    /** Radial-glow layer inside Now Playing, above the blurred
     *  backdrop and below the content. */
    private lateinit var npAmbient: View
    /** Small in-memory cache: artwork URL -> ambient color. */
    private val ambientCache = HashMap<String, Int>()

    private fun ambientOn(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("setting_Ambient color from artwork", true)

    /** Activity-result code for the local-file document picker. */
    private val reqImport = 4205
    private val reqProfilePhoto = 4206

    // Local profile (0.6.4, WS-2): every header avatar box ever
    // built by this instance (tab retention keeps more than one
    // alive), so a photo/name change refreshes all of them.
    private val avatarBoxes = ArrayList<FrameLayout>()
    private var profilePreviewBox: FrameLayout? = null
    private var profileDialog: android.app.AlertDialog? = null

    /** Which full-screen section is showing (for live row refresh). */
    private var visibleScreen = "home"

    private lateinit var menuOverlay: FrameLayout
    private lateinit var menuPanel: LinearLayout
    private lateinit var menuTitle: TextView
    private lateinit var menuList: LinearLayout

    // ---- v6.1: content back-stack ----
    /** One entry per content (sub-)screen: a stable [key] (so a
     *  re-render of the same screen replaces, never duplicates) and
     *  a [restore] that re-renders the screen from scratch — the
     *  same way tab switches already rebuild screens here. The
     *  bottom entry is always the owning tab's root screen. */
    private class NavEntry(val key: String, val restore: () -> Unit)
    private val navStack = ArrayList<NavEntry>()
    /** True while a pop is re-rendering its target, so the target's
     *  own push/root-reset calls stay out of the way. */
    private var navRestoring = false

    // ---- v6.1: Now Playing drag-to-dismiss + pinned chrome ----
    /** The Now Playing ScrollView (drag-aware subclass). */
    private lateinit var npScroll: NpScrollView
    /** Pinned chevron-down: lives OUTSIDE the scroll content, so it
     *  is reachable no matter how far the player is scrolled. */
    private lateinit var npCloseBtn: ImageView

    // ---- visualizer (0.6.7: playback-driven, no audio capture) ----
    // The audiofx Visualizer is GONE: it needed RECORD_AUDIO, and a
    // microphone permission in a music player is exactly what Play
    // Protect flags. The bars are now animated from playback state
    // and position only (see setVisualizerPulsing): they move while
    // a track plays, freeze when it stalls, and stop when it stops.
    // No capture, no permission, nothing recorded — ever.

    // ---- v6.1: gapless playback (crossfade-off path) ----
    /** A fully-built second player for the definite next track,
     *  prepared (not playing) ahead of the boundary. [activated]
     *  flips when the boundary swap installs it as [player]; the
     *  prep listener behaves accordingly. [resolved] is kept so a
     *  player rejection after activation can still advance the
     *  YouTube ladder. */
    private class GaplessPrep(
        val track: Track,
        val holder: EnginePlayer,
        val resolved: StreamResolver.Resolved?
    ) {
        @Volatile var ready = false
        @Volatile var activated = false
    }
    private var gaplessPrep: GaplessPrep? = null
    /** Track key the in-flight preload belongs to (double-arm guard). */
    private var gaplessArmingFor: String? = null
    /** One-shot boundary timer; fires the swap at duration − position. */
    private var gaplessFire: Runnable? = null

    private lateinit var queueOverlay: FrameLayout
    private lateinit var queueRecycler: RecyclerView

    private val tabViews = mutableMapOf<String, Triple<LinearLayout, ImageView, TextView>>()
    private val categoryArtViews = mutableListOf<ImageView>()

    // ---- v6 Spotify palette (replaces the Apple-Music red era) ----
    // The accent is a THEME VARIABLE (Arz: "dont lock the color
    // fully"): Spotify green by default, changeable in Settings >
    // Appearance via preset swatches or a 0–360 hue slider. Every
    // accent use in the app reads this getter at view-build time,
    // and changing the theme rebuilds the shell + current screen,
    // so the new hue applies live, everywhere, with playback
    // untouched. [accentColor] is seeded from prefs in onCreate.
    private var accentColor: Int = Color.rgb(29, 185, 84) // Spotify green #1DB954
    private val accent: Int get() = accentColor
    private val errorColor = Color.rgb(243, 98, 110)
    private val bgColor = Color.rgb(18, 18, 18) // #121212
    private val surface = Color.rgb(26, 26, 26) // #1A1A1A
    private val surface2 = Color.rgb(35, 35, 35) // #232323
    private val white50 = Color.rgb(167, 167, 167)
    private val white55 = Color.rgb(179, 179, 179) // #B3B3B3
    private val white75 = Color.argb(191, 255, 255, 255)
    private val placeholder = Color.rgb(35, 35, 35)

    // ---- v6 fonts: Poppins (SIL Open Font License 1.1), the
    // closest free geometric sans to Spotify's proprietary Circular.
    // Loaded once from assets; every label() in the app flows
    // through these four faces.
    private val boldTf: Typeface by lazy {
        Typeface.createFromAsset(assets, "fonts/Poppins-Bold.ttf")
    }
    private val semiboldTf: Typeface by lazy {
        Typeface.createFromAsset(assets, "fonts/Poppins-SemiBold.ttf")
    }
    private val mediumTf: Typeface by lazy {
        Typeface.createFromAsset(assets, "fonts/Poppins-Medium.ttf")
    }
    private val regularTf: Typeface by lazy {
        Typeface.createFromAsset(assets, "fonts/Poppins-Regular.ttf")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val ticker = object : Runnable {
        override fun run() {
            // Loop discipline (0.6.3, audit S4): backgrounded AND
            // silent, the body sleeps — 0.6.2 ran the full pass
            // (progress writes, lyrics sync, gapless checks) ~2x
            // per second forever, screen off included. Playing in
            // the background still runs it: gapless arming and
            // listening accumulation are playback features, not
            // UI refresh. The 500ms cadence itself is kept so a
            // resume is instant.
            if (activityStopped && !isPlayingSafe()) {
                handler.postDelayed(this, 500)
                return
            }
            accumulateListening()
            updateProgress()
            updateLyricsHighlight()
            maybeArmGapless()
            maybeWarmAfterNext()
            handler.postDelayed(this, 500)
        }
    }

    /** True between onStop and onStart: the activity is not
     *  visible. Drives the ticker's sleep (see [ticker]). */
    @Volatile
    private var activityStopped = false

    /**
     * Listening-time accumulation for the stats screen: while audio
     * is really playing, add the elapsed wall time since the last
     * tick. Flush to the store in ~5s chunks (and at every play()
     * and on destroy) so the total survives process death.
     */
    private fun accumulateListening() {
        val now = System.currentTimeMillis()
        if (lastTickMs == 0L) {
            lastTickMs = now
            return
        }
        val delta = now - lastTickMs
        lastTickMs = now
        if (delta in 1..2000 && isPlayingSafe()) {
            sessionListenedMs += delta
            if (sessionListenedMs >= 5000) flushListened()
        }
    }

    private fun flushListened() {
        if (sessionListenedMs <= 0) return
        PlayHistory.addListened(this, sessionListenedMs)
        sessionListenedMs = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 0.6.3 (WS-D Bug A — the zombie player): before anything
        // else, take process ownership of playback. The claim
        // releases EVERY player a previous instance of this
        // activity left behind — its onDestroy is not guaranteed
        // to run before the process is reused, and the pre-0.6.3
        // teardown never released the fade-out source at all —
        // and the dead owner's bridge snapshot is dropped so the
        // service stops mirroring a ghost in the shade.
        instanceId = PlayerRegistry.claim()
        PlaybackBridge.clearState()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        eqController = EqController(this)
        recentTracks.addAll(loadRecents())
        // Last-good Innertube client memory persists on disk so the
        // stream resolver tries the working context first.
        YtmApi.initStore(this)
        // Downloads live at process level now (0.6.3, WS-D Bug
        // D): this instance just lends the manager its UI ears,
        // and anything a dead process left mid-transfer RESUMES
        // on the manager's own executor instead of being stamped
        // failed (the old reconcileOnStart behavior).
        val dlListener: (String, String) -> Unit = { key, event -> onDownloadEvent(key, event) }
        myDlListener = dlListener
        Downloads.uiListener = dlListener
        Downloads.resumeInterruptedAsync(this, effectiveQuality())
        backfillFavorites()
        loadAccentPref()
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        // Background playback (0.6.1): register the transport
        // controls the media notification + session call back into
        // — the very same functions the mini player uses. The
        // engine stays here in the activity; the service only
        // renders state (see PlaybackService).
        val controls = PlaybackBridge.Controls(
            toggle = { togglePlayback() },
            next = { playNext() },
            prev = { playPrevious() },
            seekTo = { ms -> player?.seekTo(ms) },
            stop = { stopPlayback() }
        )
        myControls = controls
        PlaybackBridge.controls = controls
        showShell()
        showHome()
        handler.post(ticker)
        // Quiet update check (0.6.3, WS-E): at most once a day,
        // and it only ever speaks when an update EXISTS.
        checkForUpdates(manual = false)
    }

    /**
     * One-time rescue of pre-v5 likes: v4.1 stored favorites as bare
     * `fav_<id>` booleans with no track metadata. Where the track
     * can be found again (recents, downloads, local files), lift it
     * into the Liked Songs store so it shows up in the list.
     */
    private fun backfillFavorites() {
        val prefs = getPreferences(MODE_PRIVATE)
        val legacyIds = prefs.all.keys
            .filter { it.startsWith("fav_") && prefs.getBoolean(it, false) }
            .map { it.removePrefix("fav_") }
        if (legacyIds.isEmpty()) return
        val pool = HashMap<String, Track>()
        recentTracks.forEach { pool[it.id] = it }
        Downloads.entries(this).forEach { pool[it.track.id] = it.track }
        LocalTracks.all(this).forEach { pool[it.id] = it }
        legacyIds.forEach { id ->
            pool[id]?.let { t -> if (!Favorites.contains(this, t)) Favorites.add(this, t) }
        }
    }

    /** The shell's root, kept so the overlay trees can attach to
     *  it lazily (0.6.3, audit S7). */
    private var shellRoot: FrameLayout? = null
    private var overlaysBuilt = false

    /** Builds the four overlay trees on first need (0.6.3, S7):
     *  called at the top of every overlay entry point. A shell
     *  rebuild (theme/glass change) resets the flag in showShell,
     *  so the next open rebuilds them against the fresh shell. */
    private fun ensureOverlays() {
        if (overlaysBuilt) return
        val root = shellRoot ?: return
        overlaysBuilt = true
        buildNowPlaying(root)
        buildQueueOverlay(root)
        buildLyricsOverlay(root)
        buildMenuOverlay(root)
    }

    // ---------------------------------------------------------------- shell

    private fun showShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(bgColor) }

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(196))
        }
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // v6: the chrome sits on solid black (Spotify). 0.6.1:
            // with glass on, the bar goes translucent so scrolling
            // content ghosts through its margins. 0.6.3 (WS-B):
            // the glass bar is LIT from above (a vertical
            // lightening gradient) so it reads as glass, not as
            // another black slab.
            if (glassOn()) {
                background = glassBar(Color.argb(238, 32, 32, 36), Color.argb(230, 12, 12, 14))
            } else {
                setBackgroundColor(Color.BLACK)
            }
        }

        // ---- mini player ----
        // v6: a SOLID dark bar like the Spotify inspo (art, title,
        // artist, play/next). 0.6.1: glass effects (Settings,
        // default on) swap the solid fill for the pre-v6 fake
        // glass; off restores the solid bar exactly. Real actions
        // unchanged: tap opens Now Playing, swipe skips.
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (glassOn()) chromeGlass(8f) else solidBg(8f, surface2)
            setPadding(dp(8), dp(8), dp(12), dp(8))
            elevation = dp(10).toFloat()
            setOnClickListener { if (current != null) showNowPlaying() }
        }
        miniArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 8)
        }
        mini.addView(miniArtwork, LinearLayout.LayoutParams(dp(42), dp(42)))
        val miniText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(6), 0)
        }
        miniTitle = label("Nothing playing", 14.5f, Color.WHITE, boldTf)
        miniArtist = label("", 13f, white55)
        miniText.addView(miniTitle)
        miniText.addView(miniArtist)
        mini.addView(miniText, LinearLayout.LayoutParams(0, -2, 1f))
        miniPlayButton = icon(R.drawable.ic_play, Color.WHITE).apply {
            contentDescription = "Play"
            setOnClickListener { togglePlayback() }
        }
        mini.addView(miniPlayButton, LinearLayout.LayoutParams(dp(38), dp(38)))
        miniNextButton = icon(R.drawable.ic_next, Color.WHITE).apply {
            contentDescription = "Next track"
            setOnClickListener { playNext() }
        }
        mini.addView(miniNextButton, LinearLayout.LayoutParams(dp(38), dp(38)))
        // 0.6.9: mini transport joins the shared press system
        // (play = primary spring). The container's own swipe
        // listener is untouched — press attaches to the buttons.
        MotionPress.attach(miniPlayButton, primary = true) { springsOff() }
        MotionPress.attach(miniNextButton, primary = false) { springsOff() }
        visualizerView = visualizer(permanent = true).apply {
            // State comes from updateVisualizerState() (setting AND
            // actual playback); starting hidden avoids showing "playing"
            // bars before anything has ever played.
            visibility = View.GONE
        }
        mini.addView(visualizerView)
        // Swipe left/right on the mini player skips tracks (iOS 26.1
        // behavior, spec §1.1); a plain tap still opens Now Playing.
        var miniDownX = 0f
        mini.setOnTouchListener { _, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    miniDownX = e.x
                    false
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = e.x - miniDownX
                    when {
                        dx < -dp(48) -> {
                            playNext()
                            true
                        }
                        dx > dp(48) -> {
                            playPrevious()
                            true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }
        // Thin progress line along the mini player's bottom edge
        // (inspo), plus the buffering state: while a track resolves
        // or buffers, the line goes indeterminate.
        val miniWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val miniLp = LinearLayout.LayoutParams(-1, if (getPreferences(MODE_PRIVATE).getBoolean("setting_Compact mini-player", false)) dp(48) else dp(58))
        miniWrap.addView(mini, miniLp)
        miniProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            setPadding(0, 0, 0, 0)
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(50, 255, 255, 255))
        }
        val mpLp = LinearLayout.LayoutParams(-1, dp(3))
        mpLp.setMargins(dp(10), dp(1), dp(10), 0)
        miniWrap.addView(miniProgress, mpLp)
        val wrapLp = LinearLayout.LayoutParams(-1, -2)
        wrapLp.setMargins(dp(8), dp(4), dp(8), 0)
        bottomBar.addView(miniWrap, wrapLp)

        // ---- flat black tab bar (Spotify inspo) ----
        // 0.6.1: translucent glass-dark while glass is on.
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // 0.6.1: translucent glass-dark while glass is on.
            // 0.6.3 (WS-B): lit-from-above gradient, same rule as
            // the bottom bar — visible as glass over content.
            if (glassOn()) {
                background = glassBar(Color.argb(240, 40, 40, 46), Color.argb(235, 18, 18, 20))
            } else {
                setBackgroundColor(Color.BLACK)
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        val tabs = listOf(
            Triple("Home", R.drawable.ic_home, Runnable { showHome() }),
            Triple("Search", R.drawable.ic_search, Runnable { showSearch() }),
            Triple("Library", R.drawable.ic_library, Runnable { showLibrary() }),
            Triple("Downloads", R.drawable.ic_download, Runnable { showDownloads() }),
            Triple("Settings", R.drawable.ic_settings, Runnable { showSettings() })
        )
        tabs.forEach { (name, iconRes, action) ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = name
                setPadding(0, dp(7), 0, dp(5))
                setOnClickListener {
                    setActiveTab(name)
                    action.run()
                }
            }
            val iv = icon(iconRes, white55)
            val tv = label(name, 10f, white55, mediumTf).apply { gravity = Gravity.CENTER }
            box.addView(iv, LinearLayout.LayoutParams(dp(23), dp(23)))
            box.addView(tv)
            nav.addView(box, LinearLayout.LayoutParams(0, dp(52), 1f))
            tabViews[name] = Triple(box, iv, tv)
        }
        val navLp = LinearLayout.LayoutParams(-1, dp(58))
        navLp.topMargin = dp(4)
        bottomBar.addView(nav, navLp)
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // 0.6.3 (audit S7): the four overlay trees (Now Playing,
        // queue, lyrics, menu) are NOT built here anymore. They
        // cost real tree-building time at every startup and shell
        // rebuild for surfaces a session may never open.
        // ensureOverlays() builds them on first open instead —
        // every overlay entry point calls it, and the guarded
        // readers (onBackPressed, refreshPlaybackUi, the state
        // sync) already skip uninitialized overlays by design.
        shellRoot = root
        overlaysBuilt = false
        setContentView(root)
        setActiveTab("Home")
        // Re-fit on EVERY bar layout, not one posted runnable: the v4
        // post{} could fire before the bars were measured (height 0,
        // guard kept the stale default), and later height changes
        // (compact mini-player) were only covered if a post happened
        // to land after layout. A layout listener cannot miss.
        bottomBar.viewTreeObserver.addOnGlobalLayoutListener { fitContentPadding() }
        bottomBar.post { fitContentPadding() }
    }

    private var lastFitPadding = -1

    /**
     * Sizes the scroll content's bottom inset from the MEASURED floating
     * bars (mini player + tab bar) plus breathing room, so the last rows
     * can always scroll fully clear of both bars on any device. (v3 used
     * a fixed guess and rows ended up hidden underneath.) Guarded
     * against re-applying the same value, which would loop layouts.
     */
    private fun fitContentPadding() {
        if (!::bottomBar.isInitialized || !::content.isInitialized) return
        val target = bottomBar.height + dp(28)
        if (bottomBar.height > 0 && target != lastFitPadding) {
            lastFitPadding = target
            content.setPadding(dp(22), dp(10), dp(22), target)
        }
    }

    private fun setActiveTab(name: String) {
        activeTab = name
        tabViews.forEach { (tabName, views) ->
            val (_, iv, tv) = views
            // Spotify treatment: no pill — active is pure white,
            // inactive is #B3B3B3.
            if (tabName == name) {
                iv.setColorFilter(Color.WHITE)
                tv.setTextColor(Color.WHITE)
            } else {
                iv.setColorFilter(white55)
                tv.setTextColor(white55)
            }
        }
    }

    /** Pill action button: filled accent or glass, per page action rows. */
    private fun pillButton(text: String, filled: Boolean, onClick: () -> Unit): TextView {
        return label(text, 15f, Color.WHITE, boldTf).apply {
            gravity = Gravity.CENTER
            background = if (filled) {
                GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(999).toFloat()
                }
            } else {
                solidBg(999f, surface2)
            }
            isClickable = true
            setOnClickListener { onClick() }
            // 0.6.9: page primary buttons join the shared press
            // system at the helper, so every pill in the app
            // answers a tap the same way (filled = primary spring).
            MotionPress.attach(this, primary = filled) { springsOff() }
        }
    }

    /** Small accent dot shown beneath an active transport toggle. */
    private fun dotView(): View = View(this).apply {
        background = GradientDrawable().apply {
            setColor(accent)
            shape = GradientDrawable.OVAL
        }
        visibility = View.INVISIBLE
    }

    /** Solid rounded fill (Spotify surfaces are flat, not glass). */
    private fun solidBg(radiusDp: Float, fill: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    // ------------------------------------------------------------ v6.1: glass

    /** Glass effects toggle (Settings > Appearance, default ON).
     *  When ON, the chrome surfaces (mini player, bottom bar, menu
     *  sheets, queue backdrop) and Now Playing's card/control zone
     *  use the pre-v6 fake-glass treatment: a translucent fill plus
     *  a thin light stroke, with content subtly visible behind.
     *  NO runtime blur is ever rendered on scrolling surfaces —
     *  the only real blur in the app is Now Playing's precomputed
     *  artwork backdrop. When OFF, the v6 solid Spotify surfaces
     *  apply, exactly as 0.6.0 shipped. */
    private fun glassOn(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("setting_Glass effects", true)

    /** Layered glass (0.6.3, WS-B): the 0.6.1 treatment — a flat
     *  translucent near-black fill + a faint stroke — read as
     *  SOLID black over the app's #121212 screens, so the field
     *  verdict was "glass layers not showing" with the toggle on.
     *  Glass reads as glass through LIGHT, not darkness: a
     *  vertical lightening gradient (lit from above), a bright
     *  sheen band across the top edge, and a clearly visible
     *  rim stroke. Still zero runtime blur (perf law); still two
     *  static drawables per surface, drawn once. */
    private fun glassBg(
        radiusDp: Float,
        top: Int,
        bottom: Int,
        stroke: Int,
        sheenAlpha: Int
    ): Drawable {
        val r = dp(radiusDp.toInt()).toFloat()
        val body = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, bottom)
        ).apply {
            cornerRadius = r
            setStroke(dp(1), stroke)
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(sheenAlpha, 255, 255, 255),
                Color.argb((sheenAlpha * 0.4f).toInt(), 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            )
        ).apply { cornerRadius = r }
        return LayerDrawable(arrayOf(body, sheen))
    }

    /** Glass for the floating chrome (mini player, sheets): the
     *  fill stays translucent enough for content to ghost through,
     *  but the top of the surface is now lit at ~74/255 grey
     *  instead of 33 — unmistakably a lit pane, not a black box. */
    private fun chromeGlass(radiusDp: Float): Drawable =
        glassBg(
            radiusDp,
            Color.argb(238, 66, 66, 74), Color.argb(228, 36, 36, 42),
            Color.argb(120, 255, 255, 255), sheenAlpha = 110
        )

    /** Glass over Now Playing's genuinely blurred backdrop: the
     *  art behind is already soft, so the fill can go lighter. */
    private fun npGlass(radiusDp: Float): Drawable =
        glassBg(
            radiusDp,
            Color.argb(150, 58, 58, 66), Color.argb(120, 30, 30, 36),
            Color.argb(130, 255, 255, 255), sheenAlpha = 80
        )

    /** Full-width bar glass (tab bar / bottom bar): the same
     *  vertical lightening, no radius or rim (edge-to-edge). */
    private fun glassBar(top: Int, bottom: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, bottom)
        )

    /** Toggling glass rebuilds the shell + current screen (the
     *  same live-apply path as the theme accent): backgrounds are
     *  chosen at build time, and playback is never touched. */
    private fun applyGlassChange() {
        // Same rule as the accent: glass fills are chosen at build
        // time, so retained trees retire on a glass toggle.
        retainedRoots.clear()
        val tab = activeTab
        showShell()
        when (tab) {
            "Search" -> showSearch()
            "Library" -> showLibrary()
            "Downloads" -> showDownloads()
            "Settings" -> showSettings()
            else -> showHome()
        }
        setActiveTab(tab)
        refreshPlaybackUi()
    }

    // ------------------------------------------------------------ v6 theme color

    private fun loadAccentPref() {
        val p = getPreferences(MODE_PRIVATE)
        if (p.contains("theme_accent")) {
            accentColor = p.getInt("theme_accent", accentColor)
        }
    }

    /**
     * Applies a new theme accent: persists it, swaps the variable,
     * and rebuilds the shell + current screen so every view picks
     * the new hue up LIVE. Only views are rebuilt — the player
     * engine, queue and downloads are untouched, and any open
     * overlay (Now Playing, menus) closes.
     */
    private fun applyAccent(color: Int, hue: Float?) {
        // Retained trees carry build-time accent tints: a theme
        // change retires them all.
        retainedRoots.clear()
        accentColor = color
        val e = getPreferences(MODE_PRIVATE).edit().putInt("theme_accent", color)
        if (hue != null) e.putFloat("theme_hue", hue)
        e.apply()
        val tab = activeTab
        showShell()
        when (tab) {
            "Search" -> showSearch()
            "Library" -> showLibrary()
            "Downloads" -> showDownloads()
            "Settings" -> showSettings()
            else -> showHome()
        }
        setActiveTab(tab)
        refreshPlaybackUi()
    }

    /** Display name of the current theme accent (Settings row). */
    private fun themeName(): String = when (accentColor) {
        Color.rgb(29, 185, 84) -> "Spotify Green"
        Color.rgb(252, 60, 68) -> "arzxsh Red"
        Color.rgb(125, 42, 232) -> "Violet"
        Color.rgb(39, 136, 227) -> "Blue"
        Color.rgb(255, 159, 10) -> "Amber"
        Color.rgb(241, 91, 181) -> "Pink"
        else -> "Custom hue"
    }

    /** Theme picker: preset swatches + a 0–360 hue slider. */
    private fun showThemeSheet() {
        val presets = listOf(
            "Spotify Green" to Color.rgb(29, 185, 84),
            "arzxsh Red" to Color.rgb(252, 60, 68),
            "Violet" to Color.rgb(125, 42, 232),
            "Blue" to Color.rgb(39, 136, 227),
            "Amber" to Color.rgb(255, 159, 10),
            "Pink" to Color.rgb(241, 91, 181)
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        box.addView(label("Presets", 13f, white50, semiboldTf))
        val swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(4))
        }
        presets.forEach { (name, color) ->
            val sw = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(color)
                    shape = GradientDrawable.OVAL
                    if (color == accentColor) {
                        setStroke(dp(2), Color.WHITE)
                    }
                }
                contentDescription = name
                isClickable = true
                setOnClickListener {
                    applyAccent(color, null)
                    Toast.makeText(this@MainActivity, "Theme: $name", Toast.LENGTH_SHORT).show()
                }
            }
            val lp = LinearLayout.LayoutParams(dp(40), dp(40))
            lp.marginEnd = dp(12)
            swatchRow.addView(sw, lp)
        }
        box.addView(swatchRow)
        val hueNow = getPreferences(MODE_PRIVATE).getFloat("theme_hue", 145f)
        val hueLabel = label("Custom hue · ${hueNow.toInt()}°", 13f, white50, semiboldTf)
        val hlLp = LinearLayout.LayoutParams(-1, -2)
        hlLp.topMargin = dp(16)
        box.addView(hueLabel, hlLp)
        val hueSeek = SeekBar(this).apply {
            max = 360
            progress = hueNow.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    hueLabel.text = "Custom hue · $progress°"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val h = progress.toFloat()
                    // Vivid but readable on near-black: high value,
                    // strong-but-not-neon saturation.
                    val c = Color.HSVToColor(floatArrayOf(h, 0.72f, 0.92f))
                    applyAccent(c, h)
                }
            })
        }
        box.addView(hueSeek, LinearLayout.LayoutParams(-1, -2))
        box.addView(
            label(
                "Applies live to active states, progress, toggles and buttons. " +
                    "Changing it closes any open player or menu view; playback keeps going.",
                12.5f, white50
            )
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Theme color")
            .setView(box)
            .setPositiveButton("Done") { _, _ -> showSettings() }
            .show()
    }

    // ------------------------------------------------------------ v6 skeletons

    /**
     * One skeleton placeholder block. All blocks on screen pulse
     * from ONE shared animator (alpha only, no layout, no bitmaps —
     * cheap on the Helio G95); under Reduce Motion they sit static.
     * Blocks must be added to a LinearLayout (their layout params
     * are LinearLayout params).
     */
    private fun skelBlock(heightDp: Int, radiusDp: Int = 12, widthDp: Int = -1): View {
        val v = View(this).apply {
            background = solidBg(radiusDp.toFloat(), surface2)
            alpha = 0.5f
        }
        v.layoutParams = if (widthDp > 0) {
            LinearLayout.LayoutParams(dp(widthDp), dp(heightDp))
        } else {
            LinearLayout.LayoutParams(-1, dp(heightDp))
        }
        skeletonViews.add(v)
        startSkeletonPulse()
        return v
    }

    /** Skeleton twin of a track row: 52dp art square + two bars. */
    private fun skelRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(skelBlock(52, 9, 52))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        text.addView(skelBlock(13, 6, 170))
        val sub = skelBlock(11, 6, 110)
        (sub.layoutParams as LinearLayout.LayoutParams).topMargin = dp(7)
        text.addView(sub)
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun skelRows(target: LinearLayout, count: Int) {
        repeat(count) { i ->
            if (i > 0) target.addView(hairline())
            target.addView(skelRow())
        }
    }

    /** Section-title-width bar + rows, mirroring a real section. */
    private fun skelSection(target: LinearLayout, rows: Int) {
        val title = skelBlock(20, 8, 150)
        (title.layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        (title.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10)
        target.addView(title)
        skelRows(target, rows)
    }

    private fun startSkeletonPulse() {
        if (reduceMotion()) return
        if (skeletonAnimator?.isRunning == true) return
        val anim = skeletonAnimator ?: ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1300
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                if (skeletonViews.isEmpty()) {
                    skeletonAnimator?.cancel()
                    return@addUpdateListener
                }
                val phase = a.animatedValue as Float
                val alpha = 0.38f + 0.3f * (0.5f + 0.5f * kotlin.math.sin(phase))
                for (v in skeletonViews) v.alpha = alpha
            }
            skeletonAnimator = this
        }
        anim.start()
    }

    private fun clearSkeletons() {
        skeletonViews.clear()
        skeletonAnimator?.cancel()
    }

    /** Clips a view's content to a rounded-rect outline with a placeholder fill. */
    private fun roundedClip(v: View, cornerDp: Int) {
        v.background = GradientDrawable().apply {
            setColor(placeholder)
            cornerRadius = dp(cornerDp).toFloat()
        }
        v.clipToOutline = true
    }

    private fun icon(res: Int, tint: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        isClickable = true
        isFocusable = true
        // No Material ripple on player controls / chrome (spec §13.4):
        // feedback there is glyph swap + scale, handled at the call site.
    }

    /** Rectangular ripple press feedback for list rows. */
    private fun View.rippleRect() {
        val tv = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            setBackgroundResource(tv.resourceId)
        }
    }

    // ---------------------------------------------------------------- home

    private fun showHome() {
        navRoot("tab:home") { showHome() }
        // Retention (0.6.1): a built Home returns INSTANTLY — no
        // fetch, no skeleton. Past the ~30 min TTL it still
        // returns instantly, and a background refresh swaps fresh
        // data in when ready (stale-while-revalidate): a warm
        // screen is never blanked.
        if (restoreRoot("Home")) {
            val entry = retainedRoots["Home"]
            if (entry != null &&
                System.currentTimeMillis() - entry.builtAtMs > homeTtlMs
            ) {
                buildHome(warmRefresh = true)
            }
            return
        }
        buildHome(warmRefresh = false)
    }

    private fun buildHome(warmRefresh: Boolean) {
        navRoot("tab:home") { showHome() }
        val gen = ++generation
        val warmToken = if (warmRefresh) ++homeWarmToken else homeWarmToken
        // Cold builds show the v6 skeleton twin of the real home
        // layout (hero card + section rows at the same geometry),
        // never a bare "Loading…" line. Warm refreshes build NO
        // skeleton: the retained tree stays on screen untouched
        // until the swap.
        val skeleton: LinearLayout? = if (warmRefresh) {
            null
        } else {
            beginScreen("Listen Now", showAvatar = true)
            displayedRootTab = "Home"
            homeMainDone = false
            homeMixPending = false
            val sk = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val heroSkel = skelBlock(196, 16)
            (heroSkel.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
            sk.addView(heroSkel)
            skelSection(sk, 4)
            content.addView(sk)
            sk
        }
        val mode = sourceMode()
        executor.execute {
            val audius = fetch("/tracks/trending?limit=25")
            var charts: YtmCharts? = null
            var chartsErr: String? = null
            // Audius mode fires no YouTube calls at all.
            if (mode != "audius") {
                try {
                    charts = YtmApi.charts()
                } catch (t: Throwable) {
                    chartsErr = shortErr(t)
                }
            }
            runOnUiThread {
                if (warmRefresh) {
                    // SWR swap: only while the warm Home is still
                    // on screen, and only for the latest refresh.
                    // The wipe + render happen inside this one
                    // block, so no blank or skeleton frame exists.
                    if (warmToken != homeWarmToken || displayedRootTab != "Home") {
                        return@runOnUiThread
                    }
                    retainedRoots["Home"]?.builtAtMs = System.currentTimeMillis()
                    beginScreen("Listen Now", showAvatar = true)
                    displayedRootTab = "Home"
                    homeMixPending = false
                } else {
                    if (gen != generation) return@runOnUiThread
                    if (skeleton != null) content.removeView(skeleton)
                    clearSkeletons()
                }
                // The render below runs to completion inside this
                // block (UI-thread atomic), so "main done" can be
                // set now: a tab switch can only be processed after
                // the render has fully landed.
                homeMainDone = true
                var any = false
                if (audius != null && audius.isNotEmpty()) {
                    tracks.clear()
                    tracks.addAll(audius)
                }
                // Featured prefers YouTube Music (the primary source);
                // Audius headlines only when charts are unavailable.
                val featured = if (mode == "audius") {
            audius?.firstOrNull() ?: charts?.songs?.firstOrNull()
        } else {
            charts?.songs?.firstOrNull() ?: audius?.firstOrNull()
        }
                if (featured != null) {
                    val context = if (featured.source == SOURCE_YOUTUBE) {
                        charts?.songs ?: listOf(featured)
                    } else {
                        audius ?: listOf(featured)
                    }
                    val kicker = if (featured.source == SOURCE_YOUTUBE) {
                        "TRENDING ON YOUTUBE MUSIC"
                    } else {
                        "FEATURED MIX"
                    }
                    content.addView(featuredCard(featured, kicker, context))
                    any = true
                }
                if (recentTracks.isNotEmpty()) {
                    content.addView(sectionTitle("Recently Played"))
                    addRows(recentTracks, withHairlines = true, queueContext = recentTracks.toList())
                    any = true
                }
                if (charts != null && charts.songs.isNotEmpty()) {
                    content.addView(sectionTitle("Trending on YouTube Music"))
                    val top = charts.songs.take(12)
                    addRows(top, withHairlines = true, queueContext = top)
                    any = true
                } else if (chartsErr != null) {
                    // A dead YouTube section shows WHY, never a silent hole.
                    content.addView(sectionTitle("Trending on YouTube Music"))
                    content.addView(errorLine("YouTube unavailable: $chartsErr"))
                    any = true
                }
                if (charts != null && charts.artists.isNotEmpty()) {
                    content.addView(sectionTitle("Top Artists"))
                    content.addView(artistCircleRow(charts.artists.take(10)))
                    any = true
                }
                // Made for You: an on-device mix — YouTube Music radio
                // seeded by his most-played YouTube track, labeled as
                // generated here. No seed (nothing played yet, or
                // Audius mode) → the section simply doesn't exist.
                if (mode != "audius") {
                    val seed = PlayHistory.topTracks(this@MainActivity, 20)
                        .firstOrNull { it.first.source == SOURCE_YOUTUBE }?.first
                    if (seed != null) {
                        content.addView(sectionTitle("Made for You"))
                        val mixBox = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                        }
                        skelRows(mixBox, 3)
                        content.addView(mixBox)
                        any = true
                        homeMixPending = true
                        executor.execute {
                            var mix: List<Track>? = null
                            var mixErr: String? = null
                            try {
                                mix = YtmApi.radio(seed.id)
                            } catch (t: Throwable) {
                                mixErr = shortErr(t)
                            }
                            runOnUiThread {
                                if (gen != generation) return@runOnUiThread
                                homeMixPending = false
                                mixBox.removeAllViews()
                                val m = mix
                                if (m == null || m.isEmpty()) {
                                    mixBox.addView(
                                        statusLine("Mix unavailable: ${mixErr ?: "empty radio"}")
                                    )
                                } else {
                                    mixBox.addView(
                                        statusLine(
                                            "Generated on this device · radio from “${seed.title}” by ${seed.artist}"
                                        )
                                    )
                                    addRows(
                                        m.take(15), mixBox,
                                        withHairlines = true, queueContext = m
                                    )
                                }
                            }
                        }
                    }
                }
                // In YouTube Music mode the Audius section is the
                // fallback: it appears only when the charts failed
                // or came back empty. Audius/Both modes always show it.
                val showAudiusSection = mode != "youtube" ||
                    charts == null || charts.songs.isEmpty()
                if (showAudiusSection && audius != null && audius.size > 1) {
                    content.addView(sectionTitle("Trending on Audius"))
                    val rest = audius.drop(1).take(12)
                    addRows(rest, withHairlines = true, queueContext = rest)
                    any = true
                }
                if (!any) {
                    content.addView(label("Nothing to play right now", 18f, Color.WHITE, boldTf))
                    content.addView(label("Trending tracks are unavailable right now.", 14f, white50))
                }
            }
        }
    }

    private fun featuredCard(track: Track, kicker: String, context: List<Track>): FrameLayout {
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 20, 23))
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
            setOnClickListener {
                setQueue(context)
                play(track)
            }
        }
        val lp = LinearLayout.LayoutParams(-1, dp(196))
        lp.topMargin = dp(16)
        card.layoutParams = lp
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        ArtworkLoader.load(largeArt(track), img, track.artworkSmall)
        card.addView(img, FrameLayout.LayoutParams(-1, -1))
        val shade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(184, 0, 0, 0))
            )
        }
        card.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(13))
        }
        labels.addView(label(kicker, 11f, white75, boldTf).apply { letterSpacing = 0.11f })
        labels.addView(TextView(this).apply {
            text = "${track.title}\n${track.artist}"
            setTextSize(20f)
            setTextColor(Color.WHITE)
            typeface = boldTf
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
        })
        card.addView(labels, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return card
    }

    // ---------------------------------------------------------------- search

    private fun showSearch() {
        navRoot("tab:search") { showSearch() }
        // Retention (0.6.1): query text + results survive the tab
        // switch — the retained tree IS the search screen.
        if (restoreRoot("Search")) return
        searchEpoch++
        val gen = ++generation
        beginScreen("Search", showAvatar = true)
        displayedRootTab = "Search"
        categoryArtViews.clear()

        lateinit var resultsBox: LinearLayout
        lateinit var browseBox: LinearLayout
        lateinit var input: EditText

        fun runSearch(query: String) {
            val q = query.trim()
            if (q.isEmpty()) return
            searchInFlight = true
            val myEpoch = searchEpoch
            browseBox.visibility = View.GONE
            resultsBox.visibility = View.VISIBLE
            resultsBox.removeAllViews()
            resultsBox.addView(sectionTitle("Results for \"$q\""))
            // Skeleton mirrors the results layout (0.6.1): a hero
            // block where the Top result card lands, then rows.
            val heroSkel = skelBlock(100, 14)
            (heroSkel.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(16)
            resultsBox.addView(heroSkel)
            skelRows(resultsBox, 5)
            val mode = sourceMode()
            executor.execute {
                // 0.6.3 (audit S5): the five source calls are
                // independent, but they used to run STRICTLY
                // sequentially here, so search wall time was the
                // SUM of five round trips. They now fly
                // concurrently on the fetch pool and the results
                // render ONCE, below, when all five have settled.
                // Each call keeps its own guard and its own
                // rendered error, and the composition that follows
                // is unchanged.
                val audiusF = fetchPool.submit<List<Track>?> {
                    fetch("/tracks/search?query=${URLEncoder.encode(q, "UTF-8")}&limit=25")
                }
                val ytSongsF = if (mode == "audius") null else fetchPool.submit<Pair<SongSearchResult?, String?>> {
                    try {
                        YoutubeSource.searchSongs(q) to null as String?
                    } catch (t: Throwable) {
                        null to shortErr(t)
                    }
                }
                val ytArtistsF = if (mode == "audius") null else fetchPool.submit<Pair<List<YtmArtistRef>?, String?>> {
                    try {
                        YtmApi.searchArtists(q) to null as String?
                    } catch (t: Throwable) {
                        null to shortErr(t)
                    }
                }
                val ytAlbumsF = if (mode == "audius") null else fetchPool.submit<Pair<List<YtmAlbumRef>?, String?>> {
                    try {
                        YtmApi.searchAlbums(q) to null as String?
                    } catch (t: Throwable) {
                        null to shortErr(t)
                    }
                }
                val ytPlaylistsF = if (mode == "audius") null else fetchPool.submit<Pair<List<YtmPlaylistRef>?, String?>> {
                    try {
                        YtmApi.searchPlaylists(q) to null as String?
                    } catch (t: Throwable) {
                        null to shortErr(t)
                    }
                }
                val audius = try {
                    audiusF.get()
                } catch (_: Exception) {
                    null
                }
                // Every YouTube call is separately guarded and its
                // failure is RENDERED below the results (v3 swallowed
                // the exception and YouTube quietly never appeared).
                // Audius mode fires NO YouTube calls at all.
                val ytSongsRes = ytSongsF?.get()
                val ytSongs: List<Track> = ytSongsRes?.first?.tracks ?: emptyList()
                val ytNote: String? = ytSongsRes?.first?.note
                val ytErr: String? = ytSongsRes?.second
                val ytArtistsRes = ytArtistsF?.get()
                val ytArtists: List<YtmArtistRef> = ytArtistsRes?.first ?: emptyList()
                val ytArtistsErr: String? = ytArtistsRes?.second
                val ytAlbumsRes = ytAlbumsF?.get()
                val ytAlbums: List<YtmAlbumRef> = ytAlbumsRes?.first ?: emptyList()
                val ytAlbumsErr: String? = ytAlbumsRes?.second
                val ytPlaylistsRes = ytPlaylistsF?.get()
                val ytPlaylists: List<YtmPlaylistRef> = ytPlaylistsRes?.first ?: emptyList()
                val ytPlaylistsErr: String? = ytPlaylistsRes?.second
                // One merged song list, YouTube Music FIRST (it is
                // the primary source now), Audius after — both fully
                // present and playable, and next/previous playback
                // flows across sources.
                // Composition follows the source mode: YouTube
                // Music leads; Audius-only in Audius mode; in
                // YouTube mode Audius appears only as the fallback
                // when YouTube songs failed or came back empty.
                val found = mutableListOf<Track>()
                when (mode) {
                    "audius" -> audius?.let { found.addAll(it) }
                    "youtube" -> {
                        if (ytSongs.isNotEmpty()) found.addAll(ytSongs)
                        else audius?.let { found.addAll(it) }
                    }
                    else -> {
                        found.addAll(ytSongs)
                        audius?.let { found.addAll(it) }
                    }
                }
                runOnUiThread {
                    // A stale search's completion must not clear
                    // the NEWER search's in-flight flag (0.6.3):
                    // the epoch check comes first now.
                    if (myEpoch != searchEpoch) return@runOnUiThread
                    searchInFlight = false
                    resultsBox.removeAllViews()
                    resultsBox.addView(sectionTitle("Results for \"$q\""))
                    val nothing = found.isEmpty() && ytArtists.isEmpty() &&
                        ytAlbums.isEmpty() && ytPlaylists.isEmpty()
                    if (nothing) {
                        if (audius == null && ytErr != null) {
                            resultsBox.addView(label("Playable results are unavailable right now.", 14f, white50))
                        } else {
                            resultsBox.addView(label("No results", 18f, Color.WHITE, boldTf))
                        }
                    }
                    // Section order (0.6.1): Top result → Songs →
                    // Artists → Albums → Playlists. Songs used to
                    // render LAST, below the fold, behind Artists and
                    // Albums — a title search made the user scroll
                    // and hunt for the actual song.
                    // The sections BELOW the songs render from
                    // onDone (0.6.3): the song rows now build over
                    // several frames, and Artists/Albums/Playlists
                    // must stay under them, in the same order.
                    val renderTail = {
                        if (ytArtists.isNotEmpty()) {
                            resultsBox.addView(sectionTitle("Artists"))
                            ytArtists.forEach { resultsBox.addView(artistResultRow(it)) }
                        }
                        if (ytAlbums.isNotEmpty()) {
                            resultsBox.addView(sectionTitle("Albums"))
                            ytAlbums.forEachIndexed { i, album ->
                                if (i > 0) resultsBox.addView(hairline())
                                resultsBox.addView(albumResultRow(album))
                            }
                        }
                        if (ytPlaylists.isNotEmpty()) {
                            resultsBox.addView(sectionTitle("Playlists"))
                            ytPlaylists.forEachIndexed { i, ref ->
                                if (i > 0) resultsBox.addView(hairline())
                                resultsBox.addView(ytmPlaylistRow(ref))
                            }
                        }
                        // Source status: always on screen, success or failure.
                        val errors = listOfNotNull(
                            ytErr?.let { "songs: $it" },
                            ytArtistsErr?.let { "artists: $it" },
                            ytAlbumsErr?.let { "albums: $it" },
                            ytPlaylistsErr?.let { "playlists: $it" }
                        )
                        if (errors.isNotEmpty()) {
                            resultsBox.addView(errorLine("YouTube unavailable: " + errors.joinToString(" · ")))
                        }
                        if (mode == "youtube" && ytSongs.isEmpty() && !audius.isNullOrEmpty()) {
                            // Search-level fallback, stated on screen:
                            // YouTube failed or came back empty, so
                            // Audius is carrying this search.
                            resultsBox.addView(statusLine("YouTube songs unavailable — showing Audius results instead."))
                        }
                        if (mode == "audius") {
                            resultsBox.addView(statusLine("Source: Audius only — YouTube Music is off."))
                        }
                        if (ytNote != null) {
                            resultsBox.addView(statusLine("YouTube: $ytNote"))
                        }
                        if (mode != "audius" && errors.isEmpty() && ytNote == null) {
                            resultsBox.addView(
                                statusLine(
                                    "YouTube Music: ${ytSongs.size} songs · ${ytArtists.size} artists · ${ytAlbums.size} albums · ${ytPlaylists.size} playlists"
                                )
                            )
                        }
                    }
                    if (found.isNotEmpty()) {
                        resultsBox.addView(sectionTitle("Top result"))
                        resultsBox.addView(topResultCard(found.first(), found, q))
                        resultsBox.addView(sectionTitle("Songs"))
                        setQueue(found)
                        // Chunked (audit S1): searchInFlight stays
                        // true until the rows land so the Search
                        // root never snapshots half-built.
                        searchInFlight = true
                        val genAtRender = generation
                        addRowsChunked(
                            found, resultsBox,
                            queueContext = found,
                            contextLabel = "Search" to q,
                            alive = { myEpoch == searchEpoch && genAtRender == generation },
                            onDone = { completed ->
                                searchInFlight = false
                                if (completed) renderTail()
                            }
                        )
                    } else {
                        renderTail()
                    }
                }
            }
        }

        // The search field is the FIRST thing on the screen, above
        // the source selector, and impossible to miss: full width,
        // 48dp tall, white text with a visible cursor, and a clear
        // (✕) button while it holds text. v5's field sat UNDER the
        // segmented control at 38dp with no clear button, and the
        // activity declared no soft-input mode, so typed text could
        // end up hidden behind the keyboard — a tester searched and
        // never saw her own query (2026-11-07). The manifest now
        // sets adjustResize; submitting keeps the query in the field
        // and echoes it over the results ("Results for …").
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(38, 255, 255, 255))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), 0, dp(6), 0)
        }
        val fLp = LinearLayout.LayoutParams(-1, dp(48))
        fLp.topMargin = dp(14)
        field.layoutParams = fLp
        field.addView(icon(R.drawable.ic_search, white50), LinearLayout.LayoutParams(dp(18), dp(18)))
        val clearBtn = label("✕", 17f, white55).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
            contentDescription = "Clear search"
            setOnClickListener {
                input.text?.clear()
                resultsBox.removeAllViews()
                resultsBox.visibility = View.GONE
                browseBox.visibility = View.VISIBLE
                input.requestFocus()
            }
        }
        input = EditText(this).apply {
            hint = "Artists, Songs, Lyrics and More"
            setTextColor(Color.WHITE)
            setHintTextColor(white50)
            setTextSize(16.5f)
            setSingleLine(true)
            background = null
            isCursorVisible = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8), 0, 0, 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runSearch(text.toString())
                    // Results get the screen; the query stays in the
                    // field above them and is echoed over the results.
                    val imm = getSystemService(INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                    clearFocus()
                    true
                } else false
            }
        }
        field.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        field.addView(clearBtn, LinearLayout.LayoutParams(dp(38), -1))

        // Source selector: the same three states as Settings →
        // Music source, two-way synced through the one preference.
        // Glass pill track, accent fill on the active segment.
        val segRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = solidBg(999f, surface2)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val segLp = LinearLayout.LayoutParams(-1, dp(40))
        segLp.topMargin = dp(12)
        segRow.layoutParams = segLp
        val segViews = ArrayList<Pair<String, TextView>>()
        fun renderSegs() {
            val active = sourceMode()
            segViews.forEach { (mode, tv) ->
                val selected = mode == active
                // Spotify chip treatment: white pill, black text.
                tv.background = if (selected) {
                    GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(999).toFloat()
                    }
                } else null
                tv.setTextColor(if (selected) Color.BLACK else white55)
            }
        }
        listOf(
            "youtube" to "YouTube Music",
            "audius" to "Audius",
            "both" to "Both"
        ).forEach { (mode, name) ->
            val tv = label(name, 13f, white55, mediumTf).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener {
                    if (sourceMode() != mode) {
                        setSourceMode(mode)
                        renderSegs()
                        val q = input.text?.toString().orEmpty()
                        if (q.isNotBlank()) runSearch(q)
                    }
                }
            }
            segRow.addView(tv, LinearLayout.LayoutParams(0, -1, 1f))
            segViews += mode to tv
        }
        renderSegs()
        content.addView(field)
        content.addView(segRow)

        resultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        content.addView(resultsBox)

        browseBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        browseBox.addView(sectionTitle("Recent Searches"))
        if (recentTracks.isEmpty()) {
            browseBox.addView(label("Tracks you play will appear here.", 14.5f, white50))
        } else {
            recentTracks.take(4).forEach { track -> browseBox.addView(recentRow(track)) }
        }
        browseBox.addView(sectionTitle("Browse Categories"))
        val categories = listOf(
            Triple("Bass", Color.argb(96, 252, 60, 68), "bass"),
            Triple("Electronic", Color.argb(102, 125, 42, 232), "electronic"),
            Triple("Mixes", Color.argb(96, 10, 132, 255), "mix"),
            Triple("Radio", Color.argb(96, 255, 159, 10), "radio")
        )
        for (rowIdx in 0 until 2) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIdx > 0) setPadding(0, dp(13), 0, 0)
            }
            for (colIdx in 0 until 2) {
                val (name, tint, term) = categories[rowIdx * 2 + colIdx]
                val cardLp = LinearLayout.LayoutParams(0, dp(104), 1f)
                if (colIdx == 0) cardLp.marginEnd = dp(13)
                rowLayout.addView(categoryCard(name, tint) {
                    // The field shows what was actually searched.
                    input.setText(term)
                    runSearch(term)
                }, cardLp)
            }
            browseBox.addView(rowLayout)
        }
        // Honest label: the four cards above run a SEARCH for their
        // term. Real catalog browsing lives in Moods & Genres below.
        browseBox.addView(statusLine("These four cards run a search."))
        browseBox.addView(sectionTitle("Moods & Genres"))
        val moodsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        browseBox.addView(moodsBox)
        renderMoodsInto(moodsBox, gen, "Moods live on YouTube Music — switch the source above to browse them.")
        content.addView(browseBox)

        if (tracks.isEmpty()) {
            executor.execute {
                val result = fetch("/tracks/trending?limit=25")
                runOnUiThread {
                    if (gen != generation || result == null) return@runOnUiThread
                    tracks.clear()
                    tracks.addAll(result)
                    categoryArtViews.forEachIndexed { i, iv ->
                        tracks.getOrNull(i)?.let { ArtworkLoader.load(it.artworkLarge ?: it.artworkSmall, iv) }
                    }
                }
            }
        } else {
            categoryArtViews.forEachIndexed { i, iv ->
                tracks.getOrNull(i)?.let { ArtworkLoader.load(it.artworkLarge ?: it.artworkSmall, iv) }
            }
        }
    }

    private fun categoryCard(name: String, tint: Int, onClick: () -> Unit): FrameLayout {
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(13).toFloat()
            }
            clipToOutline = true
            setOnClickListener { onClick() }
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        categoryArtViews.add(img)
        card.addView(img, FrameLayout.LayoutParams(-1, -1))
        card.addView(View(this).apply { setBackgroundColor(tint) }, FrameLayout.LayoutParams(-1, -1))
        val tv = label(name, 16.5f, Color.WHITE, boldTf).apply {
            setPadding(dp(13), 0, dp(13), dp(10))
            setShadowLayer(6f, 0f, 1f, Color.argb(115, 0, 0, 0))
        }
        card.addView(tv, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return card
    }

    private fun recentRow(track: Track): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            rippleRect()
            setOnClickListener { play(track) }
            setOnLongClickListener {
                showTrackMenu(track)
                true
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 8)
        }
        ArtworkLoader.load(track.artworkSmall ?: track.artworkLarge, img, null, dp(46))
        row.addView(img, LinearLayout.LayoutParams(dp(46), dp(46)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        text.addView(label(track.title, 16f, Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        text.addView(label(track.artist, 14f, white50).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    // ---------------------------------------------------------------- settings / empty

    /** Small-caps group header, the reference apps' settings shape. */
    private fun settingsGroupHeader(text: String): TextView {
        // Breathing room (0.6.4, WS-3): sections were separated
        // by 26dp above and only 4dp below the header, so groups
        // read as one cramped column. 34/10 gives each section a
        // visible start.
        val tv = label(text.uppercase(), 13f, white50, boldTf).apply { letterSpacing = 0.12f }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(34)
        lp.bottomMargin = dp(10)
        tv.layoutParams = lp
        return tv
    }

    /** Tappable settings row: title + optional subtitle + chevron. */
    private fun settingsRow(
        title: String,
        subtitle: String?,
        titleColor: Int = Color.WHITE,
        iconRes: Int? = null,
        iconTint: Int = white55,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Spacing pass (0.6.4, WS-3): rows were 11dp-padded
            // (~45dp single-line — under the 48dp touch floor —
            // and visually tight). 15dp padding + a 60dp floor
            // puts every row at a comfortable, consistent height.
            setPadding(0, dp(15), 0, dp(15))
            minimumHeight = dp(60)
            rippleRect()
            isClickable = true
            setOnClickListener { onClick() }
        }
        // Leading glyph (0.6.1 icon pass, 24dp since WS-3):
        // secondary tint — accent only where the row itself is
        // accent-relevant (Theme color passes iconTint = accent).
        if (iconRes != null) {
            val ivLp = LinearLayout.LayoutParams(dp(24), dp(24))
            ivLp.marginEnd = dp(14)
            row.addView(icon(iconRes, iconTint), ivLp)
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(title, 16f, titleColor))
        if (subtitle != null) {
            val sub = label(subtitle, 13.5f, white50)
            val subLp = LinearLayout.LayoutParams(-1, -2)
            subLp.topMargin = dp(3)
            text.addView(sub, subLp)
        }
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("›", 22f, white50))
        return row
    }

    private fun fmtBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** Signed seconds label for a lyric offset, e.g. "+0.3s". */
    private fun fmtOffset(ms: Long): String {
        val sign = if (ms < 0) "−" else "+"
        return "$sign${"%.1f".format(kotlin.math.abs(ms) / 1000f)}s"
    }

    /** Global default lyric offset picker (Settings → Playback):
     *  − / + steppers in 0.1s steps over ±5s + Reset. Applies to
     *  every track without its own per-track offset. */
    private fun showGlobalOffsetSheet() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        val value = label(fmtOffset(LyricOffsets.globalMs(this)), 20f, Color.WHITE, boldTf).apply {
            gravity = Gravity.CENTER
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun stepper(txt: String, delta: Long): TextView =
            label(txt, 26f, Color.WHITE, boldTf).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setOnClickListener {
                    val now = (LyricOffsets.globalMs(this@MainActivity) + delta)
                        .coerceIn(-5000L, 5000L)
                    LyricOffsets.setGlobalMs(this@MainActivity, now)
                    value.text = fmtOffset(now)
                }
            }
        row.addView(stepper("−", -100L), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value, LinearLayout.LayoutParams(0, -2, 1.4f))
        row.addView(stepper("+", 100L), LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        box.addView(label("Reset", 15f, accent, mediumTf).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(0, dp(10), 0, dp(6))
            setOnClickListener {
                LyricOffsets.setGlobalMs(this@MainActivity, 0L)
                value.text = fmtOffset(0L)
            }
        })
        android.app.AlertDialog.Builder(this).setTitle("Lyrics sync offset").setView(box)
            .setPositiveButton("Done") { _, _ -> showSettings() }.show()
    }

    /** Crossfade length picker: a real 0–12s slider (0 = off, the
     *  default). Writes the same "crossfade_seconds" pref the player
     *  reads on every transition, so changes apply to the next track
     *  change with no restart. */
    private fun showCrossfadeSheet() {
        val current = crossfadeSec()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val value = label(
            if (current == 0) "Off" else "$current seconds", 16f, Color.WHITE, mediumTf
        )
        box.addView(value)
        val seek = SeekBar(this).apply {
            max = 12
            progress = current
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = if (progress == 0) "Off" else "$progress seconds"
                    getPreferences(MODE_PRIVATE).edit()
                        .putInt("crossfade_seconds", progress).apply()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val sLp = LinearLayout.LayoutParams(-1, -2)
        sLp.topMargin = dp(10)
        box.addView(seek, sLp)
        box.addView(
            label("Applies when one track flows into the next. Repeat One never fades.", 13f, white50)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Crossfade")
            .setView(box)
            .setPositiveButton("Done") { _, _ -> showSettings() }
            .show()
    }

    /** Built-in equalizer sheet (0.6.8): master switch, preset
     *  chips, one vertical slider per band and a bass-boost
     *  slider — every control wired straight to [EqController],
     *  which persists the change and pushes it to the live audio
     *  session in the same call. The pre-0.6.8 system effects
     *  panel stays one tap away at the bottom. Lives in Settings
     *  → Audio, on the row that used to open the system panel
     *  directly: a sheet, like Crossfade and the lyrics offset,
     *  because that is this app's settings idiom. */
    private fun showEqualizerSheet() {
        // Attach to the live session first (owner-only, like the
        // state funnel) so band count, centres and range are the
        // device's real ones, not the cached profile.
        if (PlayerRegistry.isOwner(instanceId)) {
            eqController.sync(player?.audioSessionId ?: 0)
        }
        val bands = eqController.uiBandCount()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(6), dp(24), 0)
        }
        var refreshChips: () -> Unit = {}
        var refreshSliders: () -> Unit = {}

        // Master switch.
        val masterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        masterRow.addView(
            label("Equalizer", 16f, Color.WHITE, mediumTf),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        masterRow.addView(Switch(this).apply {
            isChecked = eqController.enabled
            thumbTintList = ColorStateList.valueOf(accent)
            trackTintList = ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, checked -> eqController.enabled = checked }
        })
        box.addView(masterRow)

        // Preset chips. "Custom" is a state, not a chip: moving
        // any slider selects it and every chip simply unlights.
        val chips = mutableMapOf<String, TextView>()
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(2))
        }
        for (name in EqController.PRESETS.keys) {
            val chip = label(name, 13.5f, white55, mediumTf).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(13), dp(7), dp(13), dp(7))
                setOnClickListener {
                    eqController.setPreset(name)
                    refreshSliders()
                    refreshChips()
                }
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = dp(8)
            chipRow.addView(chip, lp)
            chips[name] = chip
        }
        box.addView(chipRow)
        refreshChips = {
            val active = eqController.presetName
            for ((name, chip) in chips) {
                val on = name == active
                chip.setTextColor(if (on) accent else white55)
                chip.background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (on) surface2 else Color.TRANSPARENT)
                    setStroke(dp(1), if (on) accent else white50)
                }
            }
        }

        // Band sliders: horizontal SeekBars rotated -90° inside
        // fixed-height frames — the stock way to get vertical
        // sliders from the framework widget. Progress 0..200
        // maps to the stored fraction -1..+1.
        val seekbars = arrayOfNulls<SeekBar>(bands)
        val valueLabels = arrayOfNulls<TextView>(bands)
        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        for (band in 0 until bands) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val value = label(eqController.dbLabel(band), 11f, white55, mediumTf).apply {
                gravity = Gravity.CENTER
            }
            valueLabels[band] = value
            col.addView(value)
            val seek = SeekBar(this).apply {
                max = 200
                progress = ((eqController.bandFraction(band) + 1f) * 100f).toInt()
                progressTintList = ColorStateList.valueOf(accent)
                thumbTintList = ColorStateList.valueOf(accent)
                rotation = -90f
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        eqController.setBandFraction(band, progress / 100f - 1f)
                        valueLabels[band]?.text = eqController.dbLabel(band)
                        refreshChips()
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            seekbars[band] = seek
            val frame = FrameLayout(this)
            val seekLp = FrameLayout.LayoutParams(dp(150), dp(44))
            seekLp.gravity = Gravity.CENTER
            frame.addView(seek, seekLp)
            val frameLp = LinearLayout.LayoutParams(-1, dp(158))
            frameLp.topMargin = dp(4)
            col.addView(frame, frameLp)
            col.addView(
                label(eqController.centerLabel(band), 11f, white55, mediumTf).apply {
                    gravity = Gravity.CENTER
                }
            )
            sliderRow.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        box.addView(sliderRow)
        refreshSliders = {
            for (band in 0 until bands) {
                seekbars[band]?.progress =
                    ((eqController.bandFraction(band) + 1f) * 100f).toInt()
                valueLabels[band]?.text = eqController.dbLabel(band)
            }
        }

        // Bass boost: one horizontal slider, percent of the
        // audiofx strength range.
        val bassValue = label(
            "Bass boost · ${eqController.bassStrength / 10}%", 14f, Color.WHITE, mediumTf
        )
        val bassLp = LinearLayout.LayoutParams(-1, -2)
        bassLp.topMargin = dp(16)
        box.addView(bassValue, bassLp)
        box.addView(SeekBar(this).apply {
            max = 100
            progress = eqController.bassStrength / 10
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    eqController.setBassStrength(progress * 10)
                    bassValue.text = "Bass boost · $progress%"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        lateinit var dialog: android.app.AlertDialog
        box.addView(label("System effects panel instead", 13.5f, accent, mediumTf).apply {
            isClickable = true
            setPadding(0, dp(14), 0, dp(4))
            setOnClickListener {
                dialog.dismiss()
                openEqualizer()
            }
        })
        dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Equalizer")
            .setView(box)
            .setPositiveButton("Done") { _, _ -> showSettings() }
            .create()
        refreshChips()
        dialog.show()
    }

    private fun setStreamQuality(q: String) {
        getPreferences(MODE_PRIVATE).edit().putString("stream_quality", q).apply()
        val name = when (q) {
            "data" -> "Data saver"
            "high" -> "High"
            else -> "Normal"
        }
        Toast.makeText(this, "Streaming quality: $name", Toast.LENGTH_SHORT).show()
        showSettings()
    }

    /**
     * Opens the device's system audio-effects panel (equalizer) for
     * the player's audio session. Some devices ship no panel at all;
     * that case gets an honest message, not a dead row.
     */
    private fun openEqualizer() {
        val sessionId = try {
            player?.audioSessionId
        } catch (_: Exception) {
            null
        } ?: 0
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No system equalizer available on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLicenses() {
        pushNav("licenses") { showLicenses() }
        generation++
        beginScreen("Licenses", showAvatar = false)
        fun block(title: String, body: String, first: Boolean) {
            val h = label(title, 17f, Color.WHITE, boldTf)
            val hLp = LinearLayout.LayoutParams(-1, -2)
            if (!first) hLp.topMargin = dp(24)
            h.layoutParams = hLp
            content.addView(h)
            content.addView(label(body, 14f, white55))
        }
        block(
            "NewPipe Extractor",
            "Copyright (C) 2015-2026 the NewPipe team and contributors.\n" +
                "Licensed under the GNU General Public License v3.0 (GPL-3.0). " +
                "This app uses it to resolve YouTube streams on-device; no part of it is a server.\n" +
                "Source: github.com/TeamNewPipe/NewPipeExtractor\n" +
                "Full license text: gnu.org/licenses/gpl-3.0",
            first = true
        )
        block(
            "Rhino (Mozilla)",
            "JavaScript engine bundled with NewPipe Extractor.\n" +
                "Licensed under the Mozilla Public License 2.0 (MPL-2.0).\n" +
                "Source: github.com/mozilla/rhino",
            first = false
        )
        block(
            "Poppins (typeface)",
            "Copyright 2020 The Poppins Project Authors (github.com/itfoundry/Poppins).\\n" +
                "Licensed under the SIL Open Font License, Version 1.1. Poppins is the app's " +
                "bundled typeface (Regular / Medium / SemiBold / Bold), fetched from the " +
                "google/fonts repository.\\n" +
                "License: scripts.sil.org/OFL",
            first = false
        )
        block(
            "Trademarks",
            "YouTube and YouTube Music are trademarks of Google LLC. " +
                "Audius is a trademark of Audius, Inc. arzxsh is not affiliated with or endorsed by either.",
            first = false
        )
    }

    /**
     * Full settings surface, grouped like the reference apps
     * (Playback / Audio / Downloads & storage / Appearance / About).
     * Every row works; rows whose behavior cannot be implemented for
     * real (volume normalization — no per-track loudness metadata
     * exists from either source) are omitted, not faked.
     */
    private fun showSettings() {
        navRoot("tab:settings") { showSettings() }
        generation++
        beginScreen("Settings", showAvatar = false)

        content.addView(settingsGroupHeader("Playback"))
        content.addView(
            settingsRow(
                "Music source",
                sourceModeName(sourceMode()) + " · search, home and playback follow this",
                iconRes = R.drawable.ic_radio
            ) {
                showMenu(
                    "Music source",
                    listOf(
                        "YouTube Music" to { setSourceMode("youtube"); showSettings() },
                        "Audius" to { setSourceMode("audius"); showSettings() },
                        "Both" to { setSourceMode("both"); showSettings() }
                    )
                )
            }
        )
        val qualityName = when (streamQuality()) {
            "data" -> "Data saver"
            "high" -> "High"
            else -> "Normal"
        }
        val qualitySub = if (dataSaverOn()) {
            "Data saver mode is on — the lowest stream quality is used"
        } else {
            "$qualityName · steers YouTube stream selection"
        }
        content.addView(settingsRow("Streaming quality", qualitySub, iconRes = R.drawable.ic_signal) {
            showMenu(
                "Streaming quality", listOf(
                    "Data saver" to { setStreamQuality("data") },
                    "Normal" to { setStreamQuality("normal") },
                    "High" to { setStreamQuality("high") }
                )
            )
        })
        content.addView(setting("Data saver", false) { showSettings() })
        content.addView(setting("Autoplay", true))
        val cfSec = crossfadeSec()
        content.addView(
            settingsRow(
                "Crossfade",
                if (cfSec == 0) "Off · tracks hard-cut, as before" else "$cfSec seconds between tracks",
                iconRes = R.drawable.ic_crossfade
            ) { showCrossfadeSheet() }
        )
        content.addView(setting("Gapless playback", true))
        content.addView(
            label(
                if (cfSec > 0) {
                    "Crossfade is on and handles transitions — gapless applies when crossfade is 0"
                } else {
                    "The next song loads before this one ends, so tracks flow with no gap"
                },
                12.5f, white50
            ).apply { setPadding(dp(38), 0, 0, dp(10)) }
        )
        content.addView(
            settingsRow(
                "Lyrics sync offset",
                "${fmtOffset(LyricOffsets.globalMs(this))} · default for tracks without their own offset",
                iconRes = R.drawable.ic_lyrics
            ) { showGlobalOffsetSheet() }
        )

        content.addView(settingsGroupHeader("Audio"))
        val eqSub = if (!eqController.enabled) {
            "Off"
        } else {
            "Built-in · ${eqController.presetName} preset"
        }
        content.addView(settingsRow("Equalizer", eqSub, iconRes = R.drawable.ic_equalizer) {
            showEqualizerSheet()
        })

        content.addView(settingsGroupHeader("Downloads & storage"))
        val dlCount = Downloads.entries(this).size
        val dlBytes = Downloads.storageBytes(this)
        content.addView(settingsRow("Downloads", "$dlCount tracks · ${fmtBytes(dlBytes)} on this device", iconRes = R.drawable.ic_download) {
            setActiveTab("Downloads")
            showDownloads()
        })
        if (dlCount > 0) {
            content.addView(settingsRow("Remove all downloads", "Frees ${fmtBytes(dlBytes)}", errorColor, iconRes = R.drawable.ic_trash) {
                showMenu(
                    "Remove all downloaded tracks?", listOf(
                        "Remove all" to {
                            Downloads.removeAll(this@MainActivity)
                            retainedRoots.remove("Downloads")
                            Toast.makeText(this@MainActivity, "Downloads removed", Toast.LENGTH_SHORT).show()
                            showSettings()
                        },
                        "Cancel" to {}
                    )
                )
            })
        }
        content.addView(settingsRow("Artwork cache", "${fmtBytes(ArtworkLoader.cacheBytes())} in memory", iconRes = R.drawable.ic_image) {
            ArtworkLoader.clearCache()
            Toast.makeText(this@MainActivity, "Artwork cache cleared", Toast.LENGTH_SHORT).show()
            showSettings()
        })
        content.addView(settingsRow("Lyrics cache", "${fmtBytes(Lyrics.cacheBytes(this))} on this device", iconRes = R.drawable.ic_storage) {
            Lyrics.clearCache(this@MainActivity)
            Toast.makeText(this@MainActivity, "Lyrics cache cleared", Toast.LENGTH_SHORT).show()
            showSettings()
        })

        content.addView(settingsGroupHeader("Appearance & behavior"))
        content.addView(setting("Glass effects", true) { applyGlassChange() })
        content.addView(settingsRow("Theme color", themeName(), iconRes = R.drawable.ic_theme, iconTint = accent) { showThemeSheet() })
        content.addView(setting("Ambient color from artwork", true) { applyAmbient(false) })
        content.addView(setting("Visualizer", true))
        content.addView(
            label("Bars move with playback — no microphone, nothing is captured", 12.5f, white50).apply {
                setPadding(dp(38), 0, 0, dp(10))
            }
        )
        content.addView(setting("Compact mini-player", false))
        content.addView(setting("Reduce motion", false))

        content.addView(settingsGroupHeader("About"))
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            setPadding(0, dp(2), 0, dp(16))
        })
        // Read from the build, never hardcoded (v4 shipped a stale
        // literal "Version 0.2.0" long after the app had moved on).
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        } ?: "?"
        // About lines get real line gaps (0.6.4, WS-3): they were
        // stacked at the label helper's bare 2dp padding and read
        // as one tight block.
        content.addView(label("Version $ver", 15.5f, Color.WHITE))
        for (line in listOf(
            "arzxsh by Arafat Arzu",
            "Sources · YouTube Music · Audius · On-device files",
            "Lyrics · LRCLIB / lyrics.ovh when available",
            "No DRM bypass, ever."
        )) {
            val lv = label(line, 15.5f, white50)
            val lvLp = LinearLayout.LayoutParams(-1, -2)
            lvLp.topMargin = dp(5)
            content.addView(lv, lvLp)
        }
        content.addView(settingsRow("Open-source licenses", null, iconRes = R.drawable.ic_doc) { showLicenses() })
        content.addView(
            settingsRow(
                "Check for updates",
                "You're on version $ver · new builds land on GitHub",
                iconRes = R.drawable.ic_download
            ) { checkForUpdates(manual = true) }
        )
    }

    // ------------------------------------------------------------ in-app updates (0.6.3, WS-E)

    /**
     * The update check. Manual (the Settings row) ALWAYS reports
     * back — latest, update found, or couldn't check. Automatic
     * (once from onCreate) is throttled to once a day and stays
     * silent unless an update exists; the prompt for a given
     * version is shown at most once ever, so there is no nag
     * loop. The fetch runs on the shared executor: if this
     * instance dies mid-check the result is dropped by the
     * isFinishing/isDestroyed guard.
     */
    private fun checkForUpdates(manual: Boolean) {
        val prefs = getPreferences(MODE_PRIVATE)
        if (!manual) {
            val last = prefs.getLong("update_last_check_ms", 0L)
            if (System.currentTimeMillis() - last < 24L * 3600L * 1000L) return
        }
        prefs.edit().putLong("update_last_check_ms", System.currentTimeMillis()).apply()
        if (manual) {
            Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        }
        executor.execute {
            var rel: UpdateChecker.Release? = null
            var failDetail: String? = null
            try {
                rel = UpdateChecker.fetchLatest(this)
            } catch (e: UpdateChecker.UpdateCheckException) {
                failDetail = e.detail
            } catch (e: Exception) {
                failDetail = e.javaClass.simpleName
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    rel == null -> if (manual) {
                        showUpdateCheckFailed(failDetail)
                    }
                    rel.versionCode > UpdateChecker.installedVersionCode(this) ->
                        showUpdatePrompt(rel, fromAuto = !manual)
                    else -> if (manual) {
                        Toast.makeText(
                            this,
                            "You're on the latest version (${UpdateChecker.installedVersionName(this)})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * Manual-check failure (0.6.4, WS-1): never a bare "check
     * your connection" — the dialog carries the real reason
     * (HTTP status / GitHub's message / exception class) as
     * small print, plus a way out to the releases page.
     */
    private fun showUpdateCheckFailed(detail: String?) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Couldn't check for updates")
            .setMessage(
                "The check didn't reach GitHub.\n\nDetails: ${detail ?: "unknown error"}"
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Releases page") { _, _ ->
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE_URL))
                    )
                } catch (_: Exception) {
                    // No browser either: the Settings row can be retried.
                }
            }
            .show()
    }

    private fun showUpdatePrompt(rel: UpdateChecker.Release, fromAuto: Boolean) {
        if (fromAuto) {
            // Auto-prompts: at most once per version, ever.
            val prefs = getPreferences(MODE_PRIVATE)
            if (prefs.getInt("update_prompted_code", 0) >= rel.versionCode) return
            prefs.edit().putInt("update_prompted_code", rel.versionCode).apply()
        }
        val notes = rel.notes.trim().let {
            if (it.length > 600) it.substring(0, 600) + "…" else it
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Update available — ${rel.versionName}")
            .setMessage(if (notes.isEmpty()) "A new version of arzxsh is ready to install." else notes)
            .setPositiveButton("Update") { _, _ -> startUpdateDownload(rel) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdateDownload(rel: UpdateChecker.Release) {
        // Already downloaded (e.g. returning from the
        // unknown-sources grant): straight to the installer.
        UpdateChecker.downloadedApk(this, rel)?.let { file ->
            promptInstall(file, rel)
            return
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Downloading ${rel.versionName}…")
            .setView(bar)
            .setNegativeButton("Later", null)
            .create()
        dialog.show()
        // The download itself lives on UpdateChecker's
        // process-lifetime executor (the WS-D survival rule):
        // dismissing this dialog — or this activity dying —
        // cannot interrupt it; the finished APK stays cached
        // and the next Update tap goes straight to install.
        UpdateChecker.downloadApk(
            this, rel,
            onProgress = { pct ->
                runOnUiThread { if (!isFinishing && !isDestroyed) bar.progress = pct }
            },
            onDone = { file, errDetail ->
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (file != null) {
                        promptInstall(file, rel)
                    } else {
                        Toast.makeText(
                            this,
                            "The update download failed" +
                                (errDetail?.let { " ($it)" } ?: "") +
                                ". Opening the release page instead.",
                            Toast.LENGTH_LONG
                        ).show()
                        openReleasePage(rel)
                    }
                }
            }
        )
    }

    /**
     * Hands the downloaded APK to the system installer. On
     * Android 8+ the per-app unknown-sources grant comes first:
     * if arzxsh may not install yet, the user is sent to that
     * one system toggle (their tap, never ours); the APK stays
     * downloaded, so Update → install is immediate afterwards.
     * Any failure falls back to the release page.
     */
    private fun promptInstall(file: java.io.File, rel: UpdateChecker.Release) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "One system step: allow installs from arzxsh, then tap Update again.",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                openReleasePage(rel)
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            openReleasePage(rel)
        }
    }

    private fun openReleasePage(rel: UpdateChecker.Release) {
        if (rel.pageUrl.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.pageUrl)))
        } catch (_: Exception) {
            // No browser either: the Settings row can be retried.
        }
    }

    private fun showEmpty(title: String, heading: String, message: String) {
        generation++
        beginScreen(title, showAvatar = false)
        content.addView(sectionTitle(heading))
        content.addView(label(message, 15f, white50))
    }

    // ---------------------------------------------------------------- rows

    /**
     * Search "Top result" hero card (0.6.1, the YouTube Music /
     * Spotify pattern): the songs bucket's first hit, large, at the
     * very top of the results. Tapping plays it inside the search
     * queue context (same queue the Songs rows use); the ⋯ keeps
     * the full track menu. The song ALSO stays in the Songs list
     * below, like the reference apps — the hero is a shortcut, not
     * a move. Per source mode the bucket differs, and so does the
     * hero: Audius mode's hero is the first Audius song; Both mode
     * merges YouTube-first, so the hero is the YouTube hit when one
     * exists; YouTube mode with YouTube down heroes the Audius
     * fallback song, matching the list under it.
     */
    private fun topResultCard(track: Track, queueContext: List<Track>, q: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = solidBg(14f, surface2)
            setPadding(dp(12), dp(12), dp(6), dp(12))
            isClickable = true
            setOnClickListener {
                setQueue(queueContext)
                setPlayContext("Search", q)
                play(track)
            }
            setOnLongClickListener {
                showTrackMenu(track)
                true
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 10)
        }
        ArtworkLoader.load(
            track.artworkLarge ?: track.artworkSmall, img,
            track.artworkSmall, dp(76)
        )
        card.addView(img, LinearLayout.LayoutParams(dp(76), dp(76)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(6), 0)
        }
        text.addView(label(track.title, 18f, Color.WHITE, boldTf).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val sub = if (track.duration > 0) {
            "${track.artist} · ${fmt(track.duration * 1000)}"
        } else {
            track.artist
        }
        text.addView(label(sub, 14.5f, white50).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val moreIcon = icon(R.drawable.ic_more, Color.argb(115, 255, 255, 255)).apply {
            setOnClickListener { showTrackMenu(track) }
        }
        card.addView(moreIcon, LinearLayout.LayoutParams(dp(30), dp(30)))
        return card
    }

    private fun addRows(
        list: List<Track>,
        target: LinearLayout = content,
        withHairlines: Boolean,
        queueContext: List<Track>? = null,
        contextLabel: Pair<String, String>? = null
    ) {
        // One downloaded-keys snapshot per list build (0.6.3,
        // audit S6): rows test set membership instead of running
        // a linear index scan + File.exists() per row on the
        // main thread (a 200-row playlist paid 200 stats).
        val dlKeys = Downloads.downloadedKeys(this)
        list.forEachIndexed { i, track ->
            if (withHairlines && i > 0) target.addView(hairline())
            addTrackRow(track, target, queueContext, contextLabel = contextLabel, dlKeys = dlKeys)
        }
    }

    /** In-flight chunked builds (0.6.3): while > 0, tab roots are
     *  NOT settled — a half-built list must never be snapshotted
     *  into the retention cache (saveCurrentRoot checks this). */
    private var chunkBuildsInFlight = 0

    /**
     * addRows spread across frames (0.6.3, audit S1): the shell
     * lists live inside the app's ONE big ScrollView, where a
     * RecyclerView expanded to full height would bind every row
     * anyway (it has no viewport of its own — no recycling win;
     * the queue overlay, which DOES own its viewport, got the
     * real RecyclerView). The freeze being fixed here is the
     * single synchronous 200-row build on tab switch, so the
     * rows build ~20 per frame instead: the screen paints
     * immediately and fills in over the next few frames.
     * [alive] is the caller's staleness token (screen generation,
     * search epoch): navigate away and the remaining chunks die
     * instead of appending into another screen's tree. [onDone]
     * runs on the UI thread when the last chunk lands (or at
     * once for an empty list) — trailing sections that must
     * stay BELOW the rows go there.
     */
    private fun addRowsChunked(
        list: List<Track>,
        target: LinearLayout,
        withHairlines: Boolean = true,
        queueContext: List<Track>? = null,
        contextLabel: Pair<String, String>? = null,
        menuOverrideFor: ((Int) -> (() -> Unit))? = null,
        alive: () -> Boolean,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        if (list.isEmpty()) {
            onDone?.invoke(true)
            return
        }
        val dlKeys = Downloads.downloadedKeys(this)
        chunkBuildsInFlight++
        var idx = 0
        fun step() {
            if (!alive()) {
                chunkBuildsInFlight--
                onDone?.invoke(false)
                return
            }
            val end = minOf(idx + 20, list.size)
            while (idx < end) {
                val track = list[idx]
                if (withHairlines && idx > 0) target.addView(hairline())
                addTrackRow(
                    track, target, queueContext,
                    contextLabel = contextLabel,
                    menuOverride = menuOverrideFor?.invoke(idx),
                    dlKeys = dlKeys
                )
                idx++
            }
            if (idx < list.size) {
                handler.post { step() }
            } else {
                chunkBuildsInFlight--
                onDone?.invoke(true)
            }
        }
        step()
    }

    private fun hairline(): View {
        val v = View(this).apply { setBackgroundColor(Color.argb(23, 255, 255, 255)) }
        val lp = LinearLayout.LayoutParams(-1, 1)
        lp.marginStart = dp(65)
        v.layoutParams = lp
        return v
    }

    private fun addTrackRow(
        track: Track,
        target: LinearLayout,
        queueContext: List<Track>? = null,
        contextLabel: Pair<String, String>? = null,
        menuOverride: (() -> Unit)? = null,
        dlKeys: Set<String>? = null
    ) {
        val openMenu = { if (menuOverride != null) menuOverride() else showTrackMenu(track) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            rippleRect()
            setOnClickListener {
                if (queueContext != null) setQueue(queueContext)
                if (contextLabel != null) setPlayContext(contextLabel.first, contextLabel.second)
                play(track)
            }
            setOnLongClickListener {
                openMenu()
                true
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 9)
        }
        ArtworkLoader.load(track.artworkSmall ?: track.artworkLarge, img, null, dp(52))
        row.addView(img, LinearLayout.LayoutParams(dp(52), dp(52)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        text.addView(label(track.title, 16f, Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val artistLabel = label(track.artist, 14.5f, white50).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        if (track.artistId != null) {
            artistLabel.setOnClickListener {
                showArtist(track.artistId, track.artist, track.artworkLarge)
            }
        }
        text.addView(artistLabel)
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        // No source badges in rows (product decision, 2026-11-06):
        // Track.source is still tracked internally for playback
        // resolution, radio and downloads, just never displayed.
        // Downloaded badge: set membership when the list builder
        // snapshotted the keys (S6), single stat-check otherwise.
        val isDown = if (dlKeys != null) {
            Downloads.key(track) in dlKeys
        } else {
            Downloads.isDownloaded(this, track)
        }
        if (isDown) {
            val doneIcon = icon(R.drawable.ic_downloaded, accent)
            val dLp = LinearLayout.LayoutParams(dp(19), dp(19))
            dLp.marginStart = dp(8)
            row.addView(doneIcon, dLp)
        }
        val isCurrent = track.id == current?.id && track.source == current?.source
        if (isCurrent && isPlayingSafe()) {
            // Playing indicator only while actually playing — the same
            // single source of truth as the mini player.
            row.addView(visualizer())
        } else if (Downloads.isActive(track)) {
            // In-flight download: a spinner stands in for the ⋯ menu
            // (the menu stays reachable by long-press). Static at
            // render time; the Downloads tab carries live progress.
            val spin = ProgressBar(this).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(accent)
            }
            row.addView(spin, LinearLayout.LayoutParams(dp(22), dp(22)))
        } else {
            val moreIcon = icon(R.drawable.ic_more, Color.argb(115, 255, 255, 255)).apply {
                setOnClickListener { openMenu() }
            }
            row.addView(moreIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        target.addView(row)
    }

    // ---------------------------------------------------------------- youtube music screens

    /** Compact "ClassName: message" for on-screen diagnostics. */
    private fun shortErr(t: Throwable): String {
        val msg = t.message?.replace('\n', ' ')?.trim().orEmpty()
        val cls = t.javaClass.simpleName
        return if (msg.isEmpty()) cls else "$cls: ${msg.take(140)}"
    }

    /** Visible failure line: a dead YouTube section says why, in the error color. */
    private fun errorLine(text: String): TextView {
        val tv = label(text, 13f, errorColor)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(14)
        tv.layoutParams = lp
        return tv
    }

    /**
     * Page-level load failure, human first (0.6.3, WS-D Bug C):
     * a plain-language headline that says what happened and what
     * to do, the raw error kept as the small secondary line
     * underneath (diagnostics stay available; they just stop
     * BEING the message — 0.6.2's artist page headline was the
     * raw "RuntimeException: Innertube browse HTTP 400"), and a
     * working Retry.
     */
    private fun addPageError(box: LinearLayout, what: String, err: String?, retry: () -> Unit) {
        val human = when {
            err.isNullOrEmpty() -> "Something went wrong loading this $what."
            err.contains("HTTP 400") || err.contains("HTTP 404") ->
                "YouTube rejected this $what link — it may be out of date. " +
                    "Go back and open it again from a fresh search, or retry."
            err.contains("HTTP") ->
                "YouTube's server answered with an error. Try again in a moment."
            err.contains("UnknownHost") || err.contains("Socket") ||
                err.contains("Connect") || err.contains("IO") ->
                "Couldn't reach YouTube. Check your connection and try again."
            else -> "YouTube couldn't load this $what right now. Try again in a moment."
        }
        box.addView(label(human, 15f, white75))
        if (!err.isNullOrEmpty()) box.addView(errorLine(err))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.topMargin = dp(16)
        row.layoutParams = lp
        row.addView(pillButton("Retry", filled = true) { retry() })
        box.addView(row)
    }

    /** Small source-status line (counts, fallback notes). */
    private fun statusLine(text: String): TextView {
        val tv = label(text, 12.5f, white50)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(12)
        tv.layoutParams = lp
        return tv
    }

    /** Clips a view to a circle with the placeholder fill behind art. */
    private fun circularClip(v: View) {
        v.background = GradientDrawable().apply {
            setColor(placeholder)
            shape = GradientDrawable.OVAL
        }
        v.clipToOutline = true
    }

    private fun artistResultRow(artist: YtmArtistRef): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            rippleRect()
            setOnClickListener { showArtist(artist.id, artist.name, artist.artworkLarge) }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            circularClip(this)
        }
        ArtworkLoader.load(artist.artworkSmall ?: artist.artworkLarge, img, null, dp(46))
        row.addView(img, LinearLayout.LayoutParams(dp(46), dp(46)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        text.addView(label(artist.name, 16f, Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        text.addView(label(artist.meta ?: "Artist", 14f, white50).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun albumResultRow(album: YtmAlbumRef): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            rippleRect()
            setOnClickListener { showAlbum(album) }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 9)
        }
        ArtworkLoader.load(album.artworkSmall ?: album.artworkLarge, img, null, dp(52))
        row.addView(img, LinearLayout.LayoutParams(dp(52), dp(52)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        text.addView(label(album.title, 16f, Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val sub = listOf(album.artist, album.year ?: "")
            .filter { it.isNotEmpty() }.joinToString(" • ")
        text.addView(label(sub.ifEmpty { "Album" }, 14.5f, white50).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /** Horizontal card row for an artist's albums / singles. */
    private fun albumCardRow(items: List<YtmAlbumRef>): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(2))
        }
        items.forEach { album ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener { showAlbum(album) }
            }
            val boxLp = LinearLayout.LayoutParams(dp(132), -2)
            boxLp.marginEnd = dp(14)
            box.layoutParams = boxLp
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                roundedClip(this, 10)
            }
            ArtworkLoader.load(album.artworkLarge ?: album.artworkSmall, img)
            box.addView(img, LinearLayout.LayoutParams(dp(132), dp(132)))
            box.addView(label(album.title, 13.5f, Color.WHITE, mediumTf).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.topMargin = dp(7)
                layoutParams = lp
            })
            box.addView(label(album.year ?: "", 12.5f, white50))
            row.addView(box)
        }
        scroll.addView(row)
        return scroll
    }

    /** Horizontal circle row: chart artists / related artists. */
    private fun artistCircleRow(artists: List<YtmArtistRef>): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(2))
        }
        artists.forEach { artist ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { showArtist(artist.id, artist.name, artist.artworkLarge) }
            }
            val boxLp = LinearLayout.LayoutParams(dp(104), -2)
            boxLp.marginEnd = dp(12)
            box.layoutParams = boxLp
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                circularClip(this)
            }
            ArtworkLoader.load(artist.artworkLarge ?: artist.artworkSmall, img)
            box.addView(img, LinearLayout.LayoutParams(dp(92), dp(92)))
            box.addView(label(artist.name, 13f, Color.WHITE, mediumTf).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.topMargin = dp(7)
                layoutParams = lp
            })
            row.addView(box)
        }
        scroll.addView(row)
        return scroll
    }

    private fun showArtist(artistId: String, fallbackName: String? = null, fallbackArt: String? = null) {
        pushNav("artist:$artistId") { showArtist(artistId, fallbackName, fallbackArt) }
        saveCurrentRoot()
        searchEpoch++
        val gen = ++generation
        content.removeAllViews()
        addScreenBackArrow()
        // Skeleton mirroring the artist page: banner, then rows.
        content.addView(skelBlock(232, 16))
        skelRows(content, 5)
        executor.execute {
            var page: YtmArtistPage? = null
            var err: String? = null
            try {
                page = YtmApi.artistPage(artistId)
            } catch (t: Throwable) {
                err = shortErr(t)
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                clearSkeletons()
                content.removeAllViews()
                addScreenBackArrow()
                val p = page
                if (p == null) {
                    content.addView(label(fallbackName ?: "Artist", 30f, Color.WHITE, boldTf))
                    addPageError(content, "artist page", err) {
                        showArtist(artistId, fallbackName, fallbackArt)
                    }
                    return@runOnUiThread
                }
                val banner = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(20, 20, 23))
                        cornerRadius = dp(16).toFloat()
                    }
                    clipToOutline = true
                }
                val bannerLp = LinearLayout.LayoutParams(-1, dp(232))
                bannerLp.topMargin = dp(10)
                banner.layoutParams = bannerLp
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                ArtworkLoader.load(p.artworkLarge ?: fallbackArt ?: p.artworkSmall, img)
                banner.addView(img, FrameLayout.LayoutParams(-1, -1))
                val shade = View(this).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.TRANSPARENT, Color.argb(199, 0, 0, 0))
                    )
                }
                banner.addView(shade, FrameLayout.LayoutParams(-1, -1))
                val labels = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), 0, dp(16), dp(13))
                }
                labels.addView(label(p.name, 27f, Color.WHITE, boldTf).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                val subs = p.subscribers
                if (!subs.isNullOrEmpty()) {
                    labels.addView(
                        label(
                            if (subs.contains("subscriber", ignoreCase = true)) subs else "$subs subscribers",
                            13.5f, white75
                        )
                    )
                }
                banner.addView(labels, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                content.addView(banner)
                if (p.topSongs.isNotEmpty()) {
                    // Play / Shuffle pill pair with Top Songs (spec §11.1).
                    val actions = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                    }
                    val aLp = LinearLayout.LayoutParams(-1, -2)
                    aLp.topMargin = dp(18)
                    actions.layoutParams = aLp
                    val playPill = pillButton("Play", filled = true) {
                        setPlayContext("Artist", p.name)
                        setQueue(p.topSongs)
                        play(p.topSongs.first())
                    }
                    actions.addView(playPill, LinearLayout.LayoutParams(dp(120), dp(40)))
                    val shufflePill = pillButton("Shuffle", filled = false) {
                        shuffleOn = true
                        setPlayContext("Artist", p.name)
                        setQueue(p.topSongs)
                        refreshTransportStates()
                        play(p.topSongs[playOrder.firstOrNull() ?: 0])
                    }
                    val sLp = LinearLayout.LayoutParams(dp(120), dp(40))
                    sLp.marginStart = dp(12)
                    actions.addView(shufflePill, sLp)
                    content.addView(actions)
                    content.addView(sectionTitle("Top Songs"))
                    addRows(p.topSongs, withHairlines = true, queueContext = p.topSongs, contextLabel = "Artist" to p.name)
                }
                if (p.singles.isNotEmpty()) {
                    content.addView(sectionTitle("Singles & EPs"))
                    content.addView(albumCardRow(p.singles))
                }
                if (p.albums.isNotEmpty()) {
                    content.addView(sectionTitle("Albums"))
                    content.addView(albumCardRow(p.albums))
                }
                if (p.related.isNotEmpty()) {
                    content.addView(sectionTitle("Fans Might Also Like"))
                    content.addView(artistCircleRow(p.related))
                }
                if (p.topSongs.isEmpty() && p.singles.isEmpty() && p.albums.isEmpty() && p.related.isEmpty()) {
                    content.addView(label("Nothing here yet.", 15f, white50).apply {
                        setPadding(0, dp(18), 0, 0)
                    })
                }
            }
        }
    }

    private fun showAlbum(ref: YtmAlbumRef) {
        pushNav("album:${ref.id}") { showAlbum(ref) }
        saveCurrentRoot()
        searchEpoch++
        val gen = ++generation
        content.removeAllViews()
        addScreenBackArrow()
        // Skeleton mirroring the album page: header art, then rows.
        content.addView(skelBlock(150, 12))
        skelRows(content, 6)
        executor.execute {
            var page: YtmAlbumPage? = null
            var err: String? = null
            try {
                page = YtmApi.albumPage(ref)
            } catch (t: Throwable) {
                err = shortErr(t)
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                clearSkeletons()
                content.removeAllViews()
                addScreenBackArrow()
                val p = page
                if (p == null) {
                    content.addView(label(ref.title, 26f, Color.WHITE, boldTf))
                    addPageError(content, "album", err) { showAlbum(ref) }
                    return@runOnUiThread
                }
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val art = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    roundedClip(this, 12)
                    elevation = dp(18).toFloat()
                }
                ArtworkLoader.load(p.artworkLarge ?: ref.artworkSmall, art)
                val artLp = LinearLayout.LayoutParams(dp(200), dp(200))
                artLp.topMargin = dp(14)
                head.addView(art, artLp)
                head.addView(label(p.title, 20f, Color.WHITE, boldTf).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    lp.topMargin = dp(16)
                    layoutParams = lp
                })
                if (p.artist.isNotEmpty()) {
                    val artistTv = label(p.artist, 17f, accent, mediumTf).apply {
                        gravity = Gravity.CENTER
                    }
                    if (p.artistId != null) {
                        artistTv.setOnClickListener { showArtist(p.artistId, p.artist, null) }
                    }
                    head.addView(artistTv)
                }
                if (!p.year.isNullOrEmpty()) {
                    head.addView(label(p.year, 13f, white50).apply { gravity = Gravity.CENTER })
                }
                content.addView(head)
                if (p.tracks.isNotEmpty()) {
                    // Action row (spec §12.1): circular Shuffle | wide
                    // Play pill | circular Download, centered.
                    val actions = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                    }
                    val aLp = LinearLayout.LayoutParams(-1, -2)
                    aLp.topMargin = dp(18)
                    aLp.bottomMargin = dp(6)
                    actions.layoutParams = aLp
                    val shuffleBtn = icon(R.drawable.ic_shuffle, Color.WHITE).apply {
                        background = solidBg(999f, surface2)
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        setOnClickListener {
                            shuffleOn = true
                            setPlayContext("Album", p.title)
                            setQueue(p.tracks)
                            refreshTransportStates()
                            play(p.tracks[playOrder.firstOrNull() ?: 0])
                        }
                    }
                    actions.addView(shuffleBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
                    val playAll = pillButton("Play", filled = true) {
                        setPlayContext("Album", p.title)
                        setQueue(p.tracks)
                        play(p.tracks.first())
                    }
                    val playLp = LinearLayout.LayoutParams(dp(132), dp(40))
                    playLp.setMargins(dp(14), 0, dp(14), 0)
                    actions.addView(playAll, playLp)
                    val pageDlKeys = Downloads.downloadedKeys(this)
                    val allDown = p.tracks.all { Downloads.key(it) in pageDlKeys }
                    val dlBtn = icon(
                        if (allDown) R.drawable.ic_downloaded else R.drawable.ic_download,
                        if (allDown) accent else Color.WHITE
                    ).apply {
                        background = solidBg(999f, surface2)
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        setOnClickListener {
                            if (allDown) {
                                Toast.makeText(this@MainActivity, "Already downloaded", Toast.LENGTH_SHORT).show()
                            } else {
                                p.tracks.filter { Downloads.key(it) !in pageDlKeys }
                                    .forEach { startDownload(it) }
                            }
                        }
                    }
                    actions.addView(dlBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
                    content.addView(actions)
                    addRowsChunked(
                        p.tracks, content,
                        queueContext = p.tracks,
                        contextLabel = "Album" to p.title,
                        alive = { gen == generation }
                    )
                } else {
                    content.addView(label("No tracks listed for this album.", 15f, white50).apply {
                        setPadding(0, dp(18), 0, 0)
                    })
                }
            }
        }
    }

    // ------------------------------------------------- moods & remote playlists

    /**
     * Fills [box] with the Moods & Genres browse: grouped chips,
     * each opening that category's playlist list. Shared by the
     * Search screen and the Library's Moods tile. Skeleton chip
     * rows while the catalog answers — never a bare loading text.
     */
    private fun renderMoodsInto(box: LinearLayout, gen: Int, audiusHint: String) {
        box.removeAllViews()
        if (sourceMode() == "audius") {
            box.addView(label(audiusHint, 14.5f, white50))
            return
        }
        repeat(3) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            val a = skelBlock(42, 999)
            (a.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0; weight = 1f; marginEnd = dp(10)
            }
            val b = skelBlock(42, 999)
            (b.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0; weight = 1f
            }
            row.addView(a)
            row.addView(b)
            box.addView(row)
        }
        executor.execute {
            var cats: List<YtmMoodCategory>? = null
            var err: String? = null
            try {
                cats = YtmApi.moodCategories()
            } catch (t: Throwable) {
                err = shortErr(t)
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                box.removeAllViews()
                clearSkeletons()
                val list = cats
                if (list == null || list.isEmpty()) {
                    box.addView(errorLine("Moods unavailable: ${err ?: "empty response"}"))
                } else {
                    var lastGroup: String? = null
                    var chipRow: LinearLayout? = null
                    var inRow = 0
                    list.forEach { cat ->
                        if (cat.group != lastGroup) {
                            lastGroup = cat.group
                            chipRow = null
                            inRow = 0
                            if (cat.group.isNotEmpty()) {
                                box.addView(label(cat.group, 15f, Color.WHITE, semiboldTf).apply {
                                    val lp = LinearLayout.LayoutParams(-1, -2)
                                    lp.topMargin = dp(16)
                                    lp.bottomMargin = dp(8)
                                    layoutParams = lp
                                })
                            }
                        }
                        if (chipRow == null || inRow >= 2) {
                            chipRow = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(0, 0, 0, dp(10))
                            }
                            box.addView(chipRow)
                            inRow = 0
                        }
                        val chip = label(cat.name, 14f, Color.WHITE, mediumTf).apply {
                            gravity = Gravity.CENTER
                            background = solidBg(999f, surface2)
                            setPadding(dp(10), dp(10), dp(10), dp(10))
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            isClickable = true
                            setOnClickListener { showMoodPlaylists(cat) }
                        }
                        val cLp = LinearLayout.LayoutParams(0, -2, 1f)
                        if (inRow == 0) cLp.marginEnd = dp(10)
                        chipRow.addView(chip, cLp)
                        inRow++
                    }
                }
            }
        }
    }

    /** Playlists inside one mood/genre category (real browse via
     *  FEmusic_moods_and_genres_category, not a search). */
    private fun showMoodPlaylists(cat: YtmMoodCategory) {
        pushNav("mood:${cat.params}") { showMoodPlaylists(cat) }
        val gen = ++generation
        beginScreen(cat.name, showAvatar = false)
        val skelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(skelBox)
        skelRows(skelBox, 6)
        executor.execute {
            var list: List<YtmPlaylistRef>? = null
            var err: String? = null
            try {
                list = YtmApi.moodPlaylists(cat)
            } catch (t: Throwable) {
                err = shortErr(t)
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                clearSkeletons()
                content.removeView(skelBox)
                val l = list
                if (l == null) {
                    addPageError(content, "category page", err) { showMoodPlaylists(cat) }
                    return@runOnUiThread
                }
                l.forEachIndexed { i, ref ->
                    if (i > 0) content.addView(hairline())
                    content.addView(ytmPlaylistRow(ref))
                }
            }
        }
    }

    private fun ytmPlaylistRow(ref: YtmPlaylistRef): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            rippleRect()
            setOnClickListener { showYtmPlaylist(ref) }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 9)
        }
        ArtworkLoader.load(ref.artworkSmall ?: ref.artworkLarge, img)
        row.addView(img, LinearLayout.LayoutParams(dp(52), dp(52)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        text.addView(label(ref.title, 16f, Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (ref.subtitle.isNotEmpty()) {
            text.addView(label(ref.subtitle, 14.5f, white50).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /** A remote (YouTube Music) playlist page: artwork header,
     *  Play + Shuffle, full playable track list — same shape as the
     *  album page. */
    private fun showYtmPlaylist(ref: YtmPlaylistRef) {
        pushNav("ytplaylist:${ref.id}") { showYtmPlaylist(ref) }
        saveCurrentRoot()
        searchEpoch++
        val gen = ++generation
        visibleScreen = "YtmPlaylist"
        content.removeAllViews()
        addScreenBackArrow()
        // Skeleton mirroring the playlist page: header, then rows.
        content.addView(skelBlock(150, 12))
        skelRows(content, 6)
        executor.execute {
            var list: List<Track>? = null
            var err: String? = null
            try {
                list = YtmApi.browsePlaylistTracks(ref.id)
            } catch (t: Throwable) {
                err = shortErr(t)
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                clearSkeletons()
                content.removeAllViews()
                addScreenBackArrow()
                val tracks = list
                if (tracks == null) {
                    content.addView(label(ref.title, 26f, Color.WHITE, boldTf))
                    addPageError(content, "playlist", err) { showYtmPlaylist(ref) }
                    return@runOnUiThread
                }
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val art = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    roundedClip(this, 12)
                    elevation = dp(18).toFloat()
                }
                ArtworkLoader.load(ref.artworkLarge ?: ref.artworkSmall, art)
                val artLp = LinearLayout.LayoutParams(dp(200), dp(200))
                artLp.topMargin = dp(14)
                head.addView(art, artLp)
                head.addView(label(ref.title, 20f, Color.WHITE, boldTf).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    lp.topMargin = dp(16)
                    layoutParams = lp
                })
                if (ref.subtitle.isNotEmpty()) {
                    head.addView(label(ref.subtitle, 13.5f, white50).apply {
                        gravity = Gravity.CENTER
                    })
                }
                head.addView(label("${tracks.size} tracks", 13f, white50).apply {
                    gravity = Gravity.CENTER
                })
                content.addView(head)
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                val aLp = LinearLayout.LayoutParams(-1, -2)
                aLp.topMargin = dp(18)
                aLp.bottomMargin = dp(6)
                actions.layoutParams = aLp
                val shuffleBtn = icon(R.drawable.ic_shuffle, Color.WHITE).apply {
                    background = solidBg(999f, surface2)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnClickListener {
                        shuffleOn = true
                        setPlayContext("Playlist", ref.title)
                        setQueue(tracks)
                        refreshTransportStates()
                        play(tracks[playOrder.firstOrNull() ?: 0])
                    }
                }
                actions.addView(shuffleBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
                val playAll = pillButton("Play", filled = true) {
                    setPlayContext("Playlist", ref.title)
                    setQueue(tracks)
                    play(tracks.first())
                }
                val playLp = LinearLayout.LayoutParams(dp(132), dp(40))
                playLp.setMargins(dp(14), 0, dp(14), 0)
                actions.addView(playAll, playLp)
                content.addView(actions)
                addRowsChunked(
                    tracks, content,
                    queueContext = tracks,
                    contextLabel = "Playlist" to ref.title,
                    alive = { gen == generation }
                )
            }
        }
    }

    // ---------------------------------------------------------------- now playing

    private fun buildNowPlaying(root: FrameLayout) {
        nowPlaying = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(bgColor)
        }
        // 0.6.3 (audit S9): NO color filter and NO separate shade
        // view — the loader's blurred backdrop is pre-darkened
        // (see ArtworkLoader.blur), so this single layer is the
        // whole backdrop treatment.
        npBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        nowPlaying.addView(npBackdrop, FrameLayout.LayoutParams(-1, -1))
        // Ambient glow layer (v6 refinement): a radial wash of the
        // artwork's dominant color, crossfaded on track change.
        npAmbient = View(this).apply { visibility = View.GONE }
        nowPlaying.addView(npAmbient, FrameLayout.LayoutParams(-1, -1))

        // The player scrolls (v6 refinement): the Spotify top layout
        // stays intact, and Lyrics preview + Related Songs live
        // below the fold.
        // 0.6.1: the scroll view is drag-aware — a downward drag
        // from the header/artwork zone at scroll-top drags the
        // whole panel toward dismissal (NpScrollView owns the
        // gesture rules; these lambdas own the panel).
        npScroll = NpScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
        }
        val scroll = npScroll
        npScroll.zoneTest = { y ->
            ::npArtwork.isInitialized && y <= npArtwork.bottom.toFloat()
        }
        npScroll.onDrag = { dy -> onNpDrag(dy) }
        npScroll.onDragEnd = { dy, v -> onNpDragEnd(dy, v) }
        // The ambient wash steps aside while the panel scrolls
        // (0.6.3, audit S9): a full-screen radial layer under a
        // moving scroll is the NP frame budget's biggest tenant.
        // It restores ~200ms after the scroll settles, through
        // the same applyAmbient the track change uses.
        val ambientRestore = Runnable {
            if (::nowPlaying.isInitialized && nowPlaying.visibility == View.VISIBLE) {
                applyAmbient(animate = false)
            }
        }
        npScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (::npAmbient.isInitialized) npAmbient.visibility = View.GONE
            handler.removeCallbacks(ambientRestore)
            handler.postDelayed(ambientRestore, 200)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(30))
        }
        scroll.addView(inner, LinearLayout.LayoutParams(-1, -2))
        nowPlaying.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // ---- top bar: chevron · "Playing from {context}" · ⋮ ----
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 0.6.1: the chevron moved OUT of the scroll content into a
        // pinned button (it used to scroll away — Arz: "there is no
        // back button"); this slot is a spacer so the centered
        // context block keeps its exact geometry.
        topBar.addView(View(this), LinearLayout.LayoutParams(dp(40), dp(40)))
        val ctxBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        npContextSmall = label("Playing from", 11.5f, white55).apply {
            gravity = Gravity.CENTER
        }
        npContextBig = label("", 13.5f, Color.WHITE, semiboldTf).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        ctxBox.addView(npContextSmall)
        ctxBox.addView(npContextBig)
        topBar.addView(ctxBox, LinearLayout.LayoutParams(0, -2, 1f))
        val npMore = icon(R.drawable.ic_more, Color.WHITE).apply {
            contentDescription = "More options"
            setOnClickListener { showNpMenu() }
        }
        topBar.addView(npMore, LinearLayout.LayoutParams(dp(40), dp(40)))
        inner.addView(topBar)

        val artSize = resources.displayMetrics.widthPixels - dp(48)
        npArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 10)
            // 0.6.3 (audit S9): the real-time elevation shadow
            // (dp(24) — a full-screen blur pass under the hero on
            // every frame) is replaced by a static edge-light
            // ring drawn as the view's foreground: zero shadow
            // rendering, same "lifted card" read.
            foreground = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.argb(60, 255, 255, 255))
            }
        }
        val artLp = LinearLayout.LayoutParams(artSize, artSize)
        artLp.topMargin = dp(22)
        inner.addView(npArtwork, artLp)

        // Current synced lyric line, directly above the title
        // (Spotify inspo). Tap opens the full lyrics view. Hidden
        // until synced lyrics for this track actually exist.
        // 0.6.5: the line NEVER truncates (Arz's phone test: a long
        // line ellipsized mid-sentence) — no ellipsize, wraps to at
        // most 3 lines at wrap-content height. The NP content is a
        // scroll column, so the extra height is absorbed by the
        // scroll; artwork and controls keep their layout.
        npLyricLine = label("", 15.5f, Color.WHITE, semiboldTf).apply {
            maxLines = 3
            visibility = View.GONE
            isClickable = true
            setOnClickListener { showLyrics() }
        }
        val lyricLp = LinearLayout.LayoutParams(-1, -2)
        lyricLp.topMargin = dp(20)
        inner.addView(npLyricLine, lyricLp)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val metaLp = LinearLayout.LayoutParams(-1, -2)
        metaLp.topMargin = dp(8)
        meta.layoutParams = metaLp
        val metaText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        npTitle = label("", 21f, Color.WHITE, boldTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        npArtist = label("", 16.5f, white55).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            setOnClickListener {
                val t = current
                if (t?.artistId != null) {
                    hideNowPlaying()
                    showArtist(t.artistId, t.artist, t.artworkLarge)
                }
            }
        }
        metaText.addView(npTitle)
        metaText.addView(npArtist)
        meta.addView(metaText, LinearLayout.LayoutParams(0, -2, 1f))
        npHeart = icon(R.drawable.ic_heart_outline, white75).apply {
            contentDescription = "Favorite"
            setOnClickListener { toggleFavorite() }
        }
        meta.addView(npHeart, LinearLayout.LayoutParams(dp(38), dp(38)))
        val npAdd = icon(R.drawable.ic_add_circle, white75).apply {
            contentDescription = "Add to Playlist"
            setOnClickListener { current?.let { showAddToPlaylist(it) } }
        }
        meta.addView(npAdd, LinearLayout.LayoutParams(dp(38), dp(38)))
        inner.addView(meta)

        // Playback failure surface: when a track cannot play, the real
        // reason (resolver outcomes, player error codes) is printed
        // here and stays on screen, instead of only flashing a generic
        // toast (v4's Dai Dai screenshot: toast, 0:00, no diagnosis).
        npError = label("", 13f, errorColor).apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        inner.addView(npError, LinearLayout.LayoutParams(-1, -2))

        npSeek = styledSeek(R.drawable.seek_thumb).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        // Elapsed follows the thumb; the right-hand
                        // label is the TOTAL and never moves (inspo).
                        npElapsed.text = fmt(progress)
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seeking = true
                    // 0.6.9: the scrubber grows while dragging — a
                    // larger thumb drawable (18dp vs the resting
                    // 14dp, ~1.3x) plus a subtle spring swell of
                    // the bar itself; both spring/snap back on
                    // release. (A SeekBar thumb is a drawable, not
                    // a view, so the growth is a thumb swap eased
                    // by the bar's own scale spring.)
                    sb?.thumb = getDrawable(R.drawable.seek_thumb_big)
                    if (sb != null) {
                        Springs.animate(
                            sb, DynamicAnimation.SCALE_Y, 1.12f,
                            0.70f, 900f, instant = springsOff()
                        )
                    }
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seeking = false
                    sb?.thumb = getDrawable(R.drawable.seek_thumb)
                    if (sb != null) {
                        Springs.animate(
                            sb, DynamicAnimation.SCALE_Y, 1f,
                            0.70f, 900f, instant = springsOff()
                        )
                    }
                    player?.seekTo((sb?.progress ?: 0).toLong())
                    updateNpLyricLine()
                }
            })
        }
        val seekLp = LinearLayout.LayoutParams(-1, dp(26))
        seekLp.topMargin = dp(14)
        inner.addView(npSeek, seekLp)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        npElapsed = label("0:00", 12.5f, white55)
        npRemaining = label("0:00", 12.5f, white55)
        times.addView(npElapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(npRemaining)
        inner.addView(times)

        // v6.1 glass: the transport + bottom rows live in ONE zone
        // so the whole block carries a single glass fill over the
        // (genuinely blurred) backdrop when glass is on; with
        // glass off the zone is invisible and the v6 solid layout
        // is pixel-identical.
        val controlZone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (glassOn()) {
                background = npGlass(18f)
                setPadding(dp(10), dp(6), dp(10), dp(8))
            }
        }
        val zoneLp = LinearLayout.LayoutParams(-1, -2)
        zoneLp.topMargin = dp(16)
        controlZone.layoutParams = zoneLp

        // ---- controls: shuffle · prev · BIG white play · next · sleep ----
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        val controlsLp = LinearLayout.LayoutParams(-1, -2)
        controls.layoutParams = controlsLp
        // Spotify state treatment (spec §2.1/§3): active = accent
        // glyph + a dot beneath it; repeat-one works a "1" into the
        // glyph.
        npShuffleBtn = icon(R.drawable.ic_shuffle, white55).apply {
            contentDescription = "Shuffle"
            setOnClickListener { toggleShuffle() }
        }
        npShuffleDot = dotView()
        val shuffleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        shuffleBox.addView(npShuffleBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        val shDotLp = LinearLayout.LayoutParams(dp(4), dp(4))
        shDotLp.topMargin = dp(3)
        shuffleBox.addView(npShuffleDot, shDotLp)
        val prevBtn = icon(R.drawable.ic_skip_prev, Color.WHITE).apply {
            contentDescription = "Previous track"
            setOnClickListener { playPrevious() }
        }
        // The big white circular play/pause (inspo): white disc,
        // black glyph, buffering spinner overlaid while resolving.
        val playSlot = FrameLayout(this)
        npPlayButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                shape = GradientDrawable.OVAL
            }
            setPadding(dp(19), dp(19), dp(19), dp(19))
            isClickable = true
            isFocusable = true
            contentDescription = "Play"
            setOnClickListener { togglePlayback() }
        }
        playSlot.addView(npPlayButton, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        npBuffering = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(accent)
        }
        playSlot.addView(npBuffering, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        val nextBtn = icon(R.drawable.ic_skip_next, Color.WHITE).apply {
            contentDescription = "Next track"
            setOnClickListener { playNext() }
        }
        // Sleep timer takes the fifth slot (inspo); its glyph tints
        // accent while a timer is armed.
        npSleepBtn = icon(R.drawable.ic_sleep, white75).apply {
            contentDescription = "Sleep timer"
            setOnClickListener { showSleepMenu() }
        }
        controls.addView(shuffleBox, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(prevBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(playSlot, LinearLayout.LayoutParams(0, dp(64), 1.2f))
        controls.addView(nextBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(npSleepBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        controlZone.addView(controls)

        npSleepLabel = label("", 12.5f, white55).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val sleepLp = LinearLayout.LayoutParams(-1, -2)
        sleepLp.topMargin = dp(10)
        controlZone.addView(npSleepLabel, sleepLp)

        // ---- bottom row: lyrics · repeat · download · share · queue ----
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        val bottomLp = LinearLayout.LayoutParams(-1, -2)
        bottomLp.topMargin = dp(18)
        bottomRow.layoutParams = bottomLp
        val lyricsBtn = icon(R.drawable.ic_lyrics, white75).apply {
            contentDescription = "Lyrics"
            setOnClickListener { showLyrics() }
        }
        npRepeatBtn = icon(R.drawable.ic_repeat, white55).apply {
            contentDescription = "Repeat"
            setOnClickListener { toggleRepeat() }
        }
        npRepeatDot = dotView()
        val repeatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        repeatBox.addView(npRepeatBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
        val rpDotLp = LinearLayout.LayoutParams(dp(4), dp(4))
        rpDotLp.topMargin = dp(3)
        repeatBox.addView(npRepeatDot, rpDotLp)
        npDownloadBtn = icon(R.drawable.ic_download, white75).apply {
            contentDescription = "Download"
            setOnClickListener { downloadCurrent() }
        }
        npDownloadProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(accent)
        }
        // Download slot: arrow (idle) -> indeterminate ring (working) ->
        // accent check (done), the three states in one footprint.
        val downloadSlot = FrameLayout(this)
        downloadSlot.addView(npDownloadBtn, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        downloadSlot.addView(npDownloadProgress, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        val shareBtn = icon(R.drawable.ic_share, white75).apply {
            contentDescription = "Share"
            setOnClickListener { shareTrack(current) }
        }
        val queueBtn = icon(R.drawable.ic_queue, white75).apply {
            contentDescription = "Queue"
            setOnClickListener { showQueue() }
        }
        bottomRow.addView(lyricsBtn, LinearLayout.LayoutParams(0, dp(34), 1f))
        bottomRow.addView(repeatBox, LinearLayout.LayoutParams(0, dp(34), 1f))
        bottomRow.addView(downloadSlot, LinearLayout.LayoutParams(0, dp(34), 1f))
        bottomRow.addView(shareBtn, LinearLayout.LayoutParams(0, dp(34), 1f))
        bottomRow.addView(queueBtn, LinearLayout.LayoutParams(0, dp(34), 1f))
        controlZone.addView(bottomRow)
        inner.addView(controlZone)

        // 0.6.9: the shared press treatment on the whole transport
        // block — play/pause on the primary (stiffer) spring, every
        // other control on the secondary one. No call site grows
        // its own press animation after this.
        MotionPress.attach(npPlayButton, primary = true) { springsOff() }
        listOf(
            prevBtn, nextBtn, npShuffleBtn, npSleepBtn, npRepeatBtn,
            npHeart, npAdd, npDownloadBtn, lyricsBtn, shareBtn, queueBtn, npMore
        ).forEach { MotionPress.attach(it, primary = false) { springsOff() } }

        // ---- Lyrics preview card (inspo): current + next line ----
        npLyricsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Glass over the blurred artwork backdrop when glass is
            // on (0.6.1); the v6 solid card when it is off.
            background = if (glassOn()) npGlass(14f) else solidBg(14f, surface2)
            setPadding(dp(16), dp(12), dp(16), dp(14))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { showLyrics() }
        }
        val cardLp = LinearLayout.LayoutParams(-1, -2)
        cardLp.topMargin = dp(22)
        npLyricsCard.layoutParams = cardLp
        npLyricsCard.addView(label("LYRICS", 11f, white55, semiboldTf).apply {
            letterSpacing = 0.12f
        })
        // 0.6.5: same no-truncation rule as the above-title line —
        // lyric text wraps fully (up to 3 lines), never ellipsizes.
        npLyricsCardCur = label("", 16.5f, Color.WHITE, semiboldTf).apply {
            maxLines = 3
            setPadding(0, dp(5), 0, 0)
        }
        npLyricsCardNext = label("", 14.5f, white50).apply {
            maxLines = 3
        }
        npLyricsCard.addView(npLyricsCardCur)
        npLyricsCard.addView(npLyricsCardNext)
        inner.addView(npLyricsCard)

        // Pinned chevron-down (0.6.1): a child of the PANEL, not of
        // the scroll content, so it sits at the same spot as the
        // old in-scroll chevron and never scrolls away. It
        // translates with the panel during drag-dismiss, like a
        // real sheet handle should.
        npCloseBtn = icon(R.drawable.ic_chevron_down, Color.WHITE).apply {
            contentDescription = "Close player"
            MotionPress.attach(this, primary = false) { springsOff() }
            background = GradientDrawable().apply {
                setColor(Color.argb(46, 0, 0, 0))
                shape = GradientDrawable.OVAL
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { hideNowPlaying() }
        }
        val closeLp = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START)
        closeLp.leftMargin = dp(24)
        closeLp.topMargin = dp(36)
        nowPlaying.addView(npCloseBtn, closeLp)

        root.addView(nowPlaying, FrameLayout.LayoutParams(-1, -1))
    }

    /** Drag-to-dismiss in progress (0.6.9): the drag writes the
     *  morph progress directly — the panel, the mini fade and the
     *  content fade all follow the finger from the one value. Any
     *  settle spring is cancelled first; the release re-springs. */
    private fun onNpDrag(dy: Float) {
        npMorphSpring?.cancel()
        val h = nowPlaying.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        npMorph.value = (1f - dy / h).coerceIn(0f, 1f)
        applyNpMorph(npMorph.value)
    }

    /** Drag-to-dismiss release decision (0.6.1): past ~25% of the
     *  panel height, or a downward fling, completes the hide;
     *  anything shorter settles back open. Called by NpScrollView
     *  after a stolen gesture. 0.6.9: both outcomes are the morph
     *  spring now, not fixed-duration slides. */
    private fun onNpDragEnd(dy: Float, velocityY: Float) {
        if (nowPlaying.visibility != View.VISIBLE) return
        val h = nowPlaying.height
        val threshold = if (h > 0) h * 0.25f else dp(160).toFloat()
        if (dy >= threshold || velocityY > 900f) {
            hideNowPlaying()
        } else {
            settleNpMorph(1f)
        }
    }

    /** Renders one morph progress value (0.6.9). The panel rises
     *  from the mini player's end of the screen; the mini bar
     *  fades out across the FIRST half of the travel and the Now
     *  Playing content (scroll + close chevron) fades in across
     *  the SECOND half — one surface becoming the other, driven
     *  by the single value so spring, drag and instant paths can
     *  never disagree about where anything is. */
    private fun applyNpMorph(p: Float) {
        if (!::nowPlaying.isInitialized) return
        val h = nowPlaying.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        nowPlaying.translationY = (1f - p) * h
        if (::mini.isInitialized) {
            mini.alpha = 1f - (p / 0.5f).coerceIn(0f, 1f)
        }
        val contentAlpha = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
        if (::npScroll.isInitialized) npScroll.alpha = contentAlpha
        if (::npCloseBtn.isInitialized) npCloseBtn.alpha = contentAlpha
    }

    /** Settles the morph to [target] (1 = open, 0 = closed) on
     *  the container spring (dampingRatio 0.95 / stiffness 350 —
     *  heavy, near-critically damped, the approved reference
     *  feel). Motion off resolves instantly. A settle to 0 hides
     *  the panel only when the spring actually comes to rest at
     *  the closed end (a mid-close re-open retargets the same
     *  spring, so the end listener re-checks the live target). */
    private fun settleNpMorph(target: Float) {
        npMorphTarget = target
        if (springsOff()) {
            npMorphSpring?.cancel()
            npMorph.value = target
            applyNpMorph(target)
            if (target == 0f) finishNpClose()
            return
        }
        val spring = npMorphSpring ?: Springs.valueSpring(npMorph, target, 0.95f, 350f)
            .also { s ->
                s.addUpdateListener { _, value, _ -> applyNpMorph(value) }
                s.addEndListener { _, canceled, _, _ ->
                    if (!canceled && npMorphTarget == 0f) finishNpClose()
                }
                npMorphSpring = s
            }
        spring.spring?.let {
            it.finalPosition = target
            it.dampingRatio = 0.95f
            it.stiffness = 350f
        }
        if (!spring.isRunning) spring.start()
    }

    /** The closed end state: panel hidden and every alpha the
     *  morph touched restored, so the shell underneath is exactly
     *  as it was before the player opened. */
    private fun finishNpClose() {
        if (!::nowPlaying.isInitialized) return
        nowPlaying.visibility = View.GONE
        nowPlaying.translationY = 0f
        if (::mini.isInitialized) mini.alpha = 1f
        if (::npScroll.isInitialized) npScroll.alpha = 1f
        if (::npCloseBtn.isInitialized) npCloseBtn.alpha = 1f
    }

    private fun styledSeek(thumbRes: Int): SeekBar = SeekBar(this).apply {
        progressDrawable = getDrawable(R.drawable.seek_progress)
        thumb = getDrawable(thumbRes)
        splitTrack = false
        setPadding(dp(7), 0, dp(7), 0)
    }

    private fun showNowPlaying() {
        ensureOverlays()
        val wasHidden = nowPlaying.visibility != View.VISIBLE
        nowPlaying.visibility = View.VISIBLE
        refreshPlaybackUi()
        if (wasHidden) {
            // A fresh open starts the morph at the mini end; a
            // re-show while a close is still settling continues
            // from the live progress instead of snapping.
            if (npMorphSpring?.isRunning != true) npMorph.value = 0f
            applyNpMorph(npMorph.value)
        }
        // 0.6.9: the morph spring replaces the 300ms slide-up;
        // settleNpMorph resolves instantly when motion is off.
        settleNpMorph(1f)
    }

    private fun hideNowPlaying() {
        if (!::nowPlaying.isInitialized) return
        // 0.6.9: the morph spring replaces the 230ms slide-down;
        // finishNpClose hides the panel at the spring's rest.
        settleNpMorph(0f)
    }

    override fun onBackPressed() {
        when {
            ::menuOverlay.isInitialized && menuOverlay.visibility == View.VISIBLE -> hideMenu()
            ::queueOverlay.isInitialized && queueOverlay.visibility == View.VISIBLE -> hideQueue()
            ::nowPlaying.isInitialized && nowPlaying.visibility == View.VISIBLE -> hideNowPlaying()
            // Content screens pop exactly one level (0.6.1
            // back-stack); at a tab root goBack() returns false
            // and the system behavior takes over, as before.
            goBack() -> Unit
            else -> super.onBackPressed()
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.durationMs
        if (dur <= 0) return
        val pos = p.positionMs.coerceIn(0L, dur)
        // The mini player's thin progress line tracks playback even
        // while Now Playing is closed (it lives in the shell).
        val mini = (pos * 1000 / dur).toInt()
        if (mini != lastMiniProgress && ::miniProgress.isInitialized && !buffering) {
            lastMiniProgress = mini
            miniProgress.progress = mini
        }
        if (!::nowPlaying.isInitialized || nowPlaying.visibility != View.VISIBLE || seeking) return
        updateSleepLabel()
        // Jank pass: the 500ms ticker touches ONLY the seek bar and
        // the two time labels, and only when their rendered values
        // actually changed — no layout passes, no row re-binding.
        val durInt = dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (npSeek.max != durInt) npSeek.max = durInt
        val posInt = pos.toInt()
        if (npSeek.progress != posInt) npSeek.progress = posInt
        val elapsed = fmt(posInt)
        if (elapsed != lastNpElapsed) {
            lastNpElapsed = elapsed
            npElapsed.text = elapsed
        }
        // Right-hand label is the TOTAL duration (Spotify inspo),
        // not a countdown.
        val total = fmt(durInt)
        if (total != lastNpRemaining) {
            lastNpRemaining = total
            npRemaining.text = total
        }
        updateNpLyricLine()
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * Favorite state has two generations: the v5 Liked Songs store
     * (full tracks) and the v4.1 `fav_<id>` boolean. A track counts
     * as liked when EITHER says so; toggling writes both, so the
     * heart, the store and old state can never disagree.
     */
    private fun isFavorite(t: Track): Boolean =
        Favorites.contains(this, t) ||
            getPreferences(MODE_PRIVATE).getBoolean("fav_${t.id}", false)

    private fun toggleFavorite() {
        // The Library root shows the Liked count: its retained
        // tree is stale the moment a heart flips.
        retainedRoots.remove("Library")

        val t = current ?: return
        val prefs = getPreferences(MODE_PRIVATE)
        val now = !isFavorite(t)
        if (now) {
            Favorites.add(this, t)
        } else {
            Favorites.remove(this, t)
        }
        prefs.edit().putBoolean("fav_${t.id}", now).apply()
        updateHeart()
    }

    private fun updateHeart() {
        val t = current ?: return
        val fav = isFavorite(t)
        if (fav) {
            npHeart.setImageResource(R.drawable.ic_heart)
            npHeart.setColorFilter(accent)
        } else {
            npHeart.setImageResource(R.drawable.ic_heart_outline)
            npHeart.setColorFilter(white75)
        }
    }

    // ---------------------------------------------------------------- playback wiring (engine unchanged)

    private fun play(track: Track) {
        // An explicit new play is a fresh intent: any stop/pause
        // recorded against an EARLIER resolution (0.6.3, WS-C)
        // must not leak into this one and mute it.
        suppressAutoPlay = false
        // A foreground play supersedes any gapless prep for the old
        // track's boundary (manual skip, row tap, error advance):
        // the prepared next is stale the moment this runs.
        discardGaplessPrep()
        val idx = queue.indexOfFirst { it.id == track.id && it.source == track.source }
        if (idx >= 0) {
            queueIndex = idx
            if (playOrder.isEmpty()) rebuildOrder()
        } else {
            setQueue(listOf(track), 0)
        }
        current = track
        saveRecent(track)
        // Stats: every play() is a play event (replays included);
        // bank any accumulated listening time first.
        flushListened()
        PlayHistory.record(this, track)
        buffering = true
        updateBufferingUi()
        clearPlaybackError()
        refreshPlaybackUi()
        val token = ++playToken
        // The outgoing player must never fire completion/error into
        // the NEW track's session (it would advance the queue or
        // print a stale error). Retire it up front (every callback
        // checks the flag); what happens to the instance itself is
        // decided per path below. An outgoing player that is actually
        // audible becomes the crossfade source, so the fade can start
        // from the track the user is hearing even after a background
        // resolution — ladder retries and the Audius fallback
        // re-enter play() and must not lose it.
        player?.let { old ->
            old.retired = true
            if (old.isPlaying) fadeSource = old
        }
        // Local files play from their content URI, offline, always.
        if (track.source == SOURCE_LOCAL) {
            startPlaybackUri(android.net.Uri.parse(track.id))
            return
        }
        // A downloaded copy always wins: instant start, works offline.
        val local = Downloads.localFile(this, track)
        if (local != null) {
            startPlayback(local.absolutePath)
            return
        }
        if (track.source == SOURCE_YOUTUBE) {
            // The direct stream URL is resolved on-device by the
            // retry ladder (see beginYtResolution): the known-good
            // cached resolution first, then the Innertube contexts in
            // last-good-first order, then NewPipe — and a stream the
            // PLAYER rejects advances to the next context instead of
            // killing the track. v5 resolved exactly once and handed
            // the URL to a bare-UA MediaPlayer; one rejection
            // (what=-38) was final. Failures still PRINT every
            // stage's outcome on Now Playing — no generic silence.
            // With crossfade on, the old track keeps playing through
            // the resolution and is faded out when the new one starts
            // — releasing here would make every YouTube transition a
            // hard cut with a silent gap.
            if (crossfadeSec() <= 0) {
                releaseActivePlayer()
                refreshPlaybackUi()
            }
            beginYtResolution(track, token)
        } else {
            startPlayback("https://api.audius.co/v1/tracks/${track.id}/stream?app_name=arzxsh")
        }
    }

    /** Releases the active player (if any) and forgets it as a
     *  crossfade source when it was the source. */
    private fun releaseActivePlayer() {
        val p = player ?: return
        if (fadeSource === p) fadeSource = null
        // A torn-down player has no boundary: drop any gapless prep.
        discardGaplessPrep()
        p.release()
        player = null
    }

    // ------------------------------------------------------------ YouTube retry ladder (v5.1)

    /**
     * Starts stream resolution for a YouTube track as a staged
     * ladder. Stage order: the session's known-good cached
     * resolution when one exists, then the Innertube contexts in
     * last-good-first order, then NewPipe. Each stage either produces
     * a stream the player accepts, fails resolution (its report is
     * kept), or is REJECTED by the player — which also advances the
     * ladder (see onPlayerFailed). Only when every stage is spent do
     * the Audius fallback and the printed error happen, carrying
     * every stage's outcome. Resolution runs on [playerExecutor],
     * never queued behind a download.
     */
    private fun beginYtResolution(track: Track, token: Int) {
        ytTrack = track
        ytQuality = effectiveQuality()
        ytReports.clear()
        ytCached = StreamResolver.cachedGood(track.id, ytQuality)
        ytStageLabels = if (ytCached?.url != null) {
            listOf("CACHE") + StreamResolver.youtubeStageLabels()
        } else {
            StreamResolver.youtubeStageLabels()
        }
        ytStage = -1
        advanceYtLadder(token)
    }

    private fun advanceYtLadder(token: Int) {
        val track = ytTrack ?: return
        ytStage++
        if (ytStage >= ytStageLabels.size) {
            // Ladder spent: the Audius fallback (standing second
            // source, YouTube Music and Both modes) gets the full
            // report; in Audius-only mode the printed report stands.
            val report = ytReports.joinToString(" · ")
            if (sourceMode() != "audius") {
                tryAudiusFallback(track, report, token)
            } else {
                showPlaybackError("Couldn't play this track — $report")
                refreshPlaybackUi()
                Toast.makeText(this, "This track could not be played.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val label = ytStageLabels[ytStage]
        if (label == "CACHE") {
            val cached = ytCached
            val cachedUrl = cached?.url
            if (cached != null && cachedUrl != null) {
                startPlayback(cachedUrl, cached.userAgent, cached)
            } else {
                advanceYtLadder(token)
            }
            return
        }
        playerExecutor.execute {
            val resolved = StreamResolver.resolveYoutubeStage(track.id, ytQuality, label)
            runOnUiThread {
                if (token != playToken) return@runOnUiThread
                if (resolved.url == null) {
                    ytReports += resolved.report
                    advanceYtLadder(token)
                } else {
                    startPlayback(resolved.url, resolved.userAgent, resolved)
                }
            }
        }
    }

    /** Lowercase alphanumeric form for cross-source song matching. */
    private fun normalizeForMatch(s: String): String {
        var x = s.lowercase()
        x = x.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        x = x.replace(
            Regex("\\b(official|video|audio|lyrics|lyric|hd|mv|music|topic|vevo)\\b"),
            " "
        )
        x = x.replace(Regex("[^a-z0-9 ]"), " ")
        return x.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Conservative same-song test for the Audius fallback: only an
     * obvious match qualifies — an exact normalized title, or a
     * strongly containing title, in both cases backed by artist
     * evidence (in the candidate's artist field or its title). A loose
     * match is worse than an honest error, so near-misses lose.
     */
    private fun audiusMatchFor(yt: Track, candidates: List<Track>): Track? {
        val ytTitle = normalizeForMatch(yt.title)
        val ytArtist = normalizeForMatch(yt.artist)
        if (ytTitle.isEmpty()) return null
        var best: Track? = null
        var bestScore = -1
        for (c in candidates) {
            val ct = normalizeForMatch(c.title)
            if (ct.isEmpty()) continue
            val ratio = minOf(ct.length, ytTitle.length).toDouble() /
                maxOf(ct.length, ytTitle.length).coerceAtLeast(1)
            val titleScore = when {
                ct == ytTitle -> 3
                (ct.contains(ytTitle) || ytTitle.contains(ct)) && ratio >= 0.7 -> 2
                else -> 0
            }
            if (titleScore == 0) continue
            val ca = normalizeForMatch(c.artist)
            val artistHit = ytArtist.isNotEmpty() &&
                ((ca.isNotEmpty() && (ca.contains(ytArtist) || ytArtist.contains(ca))) ||
                    ct.contains(ytArtist))
            val plausible = artistHit || (titleScore == 3 && ytArtist.isEmpty())
            if (!plausible) continue
            val score = titleScore * 10 + if (artistHit) 5 else 0
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    /**
     * When a YouTube track fails BOTH stream resolvers, search Audius
     * for the same song; on a plausible match, swap it into the queue
     * slot and play it, telling the user plainly. No match → the
     * printed resolver report stands as the diagnosis.
     */
    private fun tryAudiusFallback(ytTrack: Track, report: String, token: Int) {
        playerExecutor.execute {
            var match: Track? = null
            try {
                val q = URLEncoder.encode(
                    listOf(ytTrack.title, ytTrack.artist).filter { it.isNotBlank() }.joinToString(" "),
                    "UTF-8"
                )
                val candidates = fetch("/tracks/search?query=$q&limit=10") ?: emptyList()
                match = audiusMatchFor(ytTrack, candidates)
            } catch (_: Throwable) {
                match = null
            }
            val found = match
            runOnUiThread {
                if (token != playToken) return@runOnUiThread
                if (found == null) {
                    showPlaybackError("Couldn't play this track — $report")
                    refreshPlaybackUi()
                    Toast.makeText(this@MainActivity, "This track could not be played.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val idx = queue.indexOfFirst { it.id == ytTrack.id && it.source == ytTrack.source }
                if (idx >= 0) {
                    val q2 = queue.toMutableList()
                    q2[idx] = found
                    queue = q2
                }
                pendingNpNotice = "YouTube failed — playing the Audius version."
                Toast.makeText(
                    this@MainActivity,
                    "YouTube failed — playing the Audius version.",
                    Toast.LENGTH_LONG
                ).show()
                play(found)
            }
        }
    }

    /** Prints a playback failure on Now Playing and keeps it there. */
    private fun showPlaybackError(text: String) {
        if (!::npError.isInitialized) return
        // Any printed outcome is terminal for the current resolve:
        // the buffering indicators stand down.
        if (buffering) {
            buffering = false
            updateBufferingUi()
        }
        npError.text = text
        npError.visibility = View.VISIBLE
    }

    private fun clearPlaybackError() {
        if (!::npError.isInitialized) return
        npError.text = ""
        npError.visibility = View.GONE
    }

    /** Crossfade length from Settings, in seconds (0 = off, the
     *  default — with 0 every transition behaves exactly as v4.1). */
    private fun crossfadeSec(): Int =
        getPreferences(MODE_PRIVATE).getInt("crossfade_seconds", 0).coerceIn(0, 12)

    /** Shared player setup for every source: [stream] is a direct
     *  audio URL or a downloaded file's path. [userAgent] is the UA of
     *  the client context that produced a YouTube URL (googlevideo
     *  binds URLs to it); [resolved] marks a YouTube ladder start,
     *  whose player errors advance the ladder instead of failing the
     *  track outright. */
    private fun startPlayback(
        stream: String,
        userAgent: String? = null,
        resolved: StreamResolver.Resolved? = null
    ) = startPlaybackInternal(stream, null, userAgent, resolved)

    /** Player setup for a content:// data source (local files). */
    private fun startPlaybackUri(uri: android.net.Uri) =
        startPlaybackInternal(null, uri, null, null)

    /**
     * Builds one ExoPlayer for the new stream and makes it [player].
     * Its HTTP data source sends [userAgent] — the actual v5.1
     * playback fix. v5's MediaPlayer could not set a UA at all, so
     * googlevideo saw Stagefright's default and rejected the
     * resolved URL (what=-38) even though resolution had succeeded.
     *
     * Crossfade keeps v5's semantics on the new engine: when
     * crossfade is on, the new track is a DIFFERENT track, and the
     * previous track is still audible ([fadeSource]), the new player
     * starts silent and [crossfadeTick] swaps the volumes over the
     * configured seconds; any failure falls back to the hard cut.
     * Repeat-one restarts never fade into themselves.
     */
    private fun startPlaybackInternal(
        stream: String?,
        uri: android.net.Uri?,
        userAgent: String?,
        resolved: StreamResolver.Resolved?
    ) {
        // 0.6.3 (WS-D Bug A): a resolution that outlived its
        // activity instance must never build a player here. This
        // was the zombie mint: teardown released the old player,
        // then the late completion ran in the DEAD instance,
        // built a fresh ExoPlayer and pressed play — audio with
        // no live UI owning it and no notification controlling
        // it (the service had already been stopped).
        if (!PlayerRegistry.isOwner(instanceId)) return
        bridgeMuted = false
        val old = player
        val forTrack = current
        val sessionToken = playToken
        val fadeMs = crossfadeSec() * 1000
        val fade = fadeSource
        val sameTrack = forTrack != null && activePlayerTrack != null &&
            forTrack.id == activePlayerTrack?.id && forTrack.source == activePlayerTrack?.source
        val canFade = fadeMs > 0 && fade != null && !sameTrack && fade.isPlaying && !sleepFadeRunning
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: "arzxsh (Android)")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
            )
            .build()
        val holder = EnginePlayer(exo)
        player = holder
        // 0.6.3: this player belongs to the process registry —
        // a future instance's claim or this instance's teardown
        // releases it even if every other path forgets it.
        PlayerRegistry.register(instanceId) { holder.release() }
        activePlayerTrack = forTrack
        // 0.6.8: a track starting inside the sleep fade window
        // starts ON the ramp, not at unity — the fade survives
        // the player swap (the tick keeps re-deriving it too).
        if (sleepFadeRunning) holder.setVolume(sleepFadeVolumeNow())
        // 0.6.1 (background playback): keep the network/CPU path
        // awake while streaming, so a screen-off phone does not
        // stall mid-song; the foreground service keeps the process.
        exo.setWakeMode(C.WAKE_MODE_NETWORK)
        // Every new player = a track starting: the media
        // notification/session service runs from here, and the
        // notification permission is asked at this first play.
        PlaybackService.start(this)
        requestNotifPermissionIfNeeded()
        exo.setAudioAttributes(
            Media3AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            false
        )
        val item = when {
            uri != null -> MediaItem.fromUri(uri)
            stream != null && stream.startsWith("/") ->
                MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(stream)))
            stream != null -> MediaItem.fromUri(stream)
            else -> null
        }
        if (item == null) {
            showPlaybackError("Player error: no stream to play")
            refreshPlaybackUi()
            return
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (holder.retired || player !== holder) return
                when (state) {
                    Player.STATE_BUFFERING -> {
                        buffering = true
                        updateBufferingUi()
                    }
                    Player.STATE_READY -> if (!holder.readyFired) {
                        holder.readyFired = true
                        onPlayerReady(holder, old, forTrack, canFade, fadeMs, resolved)
                    }
                    Player.STATE_ENDED -> handleCompletion()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (holder.retired || player !== holder) return
                onPlayerFailed(holder, forTrack, resolved, sessionToken, error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (holder.retired || player !== holder) return
                // ExoPlayer starts asynchronously (MediaPlayer's
                // start() looked instant), so the transport icons
                // follow the REAL playing state from here.
                refreshPlaybackUi()
            }
        }
        holder.listener = listener
        exo.addListener(listener)
        if (canFade) holder.setVolume(0f)
        try {
            exo.setMediaItem(item)
            exo.prepare()
            if (suppressAutoPlay) {
                // Stopped or paused mid-resolution (0.6.3, WS-C):
                // the start lands PREPARED AND SILENT — the
                // user's stop is never overridden by a late READY.
                suppressAutoPlay = false
            } else {
                exo.play()
            }
        } catch (e: Exception) {
            showPlaybackError("Player error: ${shortErr(e)}")
            refreshPlaybackUi()
        }
    }

    /** The new player reached READY: the v5 "prepared" moment. A
     *  YouTube stream that actually starts is remembered as
     *  known-good (replays skip resolution); the crossfade partner is
     *  consumed exactly once — faded out, or released for the hard
     *  cut — and any other superseded player is released. */
    private fun onPlayerReady(
        holder: EnginePlayer,
        old: EnginePlayer?,
        forTrack: Track?,
        canFade: Boolean,
        fadeMs: Int,
        resolved: StreamResolver.Resolved?
    ) {
        buffering = false
        updateBufferingUi()
        if (resolved != null && forTrack != null) {
            StreamResolver.rememberGood(forTrack.id, ytQuality, resolved)
        }
        val notice = pendingNpNotice
        if (notice != null) {
            pendingNpNotice = null
            showPlaybackError(notice)
        } else {
            clearPlaybackError()
        }
        val partner = fadeSource
        fadeSource = null
        if (old != null && old !== holder && old !== partner) {
            old.release()
        }
        var faded = false
        if (canFade && partner != null && partner !== holder) {
            try {
                beginCrossfade(partner, fadeMs)
                faded = true
            } catch (_: Exception) {
                faded = false
            }
        }
        if (!faded) {
            if (partner != null && partner !== holder) partner.release()
            // Unity — unless the sleep fade owns the volume
            // right now (0.6.8), in which case the new player
            // lands on the ramp instead of punching through it.
            holder.setVolume(if (sleepFadeRunning) sleepFadeVolumeNow() else 1f)
        }
        refreshPlaybackUi()
    }

    /**
     * The player rejected (or lost) its stream. For a YouTube ladder
     * start this is NOT the end: the attempt's outcome joins the
     * report, the session cache entry is evicted, and the ladder
     * advances to the next client context — the fix for v5's "one
     * resolution, one rejection, dead track". Any other source
     * prints the failure on Now Playing exactly like v5 did, now
     * with ExoPlayer's error code name instead of MediaPlayer codes.
     */
    private fun onPlayerFailed(
        holder: EnginePlayer,
        forTrack: Track?,
        resolved: StreamResolver.Resolved?,
        sessionToken: Int,
        error: PlaybackException
    ) {
        // Buffering is NOT cleared here: a ladder rejection retries
        // with another client (still resolving); terminal failures
        // clear it via showPlaybackError.
        if (player === holder) player = null
        holder.release()
        if (resolved != null && forTrack != null && sessionToken == playToken &&
            ytTrack?.id == forTrack.id && ytTrack?.source == forTrack.source
        ) {
            ytReports += "${resolved.clientLabel ?: "stream"}: player rejected (${error.errorCodeName})"
            StreamResolver.forgetGood(forTrack.id, ytQuality)
            advanceYtLadder(sessionToken)
            return
        }
        val src = when (forTrack?.source) {
            SOURCE_YOUTUBE -> "YouTube stream"
            SOURCE_AUDIUS -> "Audius stream"
            SOURCE_LOCAL -> "local file"
            else -> "stream"
        }
        showPlaybackError(
            "Player error playing $src (${error.errorCodeName}). The resolved URL was rejected."
        )
        refreshPlaybackUi()
        Toast.makeText(this, "This track could not be played.", Toast.LENGTH_SHORT).show()
    }

    private fun beginCrossfade(old: EnginePlayer, fadeMs: Int) {
        // Retire any previous outgoing player first.
        outPlayer?.let { prev ->
            if (prev !== old) prev.release()
        }
        handler.removeCallbacks(crossfadeTick)
        outPlayer = old
        fadeStep = 0
        fadeTotalSteps = (fadeMs / 100).coerceAtLeast(1)
        handler.postDelayed(crossfadeTick, 100)
        // The visualizer follows `player` (its position drives the
        // bars), which already points at the INCOMING player.
    }

    /** Ends a running crossfade immediately: outgoing player
     *  released, active player restored to full volume. */
    private fun finishCrossfadeNow() {
        handler.removeCallbacks(crossfadeTick)
        outPlayer?.release()
        outPlayer = null
        player?.setVolume(1f)
    }

    // ------------------------------------------------- v6.1: gapless playback

    private fun gaplessOn(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("setting_Gapless playback", true)

    /**
     * The definite next queue index per the play ORDER (shuffle
     * order, not raw queue order) — the same computation
     * handleCompletion advances by. Null when there is no definite
     * next: repeat-one, end of queue without repeat-all, or the
     * repeat-all wrap would land on the current track itself.
     */
    private fun expectedNextIndex(): Int? {
        if (repeatMode == 2) return null
        if (queue.isEmpty() || queueIndex !in queue.indices || playOrder.isEmpty()) return null
        val pos = currentOrderPos()
        if (pos < 0) return null
        return if (pos + 1 < playOrder.size) {
            playOrder[pos + 1]
        } else if (repeatMode == 1) {
            val first = playOrder[0]
            if (first == queueIndex) null else first
        } else {
            null
        }
    }

    /** Pair key ("<next>><afterNext>") whose after-next warm
     *  resolve already ran; one warm per pair, re-armed when the
     *  pair changes. */
    private var afterNextWarmedFor: String? = null

    /**
     * Warms the track AFTER next (0.6.1 refinement, Arz: "the next
     * one queue is already cached"): a bare stream resolution —
     * no player, no buffering — whose only product is a warm entry
     * in the resolver's session cache, so a double-skip or the
     * following transition starts at the ladder's CACHE stage
     * instead of a cold context walk. Exactly one level deep:
     * queue-wide caching would burn data and battery on
     * skip-heavy listening. The prepared NEXT player (which does
     * buffer its head via prepare()) is [prepareGapless]'s job.
     */
    private fun maybeWarmAfterNext() {
        if (!gaplessOn() || crossfadeSec() > 0) return
        if (current == null) return
        val nextIdx = expectedNextIndex() ?: return
        val pos = currentOrderPos()
        if (pos < 0 || playOrder.isEmpty()) return
        val size = playOrder.size
        val nextPos = if (pos + 1 < size) pos + 1 else 0
        var afterPos = nextPos + 1
        if (afterPos >= size) {
            if (repeatMode != 1) return
            afterPos = 0
        }
        val afterIdx = playOrder[afterPos]
        if (afterIdx == queueIndex || afterIdx == nextIdx) return
        val after = queue.getOrNull(afterIdx) ?: return
        // Only YouTube tracks resolve at all; a downloaded copy
        // plays instantly and needs no warm cache.
        if (after.source != SOURCE_YOUTUBE) return
        if (Downloads.localFile(this, after) != null) return
        val next = queue.getOrNull(nextIdx) ?: return
        val pairKey = Downloads.key(next) + ">" + Downloads.key(after)
        if (afterNextWarmedFor == pairKey) return
        afterNextWarmedFor = pairKey
        val quality = effectiveQuality()
        gaplessExecutor.execute {
            val resolved = try {
                StreamResolver.resolveYoutube(after.id, quality)
            } catch (e: Exception) {
                null
            }
            if (resolved?.url != null) {
                StreamResolver.rememberGood(after.id, quality, resolved)
            }
        }
    }

    /** Drops any prepared/armed next track. The prepared player is
     *  released (unless it already activated — activation clears
     *  [gaplessPrep] first, so this never kills the live player). */
    private fun discardGaplessPrep() {
        gaplessFire?.let { handler.removeCallbacks(it) }
        gaplessFire = null
        gaplessArmingFor = null
        val prep = gaplessPrep
        gaplessPrep = null
        if (prep != null && !prep.activated) prep.holder.release()
    }

    /**
     * Runs on the 500ms ticker. With ~30s left on a definite next
     * track (crossfade off, gapless on, no sleep-after-track), the
     * next stream is pre-resolved on the player executor (YouTube:
     * the one-shot resolver — session cache + context walk +
     * NewPipe, same machinery the foreground ladder walks stage by
     * stage) and a second player is built and PREPARED — ExoPlayer
     * buffers the stream's head, not merely resolves its URL: a
     * resolved-but-unprepared stream could still stall the seam.
     * When the
     * boundary comes within ~1.5s and the prep is READY, a one-shot
     * timer is armed for the exact remaining time.
     *
     * HONEST LIMIT: the swap is scheduled from the player's own
     * duration/position and fires on the main-thread handler, so
     * the seam lands within a handler tick of the true boundary
     * (typically single-digit ms, worst case a frame or two) — it
     * is NOT sample-accurate. If the prep is not ready in time, or
     * anything changed underneath, the normal end-of-track path
     * runs instead: the transition is never worse than today's cut.
     */
    private fun maybeArmGapless() {
        if (!gaplessOn() || crossfadeSec() > 0 || sleepAfterTrack || sleepFadeRunning) {
            if (gaplessPrep != null || gaplessArmingFor != null) discardGaplessPrep()
            return
        }
        val p = player ?: return
        if (current == null || !p.isPlaying) return
        val dur = p.durationMs
        if (dur <= 0) return
        val remaining = dur - p.positionMs
        val nextIdx = expectedNextIndex()
        if (nextIdx == null) {
            if (gaplessPrep != null || gaplessArmingFor != null) discardGaplessPrep()
            return
        }
        val next = queue.getOrNull(nextIdx) ?: return
        val nextKey = Downloads.key(next)
        val prep = gaplessPrep
        if (prep != null) {
            if (Downloads.key(prep.track) == nextKey) {
                scheduleGaplessFire(remaining)
                return
            }
            discardGaplessPrep()
        }
        if (gaplessArmingFor == nextKey) return // resolution in flight
        // Arm window: ~30s out normally; for SHORT tracks (under
        // ~90s total) arming starts at track start — waiting for
        // the last 30s of a 60-second song would leave the
        // resolution + prepare no runway at all.
        val armWindow = if (dur <= 90_000) Long.MAX_VALUE else 32_000L
        if (remaining > armWindow) return
        gaplessArmingFor = nextKey
        val localFile = Downloads.localFile(this, next)
        when {
            next.source == SOURCE_LOCAL ->
                // Local tracks ARE their content URI (same source
                // startPlaybackUri plays); MediaItem parses it.
                prepareGapless(next, next.id, null)
            localFile != null ->
                prepareGapless(next, localFile.absolutePath, null)
            next.source == SOURCE_YOUTUBE -> {
                val quality = effectiveQuality()
                gaplessExecutor.execute {
                    val resolved = try {
                        StreamResolver.resolveYoutube(next.id, quality)
                    } catch (e: Exception) {
                        null
                    }
                    runOnUiThread {
                        // Superseded while resolving (skip, queue
                        // change, toggle off): drop the result.
                        if (gaplessArmingFor != nextKey) return@runOnUiThread
                        val url = resolved?.url
                        if (resolved == null || url == null) {
                            // No pre-resolution: the boundary uses
                            // the normal foreground ladder — today's
                            // behavior, never worse.
                            gaplessArmingFor = null
                            return@runOnUiThread
                        }
                        prepareGapless(next, url, resolved)
                    }
                }
            }
            else -> // Audius: the canonical stream endpoint, the
                // same URL play() starts for an Audius track.
                prepareGapless(
                    next,
                    "https://api.audius.co/v1/tracks/${next.id}/stream?app_name=arzxsh",
                    null
                )
        }
    }

    /** Builds the second player for [next] and prepares it paused.
     *  Its listener only marks readiness until activation; after
     *  activation it behaves like a normal player listener. */
    private fun prepareGapless(next: Track, uriString: String, resolved: StreamResolver.Resolved?) {
        // 0.6.3 (WS-D): a dead instance arms nothing — an armed
        // prep in a ghost instance is a player waiting to fire.
        if (!PlayerRegistry.isOwner(instanceId)) return
        // Construction mirrors startPlaybackInternal EXACTLY (the
        // matched-UA HTTP factory is the v5.1 playback fix; a prep
        // built any other way would be rejected by googlevideo):
        // same factory, same audio attributes, plus the wake mode
        // a backgrounded player needs.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(resolved?.userAgent ?: "arzxsh (Android)")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
            )
            .build()
        exo.setAudioAttributes(
            Media3AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            false
        )
        exo.setWakeMode(C.WAKE_MODE_NETWORK)
        val holder = EnginePlayer(exo)
        // 0.6.3: registered like every player — a claim or
        // teardown releases an armed prep instead of leaving a
        // prepared player behind in a dead instance.
        PlayerRegistry.register(instanceId) { holder.release() }
        val prep = GaplessPrep(next, holder, resolved)
        gaplessPrep = prep
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (holder.retired) return
                if (!prep.activated) {
                    if (state == Player.STATE_READY) prep.ready = true
                    return
                }
                if (holder !== player) return
                if (state == Player.STATE_ENDED) handleCompletion()
                else if (state == Player.STATE_READY) buffering = false
                refreshPlaybackUi()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (holder.retired) return
                if (prep.activated && holder === player) refreshPlaybackUi()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (holder.retired) return
                if (!prep.activated) {
                    // A failed prep is silent: drop it and let the
                    // boundary take the normal path.
                    if (gaplessPrep === prep) discardGaplessPrep()
                } else if (holder === player) {
                    onPlayerFailed(holder, next, resolved, playToken, error)
                }
            }
        }
        holder.listener = listener
        exo.addListener(listener)
        try {
            val item = if (uriString.startsWith("/")) {
                MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(uriString)))
            } else {
                MediaItem.fromUri(uriString)
            }
            exo.setMediaItem(item)
            // playWhenReady stays false: prepared and buffered, but
            // silent until the boundary activation calls play().
            exo.prepare()
        } catch (e: Exception) {
            if (gaplessPrep === prep) discardGaplessPrep()
        }
    }

    /** Arms the one-shot boundary timer once the prep is READY and
     *  the end is ~1.5s out. */
    private fun scheduleGaplessFire(remainingMs: Long) {
        val prep = gaplessPrep ?: return
        if (!prep.ready || prep.activated || gaplessFire != null) return
        if (remainingMs > 1600) return
        val fire = Runnable { fireGapless() }
        gaplessFire = fire
        handler.postDelayed(fire, remainingMs.coerceAtLeast(0))
    }

    /** Boundary moment: revalidate EVERYTHING (order, toggles,
     *  sleep state, still playing) before swapping. Any mismatch
     *  discards the prep and the normal completion path — which
     *  fires from the old player's own STATE_ENDED — handles it. */
    private fun fireGapless() {
        gaplessFire = null
        val prep = gaplessPrep ?: return
        val p = player
        if (!gaplessOn() || crossfadeSec() > 0 || sleepAfterTrack || sleepAfterQueue ||
            sleepFadeRunning ||
            p == null || !p.isPlaying || !prep.ready
        ) {
            discardGaplessPrep()
            return
        }
        val nextIdx = expectedNextIndex()
        val next = nextIdx?.let { queue.getOrNull(it) }
        if (nextIdx == null || next == null || Downloads.key(next) != Downloads.key(prep.track)) {
            discardGaplessPrep()
            return
        }
        activateGapless(prep, nextIdx)
    }

    /** The swap itself: play()'s bookkeeping, but the new player
     *  is already prepared, so starting it IS the boundary — the
     *  old player is released only after the prep is installed,
     *  and its retired/listener guards keep its late STATE_ENDED
     *  from double-advancing the queue. */
    private fun activateGapless(prep: GaplessPrep, nextIdx: Int) {
        val next = prep.track
        flushListened()
        saveRecent(next)
        com.arzx.music.PlayHistory.record(this, next)
        playToken++
        queueIndex = nextIdx
        prep.activated = true
        gaplessPrep = null
        gaplessArmingFor = null
        // A prep whose stream actually starts is known-good, same
        // as a foreground ladder success (onPlayerReady's rule).
        if (prep.resolved != null) {
            StreamResolver.rememberGood(next.id, effectiveQuality(), prep.resolved)
        }
        val old = player
        current = next
        player = prep.holder
        activePlayerTrack = next
        buffering = false
        if (old != null && old !== prep.holder) {
            if (fadeSource === old) fadeSource = null
            old.release()
        }
        prep.holder.play()
        // Per-track surfaces: refreshPlaybackUi's track-change
        // branch refetches NP lyrics + ambient for the new track
        // (the same funnel a foreground play uses); an open lyrics
        // overlay is re-opened on the new track.
        npLyrics = null
        npLyricsKey = null
        if (::lyricsOverlay.isInitialized && lyricsOverlay.visibility == View.VISIBLE) {
            hideLyrics()
            showLyrics()
        }
        npLyricIdx = -2
        lastNpLyricText = null
        refreshPlaybackUi()
    }

    // ------------------------------------------------------------ queue order, shuffle, repeat

    /** Replaces the queue and rebuilds the play order around it. */
    private fun setQueue(newQueue: List<Track>, startIndex: Int = -1) {
        // New queue = new order: any gapless prep belongs to the
        // old one and is discarded (the fire-time revalidation is
        // the backstop, this is the prompt cleanup).
        discardGaplessPrep()
        queue = newQueue
        manualIndices.clear()
        if (startIndex in newQueue.indices) {
            queueIndex = startIndex
        } else if (queueIndex !in newQueue.indices) {
            queueIndex = -1
        }
        rebuildOrder()
    }

    /** Play order is a permutation of queue indices: identity normally,
     *  current-first + shuffled rest while shuffle is on. */
    private fun rebuildOrder() {
        playOrder = if (shuffleOn && queue.isNotEmpty()) {
            val rest = queue.indices.filter { it != queueIndex }.shuffled()
            if (queueIndex in queue.indices) listOf(queueIndex) + rest else rest
        } else {
            queue.indices.toList()
        }
    }

    private fun currentOrderPos(): Int = playOrder.indexOf(queueIndex)

    private fun toggleShuffle() {
        shuffleOn = !shuffleOn
        rebuildOrder()
        refreshTransportStates()
        Toast.makeText(this, if (shuffleOn) "Shuffle on" else "Shuffle off", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        refreshTransportStates()
        val msg = when (repeatMode) {
            1 -> "Repeat all"
            2 -> "Repeat one"
            else -> "Repeat off"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun refreshTransportStates() {
        if (!::npShuffleBtn.isInitialized || !::npRepeatBtn.isInitialized) return
        npShuffleBtn.setColorFilter(if (shuffleOn) accent else white55)
        npRepeatBtn.setImageResource(if (repeatMode == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        npRepeatBtn.setColorFilter(if (repeatMode > 0) accent else white55)
        if (::npShuffleDot.isInitialized) {
            npShuffleDot.visibility = if (shuffleOn) View.VISIBLE else View.INVISIBLE
        }
        if (::npRepeatDot.isInitialized) {
            npRepeatDot.visibility = if (repeatMode > 0) View.VISIBLE else View.INVISIBLE
        }
    }

    /** End-of-track: repeat-one replays, otherwise advance through the
     *  play order, wrapping only under repeat-all. */
    private fun handleCompletion() {
        val t = current
        if (t == null) {
            refreshPlaybackUi()
            return
        }
        if (sleepAfterTrack) {
            // "End of track" sleep timer: stop here instead of advancing.
            sleepAfterTrack = false
            updateSleepLabel()
            refreshPlaybackUi()
            return
        }
        if (repeatMode == 2) {
            play(t)
            return
        }
        val pos = currentOrderPos()
        if (pos >= 0 && pos + 1 in playOrder.indices) {
            play(queue[playOrder[pos + 1]])
            return
        }
        if (repeatMode == 1 && playOrder.isNotEmpty()) {
            play(queue[playOrder[0]])
            return
        }
        // "End of queue" sleep timer: the queue ran out (no next
        // track, no repeat-all wrap), so stop here instead of
        // letting Autoplay extend the session forever.
        if (sleepAfterQueue) {
            sleepAfterQueue = false
            fadeOutAndPause()
            updateSleepLabel()
            return
        }
        // End of the queue: Autoplay continues with more like this
        // (radio for YouTube, trending for Audius) when enabled.
        if (autoplayOn()) {
            continueAutoplay()
            return
        }
        // Queue truly ended: nothing is resolving anymore.
        buffering = false
        updateBufferingUi()
        // Playback stopped for real: the media service ends with
        // it (a fresh play restarts it from startPlaybackInternal).
        lastPushedTrackKey = null
        lastPushedPlaying = null
        PlaybackService.stop(this)
        refreshPlaybackUi()
    }

    /**
     * Autoplay continuation at the end of a queue. YouTube queues grow
     * a radio around the last track; Audius queues continue with
     * trending tracks not already in the queue. Failures are announced
     * and playback simply stops — nothing is faked.
     */
    private fun continueAutoplay() {
        val t = current ?: return
        val token = playToken
        playerExecutor.execute {
            var more: List<Track> = emptyList()
            var err: String? = null
            try {
                more = if (t.source == SOURCE_YOUTUBE) {
                    YtmApi.radio(t.id)
                } else {
                    fetch("/tracks/trending?limit=25")
                        ?: throw RuntimeException("trending unavailable")
                }
            } catch (e: Throwable) {
                err = shortErr(e)
            }
            runOnUiThread {
                if (token != playToken) return@runOnUiThread
                val existing = queue.map { "${it.source}_${it.id}" }.toHashSet()
                val fresh = more.filterNot { "${it.source}_${it.id}" in existing }
                if (err != null) {
                    Toast.makeText(this@MainActivity, "Autoplay unavailable: $err", Toast.LENGTH_LONG).show()
                    refreshPlaybackUi()
                    return@runOnUiThread
                }
                if (fresh.isEmpty()) {
                    refreshPlaybackUi()
                    return@runOnUiThread
                }
                setQueue(queue + fresh)
                play(fresh.first())
            }
        }
    }

    private fun playNext() {
        if (queue.isEmpty() || queueIndex < 0) return
        val pos = currentOrderPos()
        if (pos >= 0 && pos + 1 in playOrder.indices) {
            play(queue[playOrder[pos + 1]])
            return
        }
        if (repeatMode == 1 && playOrder.isNotEmpty()) {
            play(queue[playOrder[0]])
            return
        }
        Toast.makeText(this, "No next track", Toast.LENGTH_SHORT).show()
    }

    private fun playPrevious() {
        val p = player
        if (p != null && p.positionMs > 3000) {
            p.seekTo(0)
            return
        }
        if (queue.isEmpty() || queueIndex < 0) return
        val pos = currentOrderPos()
        if (pos > 0) {
            play(queue[playOrder[pos - 1]])
        } else {
            p?.seekTo(0)
        }
    }

    /** Inserts [track] to play right after the current track, keeping
     *  the shuffled order consistent. */
    private fun playNextInsert(track: Track) {
        if (queue.isEmpty() || queueIndex < 0) {
            setQueue(listOf(track), 0)
            play(track)
            return
        }
        val insertPos = queueIndex + 1
        val newQueue = queue.toMutableList()
        newQueue.add(insertPos, track)
        val pos = currentOrderPos()
        val newOrder = playOrder.map { if (it >= insertPos) it + 1 else it }.toMutableList()
        newOrder.add(if (pos >= 0) pos + 1 else newOrder.size, insertPos)
        val shifted = manualIndices.map { if (it >= insertPos) it + 1 else it }.toMutableSet()
        shifted.add(insertPos)
        manualIndices.clear()
        manualIndices.addAll(shifted)
        queue = newQueue
        playOrder = newOrder
        Toast.makeText(this, "Will play next", Toast.LENGTH_SHORT).show()
    }

    /** Adds [track] to the manual queue: stored at the end of the
     *  queue list, but its PLAY position is after the current track and
     *  any already manually queued tracks, before the source
     *  continuation (Spotify's Next in Queue rule). */
    private fun appendToQueue(track: Track) {
        if (queue.isEmpty()) {
            setQueue(listOf(track))
        } else {
            val newIndex = queue.size
            queue = queue + track
            manualIndices.add(newIndex)
            val pos = currentOrderPos()
            var insertAt = if (pos >= 0) pos + 1 else playOrder.size
            while (insertAt < playOrder.size && playOrder[insertAt] in manualIndices) insertAt++
            val newOrder = playOrder.toMutableList()
            newOrder.add(insertAt, newIndex)
            playOrder = newOrder
        }
        Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
    }

    /**
     * Builds a YouTube Music radio queue around [seed]. When the seed
     * is the playing track it is NOT interrupted — it stays as queue
     * head and the radio tracks become what's next; for any other seed
     * playback switches to it with its radio behind it. Radio needs a
     * YouTube seed (the automix is keyed by video id).
     */
    private fun startRadioFor(seed: Track) {
        if (seed.source != SOURCE_YOUTUBE) {
            Toast.makeText(this, "Radio is available for YouTube tracks", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Starting radio…", Toast.LENGTH_SHORT).show()
        val token = playToken
        val seedIsCurrent = current?.id == seed.id && current?.source == seed.source
        playerExecutor.execute {
            var list: List<Track> = emptyList()
            var err: String? = null
            try {
                list = YtmApi.radio(seed.id)
            } catch (t: Throwable) {
                err = shortErr(t)
            }
            runOnUiThread {
                if (token != playToken) return@runOnUiThread
                if (err != null) {
                    Toast.makeText(this@MainActivity, "Radio unavailable: $err", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val rest = list.filterNot { it.id == seed.id && it.source == seed.source }
                if (rest.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Radio unavailable right now.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                setQueue(listOf(seed) + rest, 0)
                if (!seedIsCurrent) play(seed)
                Toast.makeText(
                    this@MainActivity,
                    "Radio on · ${queue.size} tracks queued",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun togglePlayback() {
        // A tap mid-crossfade settles the fade instantly, so pause /
        // resume always acts on ONE player at full volume.
        if (outPlayer != null) finishCrossfadeNow()
        val active = player
        if (active == null) {
            // 0.6.3 (WS-C): a pause/stop intent while a tap-to-play
            // is still RESOLVING used to evaporate here, and the
            // track then autoplayed on READY against the user's
            // explicit pause ("after stopping it doesnt stop").
            // The intent is now recorded; the pending start
            // consumes it and lands paused.
            if (buffering || current != null) suppressAutoPlay = true
            return
        }
        if (active.retired) {
            // This instance lost process ownership earlier (a
            // newer instance claimed it and the registry released
            // this player): restart the track honestly through
            // the normal play path instead of poking a corpse.
            val t = current
            if (t != null) play(t)
            return
        }
        if (active.isPlaying) {
            active.pause()
            // A manual pause takes the timed sleep timer down
            // with it (0.6.8): mid-fade, the ramp is abandoned
            // and volume restored; merely armed, the deadline is
            // disarmed — a timer must never fire later against a
            // player the user deliberately paused and resumed.
            cancelTimedSleep()
            // A pause DISARMS the gapless boundary timer (0.6.3,
            // WS-C): fireGapless's own isPlaying revalidation is
            // the second lock; this is the first, so a paused
            // player can never be swapped out from under the user.
            gaplessFire?.let { handler.removeCallbacks(it) }
            gaplessFire = null
        } else {
            // ExoPlayer play() at the ENDED state is a no-op (unlike
            // MediaPlayer's start(), which restarted): rewind first.
            if (active.isEnded) active.seekTo(0)
            if (bridgeMuted) {
                // Resuming after an explicit stop (0.6.3): the
                // service + bridge mirror restart WITH the audio —
                // never one without the other (WS-D Bug B's rule).
                bridgeMuted = false
                lastPushedTrackKey = null
                lastPushedPlaying = null
                PlaybackService.start(this)
            }
            active.play()
        }
        // Scale pop on the glyph swap (spec §13.4), transform-only.
        // 0.6.9: the fixed 70/140ms two-step tween is the primary
        // press spring — snap to the pressed scale, spring back.
        // It also covers toggles that arrive with no local touch
        // (headset, widget, notification); the button's own
        // MotionPress uses the same spring, so the two compose.
        if (::npPlayButton.isInitialized && nowPlaying.visibility == View.VISIBLE) {
            npPlayButton.scaleX = 0.88f
            npPlayButton.scaleY = 0.88f
            Springs.scale(npPlayButton, 1f, 0.55f, 1500f, instant = springsOff())
        }
        refreshPlaybackUi()
    }

    /**
     * The one conclusive STOP (0.6.3, WS-C + WS-D). Media-session
     * stop, the service's ACTION_STOP and any future stop surface
     * all land here. End state, every time: no player playing, no
     * armed gapless prep able to fire, no pending resolution able
     * to start, the bridge snapshot cleared and the service ended
     * — audio and its controlling notification live and die
     * together, never one without the other.
     */
    private fun stopPlayback() {
        if (outPlayer != null) finishCrossfadeNow()
        // The conclusive stop ends the timed sleep timer too
        // (0.6.8): no deadline, no fade, volume whole — nothing
        // timed may outlive the stop.
        cancelTimedSleep()
        // Disarm the boundary: prep released, fire timer removed.
        discardGaplessPrep()
        gaplessFire?.let { handler.removeCallbacks(it) }
        gaplessFire = null
        // A stop while a NEW track is still resolving: the retired
        // outgoing player (audible during the fade window) dies
        // too, and the pending start is cancelled outright.
        fadeSource?.let { src ->
            src.pause()
            src.release()
        }
        fadeSource = null
        val p = player
        if (p != null) {
            p.pause()
        } else if (buffering || current != null) {
            // Nothing built yet: cancel the pending start so the
            // late READY cannot autoplay (same rule as the
            // mid-resolution pause in togglePlayback).
            suppressAutoPlay = true
            playToken++
        }
        lastPushedTrackKey = null
        lastPushedPlaying = null
        bridgeMuted = true
        PlaybackBridge.clearState()
        // The widget clears with the bridge (0.6.8): a stopped
        // player leaves a placeholder, never a stale track.
        ArzxshWidget.syncFromState(this, null)
        PlaybackService.stop(this)
        refreshPlaybackUi()
    }

    private fun isPlayingSafe(): Boolean = player?.isPlaying == true

    private fun refreshPlaybackUi() {
        val t = current
        val playing = isPlayingSafe()
        miniPlayButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        miniPlayButton.contentDescription = if (playing) "Pause" else "Play"
        if (::npPlayButton.isInitialized) {
            npPlayButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            npPlayButton.contentDescription = if (playing) "Pause" else "Play"
        }
        updateVisualizerState()
        refreshTransportStates()
        refreshDownloadButton()
        // Apple signature detail (spec §13.3): the artwork breathes —
        // slightly smaller while paused, full size while playing.
        // 0.6.9: the fixed 260ms tween is a spring now (heavier
        // than the button springs — the artwork is the biggest
        // moving surface). Motion off sets the scale instantly, so
        // the state read (full = playing, shrunk = paused) survives
        // Reduce Motion instead of freezing wherever it was.
        if (::npArtwork.isInitialized && playing != artWasPlaying) {
            artWasPlaying = playing
            val s = if (playing) 1f else 0.92f
            Springs.scale(npArtwork, s, 0.78f, 380f, instant = springsOff())
        }
        if (t == null) return
        miniTitle.text = t.title
        miniArtist.text = t.artist
        // Small first, large as the fallback: a failed small thumbnail
        // must not leave the mini player artwork blank (v4 bug). The
        // mini player is a larger moment than a list row, so both
        // URLs are upgraded to a w226 variant (rows stay small).
        ArtworkLoader.load(
            sizedArtwork(t.artworkSmall, 226), miniArtwork,
            sizedArtwork(t.artworkLarge, 226), dp(84)
        )
        if (::npTitle.isInitialized) {
            npTitle.text = t.title
            npArtist.text = t.artist
            // Hero art comes from the hi-res variant (heroArt) and
            // the blurred backdrop is generated FROM that image
            // (downscale, then blur), never by upscaling a thumbnail.
            val hero = heroArt(t)
            val npKey = t.id + "|" + (t.artworkLarge ?: t.artworkSmall ?: "")
            val trackChanged = npKey != lastNpArtKey
            lastNpArtKey = npKey
            ArtworkLoader.load(hero, npArtwork, t.artworkSmall, resources.displayMetrics.widthPixels) {
                if (trackChanged) fadeInNpArtwork()
            }
            ArtworkLoader.loadBlurred(hero, npBackdrop)
            updateHeart()
            npSeek.progress = 0
            npElapsed.text = "0:00"
            lastNpElapsed = "0:00"
            npRemaining.text = fmt(t.duration * 1000)
            lastNpRemaining = npRemaining.text.toString()
            // "Playing from {context}" top bar (v6): the owning
            // screen set the context; fall back to the artist.
            if (::npContextSmall.isInitialized) {
                npContextSmall.text =
                    if (npContextKind.isEmpty()) "Playing from" else "Playing from $npContextKind"
                npContextBig.text = npContextName.ifEmpty { t.artist }
            }
            if (::miniProgress.isInitialized) {
                lastMiniProgress = 0
                if (!buffering) miniProgress.progress = 0
            }
            if (trackChanged) {
                // New track: lyric line + preview card refetch, and
                // the ambient glow retargets to the new artwork.
                fetchNpLyrics(t)
                requestAmbient(t)
            }
        }
        // Built-in EQ follows the player (0.6.8): re-attach the
        // effects when the audio session changes. Owner-only,
        // like the bridge below — a stale instance must never
        // wire effects onto its dead session over the live one.
        if (PlayerRegistry.isOwner(instanceId)) {
            eqController.sync(player?.audioSessionId ?: 0)
        }
        // Media notification + session mirror (0.6.1): pushed
        // from this one funnel, change-guarded inside.
        pushPlaybackState()
    }

    // ------------------------------------------------- v6.1: background playback

    /** Last snapshot pushed to [PlaybackBridge] (change guard:
     *  the service re-renders only on a real change). */
    private var lastPushedTrackKey: String? = null
    private var lastPushedPlaying: Boolean? = null
    private var notifPermRequested = false

    private fun pushPlaybackState() {
        // A stale instance never writes the bridge (0.6.3, WS-D):
        // its snapshots are how a ghost track kept rendering as
        // PLAYING in the shade over the live "Nothing playing".
        if (!PlayerRegistry.isOwner(instanceId)) return
        // Nor does a stopped one (WS-C): the stop cleared the
        // bridge on purpose; nothing re-renders until a real
        // start un-mutes it.
        if (bridgeMuted) return
        val t = current ?: return
        val playing = isPlayingSafe()
        val key = Downloads.key(t)
        if (key == lastPushedTrackKey && playing == lastPushedPlaying) return
        lastPushedTrackKey = key
        lastPushedPlaying = playing
        PlaybackBridge.push(
            PlaybackBridge.State(
                title = t.title,
                artist = t.artist,
                artworkUrl = t.artworkSmall ?: t.artworkLarge,
                isPlaying = playing,
                durationMs = player?.durationMs?.takeIf { it > 0 } ?: (t.duration * 1000L),
                positionMs = player?.positionMs?.takeIf { it >= 0 } ?: 0L,
                hasNext = expectedNextIndex() != null || manualIndices.isNotEmpty(),
                hasPrev = queueIndex > 0 || currentOrderPos() > 0,
                trackKey = key
            )
        )
        // Home-screen widget (0.6.8): mirrors this same snapshot
        // through the same funnel, change guard and ownership
        // rule — it can never show a track the bridge doesn't.
        ArzxshWidget.syncFromState(this, PlaybackBridge.state)
    }

    /** POST_NOTIFICATIONS, asked once per process at first play
     *  with the honest one-liner. Denial changes nothing about
     *  playback: the service still runs, the system just hides
     *  its notification. */
    private fun requestNotifPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        if (notifPermRequested) return
        notifPermRequested = true
        Toast.makeText(
            this,
            "Allow notifications to get playback controls on your lock screen",
            Toast.LENGTH_LONG
        ).show()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7718)
    }

    /**
     * The mini player's equalizer has ONE source of truth: it shows
     * only when the Visualizer setting is on AND audio is actually
     * playing. (v4 drove it from the setting alone, so the bars
     * "played" next to a pause-state play button.)
     */
    // --------------------------------------- visualizer (0.6.7)

    private fun vizBarsEnabled(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("setting_Visualizer", true)

    private fun updateVisualizerState() {
        if (!::visualizerView.isInitialized) return
        // 0.6.7: no capture session to sync — the bars exist exactly
        // while the setting is on AND audio is actually playing,
        // and the pulse phase is read from the player's position.
        val show = vizBarsEnabled() && isPlayingSafe()
        visualizerView.visibility = if (show) View.VISIBLE else View.GONE
        setVisualizerPulsing(show)
    }

    /** One shared animator pulses every registered visualizer bar
     *  set with per-bar phase offsets: transform-only scaleY, no
     *  layout passes, no per-frame allocation. Since 0.6.7 the
     *  phase is derived from the audible player's POSITION (one
     *  bar cycle per 1150ms of playback), so the motion is
     *  genuinely playback-driven — it freezes when the stream
     *  stalls and stops with the track — with no audio capture.
     *  Stops flat (scaleY 1) when off; Reduce Motion keeps the
     *  bars static. */
    private fun setVisualizerPulsing(on: Boolean) {
        if (on && !reduceMotion()) {
            if (visualizerAnimator?.isRunning == true) return
            val anim = visualizerAnimator ?: ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
                duration = 1150
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    // The decorative pulse writes at ~30fps, not
                    // the animator's ~60 (0.6.3, audit S4): half
                    // the transform writes, visually identical at
                    // a 1150ms period, kind on the G95.
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPulseWriteMs < 33) return@addUpdateListener
                    lastPulseWriteMs = now
                    // 0.6.7: phase from the playback position, not
                    // the animator's clock; the animator is only
                    // the frame ticker now. Fallback to the
                    // animator value if no player is up yet.
                    val posMs = player?.positionMs ?: -1L
                    val phase = if (posMs >= 0L) {
                        ((posMs % 1150L).toFloat() / 1150f) * (2 * Math.PI).toFloat()
                    } else {
                        a.animatedValue as Float
                    }
                    applyVisualizerPhase(miniVisualizerBars, phase)
                    for (set in visualizerBarSets) {
                        if (set.firstOrNull()?.isAttachedToWindow == true) {
                            applyVisualizerPhase(set, phase)
                        }
                    }
                }
                visualizerAnimator = this
            }
            anim.start()
        } else {
            visualizerAnimator?.cancel()
            applyVisualizerPhase(miniVisualizerBars, -1f)
            for (set in visualizerBarSets) applyVisualizerPhase(set, -1f)
        }
    }

    private fun applyVisualizerPhase(bars: Array<View>?, phase: Float) {
        if (bars == null) return
        for (i in bars.indices) {
            bars[i].scaleY = if (phase < 0f) 1f
            else 0.4f + 0.6f * (0.5f + 0.5f * kotlin.math.sin(phase + i * 1.35f))
        }
    }

    /** Track-change artwork entrance on Now Playing: fade + settle
     *  with iOS easing over ~340ms — a real animator, not ticker
     *  steps. Skipped under Reduce Motion. */
    private fun fadeInNpArtwork() {
        if (reduceMotion() || !::npArtwork.isInitialized) return
        // The breathing spring must never fight the entrance
        // tween for the same properties (0.6.9): cancel it first,
        // and land the entrance on the breathing target for the
        // live play state, not a hard-coded 1f.
        Springs.cancel(npArtwork)
        npArtwork.animate().cancel()
        npArtwork.alpha = 0f
        npArtwork.scaleX = 0.965f
        npArtwork.scaleY = 0.965f
        val s = if (isPlayingSafe()) 1f else 0.92f
        npArtwork.animate().alpha(1f).scaleX(s).scaleY(s)
            .setDuration(340).setInterpolator(iosEase).start()
    }

    // ------------------------------------------------------------ v6: buffering indicators

    /** Pushes [buffering] into the mini player progress bar (it goes
     *  indeterminate) and the Now Playing play-button spinner. */
    private fun updateBufferingUi() {
        if (::miniProgress.isInitialized) {
            miniProgress.isIndeterminate = buffering
            if (!buffering) miniProgress.progress = lastMiniProgress.coerceAtLeast(0)
        }
        if (::npBuffering.isInitialized) {
            npBuffering.visibility =
                if (buffering && ::nowPlaying.isInitialized &&
                    nowPlaying.visibility == View.VISIBLE
                ) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------ v6: ambient artwork color

    /** Requests the ambient color for [t]'s artwork (cached per
     *  track) and crossfades the Now Playing glow. Extraction runs
     *  on the artwork pool thread; only the finished color crosses
     *  to the UI thread. */
    private fun requestAmbient(t: Track) {
        val key = Downloads.key(t)
        ambientKey = key
        if (!ambientOn()) {
            ambientColor = null
            applyAmbient(animate = false)
            return
        }
        val cached = ambientCache[key]
        if (cached != null) {
            ambientColor = cached
            applyAmbient(animate = true)
            return
        }
        ArtworkLoader.loadAmbientColor(heroArt(t) ?: t.artworkSmall) { color ->
            if (ambientKey != key) return@loadAmbientColor
            if (color != null) ambientCache[key] = color
            ambientColor = color
            applyAmbient(animate = true)
        }
    }

    /** Renders the radial ambient glow behind Now Playing; the
     *  lyrics overlay (when open) takes the solid ambient color.
     *  Toggle OFF or no usable color = the neutral player. */
    private fun applyAmbient(animate: Boolean) {
        if (!::npAmbient.isInitialized) return
        val c = if (ambientOn()) ambientColor else null
        if (c == null) {
            npAmbient.visibility = View.GONE
            if (::lyricsOverlay.isInitialized && lyricsOverlay.visibility == View.VISIBLE) {
                lyricsOverlay.setBackgroundColor(bgColor)
            }
            return
        }
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0.30f)
            gradientRadius = resources.displayMetrics.heightPixels * 0.75f
            colors = intArrayOf(
                Color.argb(235, Color.red(c), Color.green(c), Color.blue(c)),
                Color.argb(120, Color.red(c), Color.green(c), Color.blue(c)),
                bgColor
            )
        }
        npAmbient.background = glow
        npAmbient.visibility = View.VISIBLE
        if (animate && !reduceMotion()) {
            npAmbient.alpha = 0f
            npAmbient.animate().alpha(1f).setDuration(450).setInterpolator(iosEase).start()
        } else {
            npAmbient.alpha = 1f
        }
        if (::lyricsOverlay.isInitialized && lyricsOverlay.visibility == View.VISIBLE) {
            lyricsOverlay.setBackgroundColor(c)
        }
    }

    // ------------------------------------------------------------ v6: Now Playing lyrics

    /** The track's effective lyric offset: its own value when set,
     *  else the global default from Settings. */
    private fun npOffsetMs(t: Track): Long = LyricOffsets.effectiveMs(this, t)

    /** Index of the synced line sounding at [posMs] with [offsetMs]
     *  applied (effective line time = lineTimeMs − offsetMs). -1
     *  before the first line. Reads real player position only —
     *  no proportion-of-duration math anywhere. */
    private fun currentLyricIndex(lines: List<LyriTimber>, posMs: Long, offsetMs: Long): Int =
        lines.indexOfLast { it.timeMs - offsetMs <= posMs }

    /** Fetches (LRCLIB-cached) lyrics for the Now Playing surfaces:
     *  the lyric line above the title and the preview card. Silent
     *  on failure: the player never prints lyric errors. */
    private fun fetchNpLyrics(t: Track) {
        val key = Downloads.key(t)
        if (key == npLyricsKey && npLyrics != null) {
            updateNpLyricViews()
            return
        }
        npLyricsKey = key
        npLyrics = null
        npLyricIdx = -2
        lastNpLyricText = null
        updateNpLyricViews()
        lyricsExecutor.execute {
            val data = try {
                Lyrics.fetch(this@MainActivity, t)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (npLyricsKey != key) return@runOnUiThread
                npLyrics = data
                npLyricIdx = -2
                updateNpLyricViews()
            }
        }
    }

    /** Shows/hides the lyric line + preview card for the current
     *  [npLyrics] state. */
    private fun updateNpLyricViews() {
        if (!::npLyricLine.isInitialized) return
        val has = !npLyrics?.synced.isNullOrEmpty()
        npLyricLine.visibility = if (has) View.VISIBLE else View.GONE
        if (::npLyricsCard.isInitialized) {
            npLyricsCard.visibility = if (has) View.VISIBLE else View.GONE
        }
        if (has) updateNpLyricLine()
    }

    /** Moves the Now Playing lyric line + preview card to the line
     *  at the player's REAL position. Change-guarded: no view
     *  writes when the sounding line did not change. Called from
     *  the 500ms progress ticker and after seeks. */
    private fun updateNpLyricLine() {
        val t = current ?: return
        val lines = npLyrics?.synced ?: return
        if (lines.isEmpty() || !::npLyricLine.isInitialized) return
        val pos = player?.positionMs ?: return
        val idx = currentLyricIndex(lines, pos, npOffsetMs(t))
        if (idx == npLyricIdx) return
        npLyricIdx = idx
        val cur = lines.getOrNull(idx)?.text?.ifEmpty { "♪" } ?: "♪"
        if (cur != lastNpLyricText) {
            lastNpLyricText = cur
            npLyricLine.text = cur
        }
        // Same halo as the full lyrics view, smaller radius: these
        // lines always show the CURRENT line, so the glow is simply
        // re-asserted on each line change (the change guard above
        // keeps it off the per-tick path).
        setLyricGlow(npLyricLine, 10)
        if (::npLyricsCardCur.isInitialized) {
            npLyricsCardCur.text = cur
            npLyricsCardNext.text = lines.getOrNull(idx + 1)?.text?.ifEmpty { "♪" } ?: ""
            setLyricGlow(npLyricsCardCur, 10)
        }
    }

    // ------------------------------------------------- v6.1: lyric glow

    /**
     * Halo color for the CURRENT lyric line (0.6.1, from Arz's
     * inspo board): the ambient artwork color when ambience is on,
     * else the theme accent — lightened 55% toward white so the
     * halo stays luminous without ever dropping the line's own
     * contrast: the glow sits AROUND full-bright white text, it
     * never replaces the text color.
     */
    private fun lyricGlowColor(): Int {
        val base = (if (ambientOn()) ambientColor else null) ?: accent
        fun lift(c: Int) = (c + (255 - c) * 0.55f).toInt().coerceIn(0, 255)
        return Color.rgb(lift(Color.red(base)), lift(Color.green(base)), lift(Color.blue(base)))
    }

    /** Applies (radius > 0) or clears (radius = 0) the glow shadow
     *  on a lyric line. Static effect — set only when the current
     *  line CHANGES, never per frame, and Reduce Motion does not
     *  gate it. */
    private fun setLyricGlow(tv: TextView, radiusDp: Int) {
        if (radiusDp <= 0) {
            tv.setShadowLayer(0f, 0f, 0f, 0)
        } else {
            tv.setShadowLayer(dp(radiusDp).toFloat(), 0f, 0f, lyricGlowColor())
        }
    }

    // ------------------------------------------------------------ user settings (single read points)

    /**
     * Active music source: "youtube" (primary, Audius only as the
     * automatic fallback), "audius" (Audius only — no YouTube calls
     * at all), or "both" (merged, YouTube first). Set from Settings
     * or the Search screen's segmented control; both stay in sync
     * because both read/write this one preference.
     */
    private fun sourceMode(): String =
        getPreferences(MODE_PRIVATE).getString("source_mode", "youtube") ?: "youtube"

    private fun setSourceMode(mode: String) {
        getPreferences(MODE_PRIVATE).edit().putString("source_mode", mode).apply()
    }

    private fun sourceModeName(mode: String): String = when (mode) {
        "audius" -> "Audius"
        "both" -> "Both"
        else -> "YouTube Music"
    }

    private fun streamQuality(): String =
        getPreferences(MODE_PRIVATE).getString("stream_quality", "normal") ?: "normal"

    private fun dataSaverOn(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("setting_Data saver", false)

    /** The quality the resolver actually uses: Data saver wins. */
    private fun effectiveQuality(): String = if (dataSaverOn()) "data" else streamQuality()

    private fun autoplayOn(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("setting_Autoplay", true)

    private fun reduceMotion(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("setting_Reduce motion", false)

    /** Motion master check for the 0.6.9 spring system: the app's
     *  Reduce Motion setting OR the system animator duration
     *  scale at 0 (accessibility / developer options) both mean
     *  springs resolve instantly — callers pass this as the
     *  Springs instant flag. */
    private fun springsOff(): Boolean =
        reduceMotion() || ValueAnimator.getDurationScale() == 0f

    /** Artwork URL for large surfaces; Data saver downgrades to the
     *  small rendition instead of prefetching full-size art. */
    private fun largeArt(t: Track): String? =
        if (dataSaverOn()) t.artworkSmall ?: t.artworkLarge else t.artworkLarge ?: t.artworkSmall

    /**
     * Rewrites a googleusercontent artwork URL's size segment to
     * [width] px. YouTube Music serves these images at essentially
     * any size — the size lives IN the URL (=w60-h60…) — and
     * search/catalog results carry tiny ones (the thumbnail array
     * often tops out at w120). Loading that URL unchanged for Now
     * Playing stretched a 120px image across the whole screen: the
     * "low quality cover art" Arz flagged (2026-11-07). URLs with no
     * size segment (ytimg, Audius CDN, local files) pass through.
     */
    private fun sizedArtwork(url: String?, width: Int): String? {
        if (url == null || !url.contains("googleusercontent.com")) return url
        val re = Regex("=w\\d+-h\\d+")
        return if (re.containsMatchIn(url)) url.replace(re, "=w$width-h$width") else url
    }

    /** Hero artwork for Now Playing and its blurred backdrop: the
     *  largest variant the URL offers (w1080), so the full-screen
     *  art AND the blur are generated from a real high-resolution
     *  image, never an upscaled thumbnail. Data saver keeps the
     *  small rendition, honestly trading quality for bytes. */
    private fun heroArt(t: Track): String? =
        if (dataSaverOn()) t.artworkSmall ?: t.artworkLarge
        else sizedArtwork(t.artworkLarge ?: t.artworkSmall, 1080)

    // ---------------------------------------------------------------- menus, queue screen, sleep timer, downloads

    private fun buildMenuOverlay(root: FrameLayout) {
        menuOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnClickListener { hideMenu() }
        }
        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Glass sheet (0.6.1) when glass is on; v6 solid sheet
            // when off. The dim backdrop behind stays as it was.
            background = if (glassOn()) chromeGlass(22f) else solidBg(22f, Color.rgb(40, 40, 40))
            setPadding(dp(8), dp(10), dp(8), dp(10))
            elevation = dp(16).toFloat()
            isClickable = true // absorb taps; only the dim backdrop dismisses
        }
        menuTitle = label("", 13.5f, white50, boldTf).apply {
            setPadding(dp(14), dp(6), dp(14), dp(8))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        menuList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuPanel.addView(menuTitle)
        menuPanel.addView(menuList)
        val pLp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        pLp.setMargins(dp(12), 0, dp(12), dp(12))
        menuOverlay.addView(menuPanel, pLp)
        root.addView(menuOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    /** Glass bottom-sheet menu used for track actions, the Now Playing
     *  more menu, and the sleep timer picker. */
    private fun showMenu(title: String?, items: List<Pair<String, () -> Unit>>) {
        ensureOverlays()
        if (title.isNullOrEmpty()) {
            menuTitle.visibility = View.GONE
        } else {
            menuTitle.visibility = View.VISIBLE
            menuTitle.text = title
        }
        menuList.removeAllViews()
        items.forEachIndexed { i, (text, action) ->
            if (i > 0) {
                val h = View(this).apply { setBackgroundColor(Color.argb(20, 255, 255, 255)) }
                val lp = LinearLayout.LayoutParams(-1, 1)
                lp.setMargins(dp(14), 0, dp(14), 0)
                menuList.addView(h, lp)
            }
            val row = label(text, 16.5f, Color.WHITE).apply {
                setPadding(dp(14), dp(13), dp(14), dp(13))
                isClickable = true
                rippleRect()
                setOnClickListener {
                    hideMenu()
                    action()
                }
            }
            menuList.addView(row)
        }
        menuOverlay.visibility = View.VISIBLE
        if (springsOff()) {
            menuOverlay.alpha = 1f
            menuPanel.translationY = 0f
            return
        }
        // 0.6.9 sheet entrance: the dim fades in on a spring while
        // the panel springs up from a 28dp offset — a decay
        // entrance (fast attack, soft settle) replacing the
        // alpha-only 150ms fade + fixed 220ms decelerate slide.
        menuOverlay.alpha = 0f
        Springs.alpha(menuOverlay, 1f, 0.9f, 700f, instant = false)
        menuPanel.translationY = dp(28).toFloat()
        Springs.translationY(menuPanel, 0f, 0.82f, 620f, instant = false)
    }

    private fun hideMenu() {
        if (::menuOverlay.isInitialized) {
            Springs.cancel(menuOverlay)
            Springs.cancel(menuPanel)
            menuOverlay.visibility = View.GONE
        }
    }

    /** The Now Playing ⋯ menu. Radio lives here; sleep timer, artist
     *  jump and queue actions alongside. */
    private fun showNpMenu() {
        val t = current ?: return
        // Adopted menu order (spec §5.2): queue actions early,
        // navigation mid, sleep timer near the bottom, radio last.
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Play Next" to { playNextInsert(t) }
        items += "Add to Queue" to { appendToQueue(t) }
        items += "Add to Playlist" to { showAddToPlaylist(t) }
        if (t.artistId != null) {
            items += "Go to Artist" to {
                hideNowPlaying()
                showArtist(t.artistId, t.artist, t.artworkLarge)
            }
        }
        val sleepText = when {
            sleepDeadline > System.currentTimeMillis() ->
                "Sleep Timer · " + fmt((sleepDeadline - System.currentTimeMillis()).toInt()) + " left"
            sleepAfterTrack -> "Sleep Timer · ends after this track"
            else -> "Sleep Timer"
        }
        items += sleepText to { showSleepMenu() }
        items += "Start Radio" to { startRadioFor(t) }
        val header = if (t.artist.isNotEmpty()) "${t.title}\n${t.artist}" else t.title
        showMenu(header, items)
    }

    private fun showSleepMenu() {
        val now = System.currentTimeMillis()
        val title = when {
            sleepDeadline > now -> "Sleep timer · " + fmt((sleepDeadline - now).toInt()) + " left"
            sleepAfterTrack -> "Sleep timer · ends after this track"
            sleepAfterQueue -> "Sleep timer · ends at the end of the queue"
            else -> "Sleep Timer"
        }
        val items = mutableListOf<Pair<String, () -> Unit>>()
        for (m in listOf(10, 20, 30, 45, 60)) {
            items += (if (m == 60) "1 hour" else "$m minutes") to { setSleepTimer(m) }
        }
        items += "Custom…" to { showCustomSleepDialog() }
        items += "End of track" to { setSleepEndOfTrack() }
        items += "End of queue" to { setSleepEndOfQueue() }
        if (sleepDeadline > now || sleepAfterTrack || sleepAfterQueue) {
            items += "Turn off timer" to { setSleepTimer(0) }
        }
        showMenu(title, items)
    }

    private fun setSleepEndOfQueue() {
        cancelTimedSleep()
        sleepAfterTrack = false
        sleepAfterQueue = true
        updateSleepLabel()
        Toast.makeText(this, "Your sleep timer is set.", Toast.LENGTH_SHORT).show()
    }

    /** Custom sleep duration via a plain numeric input dialog. */
    private fun showCustomSleepDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Minutes"
            setTextColor(Color.WHITE)
            setHintTextColor(white50)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Sleep timer — custom")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val mins = input.text?.toString()?.trim()?.toIntOrNull()
                if (mins != null && mins > 0) {
                    setSleepTimer(mins)
                } else {
                    Toast.makeText(this, "Enter a number of minutes", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        // Any previous timer — armed or mid-fade — is fully
        // cancelled first (callbacks, ramp, volume), so a change
        // of mind never leaves the player quiet or a stale
        // deadline armed.
        cancelTimedSleep()
        sleepAfterTrack = false
        sleepAfterQueue = false
        if (minutes <= 0) {
            Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
        } else {
            val totalMs = minutes * 60_000L
            sleepDeadline = System.currentTimeMillis() + totalMs
            // The ramp occupies the last 30 seconds — or the
            // whole timer, for a custom duration shorter than
            // the ramp itself.
            sleepFadeWindowMs = minOf(SLEEP_FADE_MS, totalMs)
            handler.postDelayed(sleepFadeStart, totalMs - sleepFadeWindowMs)
            // The single confirmation (spec §6); Android Toast stands
            // in for Spotify's white snackbar.
            Toast.makeText(this, "Your sleep timer is set.", Toast.LENGTH_SHORT).show()
        }
        updateSleepLabel()
    }

    private fun setSleepEndOfTrack() {
        cancelTimedSleep()
        sleepAfterQueue = false
        sleepAfterTrack = true
        updateSleepLabel()
        Toast.makeText(this, "Your sleep timer is set.", Toast.LENGTH_SHORT).show()
    }

    // ---- 0.6.8: timed sleep fade ----
    // A timed sleep timer no longer hard-cuts at the deadline
    // (the pre-0.6.8 path fired a ~2s fade AT expiry). The last
    // 30 seconds are the fade: [sleepFadeStart] fires one ramp
    // length before the deadline and [sleepFadeTick] re-derives
    // the volume from the remaining time on every pass, so the
    // ramp is a function of the deadline, not of any one player
    // instance — a track change mid-fade swaps in a fresh player
    // and the tick pulls it back onto the ramp within 150ms
    // (startPlaybackInternal also seeds it at the ramp value).
    // At zero the player pauses and volume returns to unity:
    // the next play, whenever it comes, starts full.

    private val sleepFadeStart = Runnable { beginSleepFade() }

    private val sleepFadeTick = object : Runnable {
        override fun run() {
            if (!sleepFadeRunning) return
            if (sleepDeadline - System.currentTimeMillis() <= 0L) {
                finishSleepTimer()
                return
            }
            player?.setVolume(sleepFadeVolumeNow())
            // Defensive only: a crossfade partner cannot exist
            // in the fade window (beginSleepFade settled any
            // running one and beginCrossfade is guarded), but a
            // silent partner beats a surprise one.
            outPlayer?.setVolume(0f)
            handler.postDelayed(this, 150)
        }
    }

    /** The ramp value for *right now*: 1 at the top of the fade
     *  window, 0 at the deadline. */
    private fun sleepFadeVolumeNow(): Float {
        if (sleepFadeWindowMs <= 0L) return 1f
        val remaining = sleepDeadline - System.currentTimeMillis()
        return (remaining.toFloat() / sleepFadeWindowMs).coerceIn(0f, 1f)
    }

    private fun beginSleepFade() {
        if (sleepDeadline <= 0L || sleepFadeRunning) return
        sleepFadeRunning = true
        // Sleep wins over every transition: a crossfade in
        // flight settles now, an armed gapless prep is
        // discarded, and neither re-arms while the fade runs
        // (both read sleepFadeRunning in their guards).
        if (outPlayer != null) finishCrossfadeNow()
        discardGaplessPrep()
        handler.post(sleepFadeTick)
    }

    /** The deadline itself: pause, restore unity volume, clear
     *  the timer. Volume is restored NOW, on the paused player,
     *  so no later play can ever inherit the bottom of the ramp. */
    private fun finishSleepTimer() {
        handler.removeCallbacks(sleepFadeTick)
        sleepFadeRunning = false
        sleepDeadline = 0L
        sleepFadeWindowMs = 0L
        gaplessFire?.let { handler.removeCallbacks(it) }
        gaplessFire = null
        val p = player
        if (p != null && p.isPlaying) p.pause()
        p?.setVolume(1f)
        updateSleepLabel()
        refreshPlaybackUi()
        Toast.makeText(this, "Sleep timer ended", Toast.LENGTH_SHORT).show()
    }

    /** Cancels the TIMED sleep timer wherever it stands — armed
     *  or mid-fade: callbacks removed, ramp abandoned, volume
     *  back to unity. Called by every timer-mode change, by the
     *  manual-pause path and by the conclusive stop. The
     *  end-of-track / end-of-queue modes are boundary modes, not
     *  this timer, and are not touched here. */
    private fun cancelTimedSleep() {
        handler.removeCallbacks(sleepFadeStart)
        handler.removeCallbacks(sleepFadeTick)
        val wasFading = sleepFadeRunning
        sleepFadeRunning = false
        sleepDeadline = 0L
        sleepFadeWindowMs = 0L
        if (wasFading) player?.setVolume(1f)
        updateSleepLabel()
    }

    /** Sleep-timer expiry fades the volume out over ~2s, then pauses
     *  (spec §6), instead of hard-cutting. A crossfade's outgoing
     *  player is silenced and released too — sleep wins over fade. */
    private fun fadeOutAndPause() {
        // The boundary timer must never fire mid-fade (0.6.3,
        // WS-C): the player is still AUDIBLE for the ~2s fade,
        // and an armed prep swapping in would land at full
        // volume against the sleep timer's whole point.
        discardGaplessPrep()
        val p = player ?: return
        val out = outPlayer
        if (out != null) {
            handler.removeCallbacks(crossfadeTick)
            out.release()
            outPlayer = null
            p.setVolume(1f)
        }
        if (!p.isPlaying) return
        var step = 0
        val fade = object : Runnable {
            override fun run() {
                step++
                val v = (1f - step / 20f).coerceAtLeast(0f)
                p.setVolume(v)
                if (step < 20) {
                    handler.postDelayed(this, 100)
                } else {
                    p.pause()
                    p.setVolume(1f)
                    refreshPlaybackUi()
                }
            }
        }
        handler.post(fade)
    }

    /** The honest remaining-time label under the Now Playing controls. */
    private fun updateSleepLabel() {
        if (!::npSleepLabel.isInitialized) return
        // Runs from the 500ms ticker: only touch the view when the
        // rendered string actually changes (jank pass).
        val text = when {
            sleepAfterTrack -> "Sleep timer · ends after this track"
            sleepAfterQueue -> "Sleep timer · ends at the end of the queue"
            sleepDeadline > 0 && sleepDeadline - System.currentTimeMillis() > 0 ->
                "Sleep timer · " + fmt((sleepDeadline - System.currentTimeMillis()).toInt())
            else -> ""
        }
        if (text == lastSleepLabel) return
        lastSleepLabel = text
        if (text.isEmpty()) {
            npSleepLabel.visibility = View.GONE
        } else {
            npSleepLabel.text = text
            npSleepLabel.visibility = View.VISIBLE
        }
    }

    /** Context menu for any track row (⋯ button or long-press), on
     *  every screen that lists songs. */
    private fun showTrackMenu(track: Track) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Play Next" to { playNextInsert(track) }
        items += "Add to Queue" to { appendToQueue(track) }
        items += "Add to Playlist" to { showAddToPlaylist(track) }
        if (Downloads.isDownloaded(this, track)) {
            items += "Remove Download" to {
                Downloads.remove(this, track)
                Toast.makeText(this, "Download removed", Toast.LENGTH_SHORT).show()
                refreshPlaybackUi()
            }
        } else if (track.source != SOURCE_LOCAL) {
            items += "Download" to { startDownload(track) }
        }
        if (track.artistId != null) {
            items += "Go to Artist" to {
                if (::nowPlaying.isInitialized && nowPlaying.visibility == View.VISIBLE) hideNowPlaying()
                showArtist(track.artistId, track.artist, track.artworkLarge)
            }
        }
        items += "Share" to { shareTrack(track) }
        if (track.source == SOURCE_YOUTUBE) {
            items += "Start Radio" to { startRadioFor(track) }
        }
        val header = if (track.artist.isNotEmpty()) "${track.title}\n${track.artist}" else track.title
        showMenu(header, items)
    }

    // ------------------------------------------------------------ playlists UI

    /** Playlist picker: every playlist plus "New Playlist…". */
    private fun showAddToPlaylist(track: Track) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        Playlists.all(this).forEach { p ->
            items += p.name to {
                if (Playlists.addTrack(this, p.id, track)) {
                    Toast.makeText(this, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Already in ${p.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        items += "New Playlist…" to {
            showNameDialog("New Playlist", "") { name ->
                val p = Playlists.create(this, name)
                Playlists.addTrack(this, p.id, track)
                Toast.makeText(this, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
            }
        }
        showMenu("Add to Playlist", items)
    }

    /** One-text-field dialog (playlist create/rename). */
    private fun showNameDialog(title: String, initial: String, onSubmit: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            hint = "Playlist name"
            setTextColor(Color.WHITE)
            setHintTextColor(white50)
            setSingleLine(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            if (initial.isNotEmpty()) setSelection(initial.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) onSubmit(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLibrary() {
        navRoot("tab:library") { showLibrary() }
        if (restoreRoot("Library")) return
        visibleScreen = "Library"
        generation++
        beginScreen("Library", showAvatar = true)
        displayedRootTab = "Library"

        content.addView(sectionTitle("Playlists"))
        val playlists = Playlists.all(this)
        if (playlists.isEmpty()) {
            content.addView(label("No playlists yet. Use a track's ⋯ menu to start one.", 14.5f, white50))
        } else {
            playlists.forEachIndexed { i, p ->
                if (i > 0) content.addView(hairline())
                content.addView(playlistRow(p))
            }
        }
        content.addView(
            settingsRow("New Playlist", null) {
                showNameDialog("New Playlist", "") { name ->
                    Playlists.create(this, name)
                    showLibrary()
                }
            }
        )

        content.addView(sectionTitle("Your Music"))
        val likedCount = Favorites.liked(this).size
        content.addView(settingsRow("Liked Songs", "$likedCount liked") { showLiked() })
        val histCount = PlayHistory.totalPlays(this)
        content.addView(settingsRow("Listening History", "$histCount plays recorded") { showHistory() })
        content.addView(settingsRow("Your Listening", "Top tracks, artists and time") { showStats() })

        content.addView(sectionTitle("On This Device"))
        val locals = LocalTracks.all(this)
        if (locals.isNotEmpty()) {
            val list = locals
            content.addView(sectionTitle("${list.size} imported"))
            // Chunked (0.6.3, S1); the Import row + format note
            // render in onDone so they stay BELOW the tracks.
            val gen = generation
            addRowsChunked(
                list, content,
                queueContext = list,
                alive = { gen == generation },
                onDone = { completed -> if (completed) addImportTail() }
            )
        } else {
            addImportTail()
        }
    }

    /** Library tail: the import action + format note. Split out
     *  (0.6.3) because with chunked local-track rows it must
     *  render after the last chunk, not before it. */
    private fun addImportTail() {
        content.addView(
            settingsRow("Import Music Files", "Links to files — nothing is copied") {
                launchImport()
            }
        )
        content.addView(
            label(
                "MP3, AAC/M4A, FLAC, WAV, OGG and other formats this device can decode. " +
                    "If a file is moved or deleted, it stops working here too.",
                13f, white50
            )
        )
    }

    // ------------------------------------------------------------ local file import

    /**
     * System document picker (multi-select audio). Files are LINKED,
     * never copied: the persisted read permission keeps the content
     * URI playable across restarts, and deleting the file in a file
     * manager removes it here too — the Library copy says so.
     */
    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, reqImport)
        } catch (_: Exception) {
            Toast.makeText(this, "No file picker available on this device", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------- v6.1: lifecycle hooks

    override fun onStart() {
        super.onStart()
        activityStopped = false
    }

    override fun onStop() {
        activityStopped = true
        super.onStop()
    }

    /** The bar animator never runs while the app is backgrounded:
     *  cancelled here, re-armed by the state pass on resume if
     *  playback is still going. (Background PLAYBACK itself is the
     *  service's business and is untouched.) */
    override fun onPause() {
        super.onPause()
        visualizerAnimator?.cancel()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) updateVisualizerState()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 7718 (POST_NOTIFICATIONS) is the only permission request
        // left in the app: granted or not, playback continues
        // exactly as before, so there is nothing to do here. (The
        // old 7717 RECORD_AUDIO request died with the audiofx live
        // visualizer in 0.6.7.)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == reqProfilePhoto) {
            data.data?.let { onProfilePhotoPicked(it) }
            return
        }
        if (requestCode != reqImport) return
        val uris = ArrayList<android.net.Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return
        Toast.makeText(this, "Importing ${uris.size} file(s)…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val imported = ArrayList<Track>()
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Some providers grant non-persistable access; the
                    // track still imports for this session.
                }
                extractLocalTrack(uri)?.let { imported.add(it) }
            }
            val added = if (imported.isNotEmpty()) LocalTracks.addAll(this@MainActivity, imported) else 0
            runOnUiThread {
                when {
                    added > 0 -> Toast.makeText(
                        this@MainActivity, "Imported $added file(s)", Toast.LENGTH_SHORT
                    ).show()
                    imported.isNotEmpty() -> Toast.makeText(
                        this@MainActivity, "Already imported", Toast.LENGTH_SHORT
                    ).show()
                    else -> Toast.makeText(
                        this@MainActivity, "Couldn't read those files", Toast.LENGTH_LONG
                    ).show()
                }
                if (visibleScreen == "Library") showLibrary()
            }
        }
    }

    /** Reads title/artist/duration + embedded artwork via
     *  MediaMetadataRetriever; artwork is written once into
     *  filesDir/local_art and referenced as a file:// URL (the
     *  ArtworkLoader decodes file: URLs from disk). */
    private fun extractLocalTrack(uri: android.net.Uri): Track? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, uri)
            var title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim().orEmpty()
            var artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim().orEmpty()
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (title.isEmpty()) {
                title = displayName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                    ?: "Unknown title"
            }
            if (artist.isEmpty()) artist = "Unknown artist"
            var artPath: String? = null
            val pic = mmr.embeddedPicture
            if (pic != null) {
                try {
                    val dir = java.io.File(filesDir, "local_art").apply { mkdirs() }
                    val f = java.io.File(
                        dir, Integer.toHexString(uri.toString().hashCode()) + ".jpg"
                    )
                    if (!f.exists()) f.writeBytes(pic)
                    artPath = android.net.Uri.fromFile(f).toString()
                } catch (_: Exception) {
                    artPath = null
                }
            }
            Track(
                uri.toString(), title, artist, (durMs / 1000).toInt(),
                artPath, artPath, SOURCE_LOCAL, null
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun displayName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun playlistRow(p: Playlist): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            rippleRect()
            setOnClickListener { showPlaylist(p.id) }
            setOnLongClickListener {
                showPlaylistMenu(p)
                true
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 9)
        }
        val first = p.tracks.firstOrNull()
        ArtworkLoader.load(first?.artworkSmall ?: first?.artworkLarge, img, null, dp(52))
        row.addView(img, LinearLayout.LayoutParams(dp(52), dp(52)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        text.addView(label(p.name, 16f, Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val totalSec = p.tracks.sumOf { it.duration }
        val sub = buildString {
            append("${p.tracks.size} tracks")
            if (totalSec > 0) append(" · ").append(fmt(totalSec * 1000))
        }
        text.addView(label(sub, 14.5f, white50))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val moreIcon = icon(R.drawable.ic_more, Color.argb(115, 255, 255, 255)).apply {
            setOnClickListener { showPlaylistMenu(p) }
        }
        row.addView(moreIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
        return row
    }

    private fun showPlaylistMenu(p: Playlist) {
        showMenu(
            p.name, listOf(
                "Play" to {
                    if (p.tracks.isNotEmpty()) {
                        setQueue(p.tracks)
                        play(p.tracks.first())
                    }
                },
                "Rename" to {
                    showNameDialog("Rename Playlist", p.name) { name ->
                        Playlists.rename(this, p.id, name)
                        if (visibleScreen == "Library") showLibrary()
                    }
                },
                "Delete Playlist" to {
                    showMenu(
                        "Delete “${p.name}”?", listOf(
                            "Delete" to {
                                Playlists.delete(this, p.id)
                                Toast.makeText(this, "Playlist deleted", Toast.LENGTH_SHORT).show()
                                showLibrary()
                            },
                            "Cancel" to {}
                        )
                    )
                }
            )
        )
    }

    private fun showPlaylist(playlistId: String) {
        pushNav("playlist:$playlistId") { showPlaylist(playlistId) }
        visibleScreen = "Playlist"
        generation++
        val p = Playlists.get(this, playlistId) ?: run {
            showLibrary()
            return
        }
        beginScreen(p.name, showAvatar = false)
        val first = p.tracks.firstOrNull()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 12)
            elevation = dp(18).toFloat()
        }
        ArtworkLoader.load(first?.artworkLarge ?: first?.artworkSmall, art)
        val artLp = LinearLayout.LayoutParams(dp(180), dp(180))
        artLp.topMargin = dp(14)
        head.addView(art, artLp)
        val totalSec = p.tracks.sumOf { it.duration }
        head.addView(
            label(
                buildString {
                    append("${p.tracks.size} tracks")
                    if (totalSec > 0) append(" · ").append(fmt(totalSec * 1000))
                },
                13.5f, white50
            ).apply {
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.topMargin = dp(12)
                layoutParams = lp
            }
        )
        content.addView(head)
        if (p.tracks.isNotEmpty()) {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val aLp = LinearLayout.LayoutParams(-1, -2)
            aLp.topMargin = dp(16)
            aLp.bottomMargin = dp(6)
            actions.layoutParams = aLp
            val shuffleBtn = icon(R.drawable.ic_shuffle, Color.WHITE).apply {
                background = solidBg(999f, surface2)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    shuffleOn = true
                    setPlayContext("Playlist", p.name)
                    setQueue(p.tracks)
                    refreshTransportStates()
                    play(p.tracks[playOrder.firstOrNull() ?: 0])
                }
            }
            actions.addView(shuffleBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
            val playAll = pillButton("Play", filled = true) {
                setPlayContext("Playlist", p.name)
                setQueue(p.tracks)
                play(p.tracks.first())
            }
            val playLp = LinearLayout.LayoutParams(dp(132), dp(40))
            playLp.setMargins(dp(14), 0, dp(14), 0)
            actions.addView(playAll, playLp)
            val menuBtn = icon(R.drawable.ic_more, Color.WHITE).apply {
                background = solidBg(999f, surface2)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { showPlaylistMenu(p) }
            }
            actions.addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
            content.addView(actions)
            // Rows with playlist-specific menus: reorder happens
            // through explicit Move Up / Move Down items (the list
            // UI has no drag framework; numbered moves are exact).
            // Chunked (0.6.3, S1): a 200-track playlist no longer
            // builds in one main-thread pass.
            val gen = generation
            addRowsChunked(
                p.tracks, content,
                queueContext = p.tracks,
                contextLabel = "Playlist" to p.name,
                menuOverrideFor = { i -> { showPlaylistTrackMenu(p.id, p.tracks[i], i) } },
                alive = { gen == generation }
            )
        } else {
            content.addView(label("This playlist is empty.", 15f, white50).apply {
                setPadding(0, dp(18), 0, 0)
            })
            content.addView(label("Add tracks from any song's ⋯ menu → Add to Playlist.", 14f, white50))
        }
    }

    private fun showPlaylistTrackMenu(playlistId: String, track: Track, index: Int) {
        val p = Playlists.get(this, playlistId) ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Play Next" to { playNextInsert(track) }
        items += "Add to Queue" to { appendToQueue(track) }
        if (index > 0) {
            items += "Move Up" to {
                Playlists.moveTrack(this, playlistId, index, index - 1)
                showPlaylist(playlistId)
            }
        }
        if (index < p.tracks.size - 1) {
            items += "Move Down" to {
                Playlists.moveTrack(this, playlistId, index, index + 1)
                showPlaylist(playlistId)
            }
        }
        if (Downloads.isDownloaded(this, track)) {
            items += "Remove Download" to {
                Downloads.remove(this, track)
                Toast.makeText(this, "Download removed", Toast.LENGTH_SHORT).show()
                refreshPlaybackUi()
            }
        } else if (track.source != SOURCE_LOCAL) {
            items += "Download" to { startDownload(track) }
        }
        items += "Remove from Playlist" to {
            Playlists.removeTrackAt(this, playlistId, index)
            Toast.makeText(this, "Removed from ${p.name}", Toast.LENGTH_SHORT).show()
            showPlaylist(playlistId)
        }
        items += "Share" to { shareTrack(track) }
        val header = if (track.artist.isNotEmpty()) "${track.title}\n${track.artist}" else track.title
        showMenu(header, items)
    }

    private fun showLiked() {
        pushNav("liked") { showLiked() }
        visibleScreen = "Liked"
        generation++
        beginScreen("Liked Songs", showAvatar = false)
        val liked = Favorites.liked(this)
        if (liked.isEmpty()) {
            content.addView(sectionTitle("Nothing liked yet"))
            content.addView(label("Tap the heart on Now Playing and the song lands here.", 15f, white50))
        } else {
            content.addView(sectionTitle("${liked.size} liked"))
            val gen = generation
            addRowsChunked(
                liked, content,
                queueContext = liked,
                contextLabel = "Playlist" to "Liked Songs",
                alive = { gen == generation }
            )
        }
    }

    private fun showHistory() {
        pushNav("history") { showHistory() }
        visibleScreen = "History"
        generation++
        beginScreen("Listening History", showAvatar = false)
        val events = PlayHistory.events(this)
        if (events.isEmpty()) {
            content.addView(sectionTitle("No plays yet"))
            content.addView(label("Everything you play is recorded here, on this device only.", 15f, white50))
        } else {
            content.addView(sectionTitle("${events.size} plays"))
            val list = events.map { it.first }
            val gen = generation
            addRowsChunked(list, content, queueContext = list, alive = { gen == generation })
        }
    }

    private fun showStats() {
        pushNav("stats") { showStats() }
        visibleScreen = "Stats"
        generation++
        beginScreen("Your Listening", showAvatar = false)
        val total = PlayHistory.totalPlays(this)
        val listened = PlayHistory.listenedMs(this)
        content.addView(sectionTitle("Overview"))
        content.addView(label("Total plays · $total", 16f, Color.WHITE))
        val mins = listened / 60_000
        val timeText = when {
            mins >= 60 -> "${mins / 60} h ${mins % 60} min"
            mins > 0 -> "$mins min"
            else -> "under a minute"
        }
        content.addView(label("Time listened · about $timeText", 16f, Color.WHITE))
        content.addView(
            label(
                "Time is measured from real playback on this device and rounded — treat it as an estimate.",
                12.5f, white50
            )
        )
        val topTracks = PlayHistory.topTracks(this, 10)
        if (topTracks.isNotEmpty()) {
            content.addView(sectionTitle("Top Tracks"))
            val ctx = topTracks.map { it.first }
            topTracks.forEachIndexed { i, (t, count) ->
                if (i > 0) content.addView(hairline())
                addTrackRow(t, content, ctx)
                // Play count appended as a trailing label row is
                // overkill; the count rides in the stats line below
                // via a dedicated compact row instead.
            }
            content.addView(statusLine(topTracks.joinToString(" · ") { "${it.first.title} ×${it.second}" }))
        }
        val topArtists = PlayHistory.topArtists(this, 10)
        if (topArtists.isNotEmpty()) {
            content.addView(sectionTitle("Top Artists"))
            topArtists.forEach { (name, count, rep) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                    if (rep?.artistId != null) {
                        rippleRect()
                        setOnClickListener { showArtist(rep.artistId, name, rep.artworkLarge) }
                    }
                }
                val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                text.addView(label(name, 16f, Color.WHITE, mediumTf))
                text.addView(label("$count plays", 14f, white50))
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                content.addView(row)
            }
        }
        if (topTracks.isEmpty() && topArtists.isEmpty()) {
            content.addView(label("Play some music and your patterns show up here.", 15f, white50))
        }
    }

    private fun shareTrack(track: Track?) {
        val t = track ?: return
        val text = buildString {
            append(t.title)
            if (t.artist.isNotEmpty()) append(" — ").append(t.artist)
            if (t.source == SOURCE_YOUTUBE) append("\nhttps://music.youtube.com/watch?v=${t.id}")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share track"))
    }

    // ------------------------------------------------------------ downloads glue

    private fun refreshDownloadButton() {
        if (!::npDownloadBtn.isInitialized) return
        val t = current ?: return
        val busy = Downloads.isActive(t)
        if (::npDownloadProgress.isInitialized) {
            npDownloadProgress.visibility = if (busy) View.VISIBLE else View.GONE
        }
        npDownloadBtn.visibility = if (busy) View.INVISIBLE else View.VISIBLE
        when {
            busy -> Unit // the ring in the same slot carries this state
            Downloads.isDownloaded(this, t) -> {
                npDownloadBtn.setImageResource(R.drawable.ic_downloaded)
                npDownloadBtn.setColorFilter(accent)
            }
            else -> {
                npDownloadBtn.setImageResource(R.drawable.ic_download)
                npDownloadBtn.setColorFilter(white75)
            }
        }
    }

    private fun downloadCurrent() {
        val t = current ?: return
        if (Downloads.isDownloaded(this, t)) {
            Toast.makeText(this, "Already downloaded", Toast.LENGTH_SHORT).show()
            return
        }
        startDownload(t)
    }

    private fun startDownload(track: Track) {
        if (track.source == SOURCE_LOCAL) {
            Toast.makeText(this, "Local files are already on this device", Toast.LENGTH_SHORT).show()
            return
        }
        if (Downloads.isDownloaded(this, track)) {
            Toast.makeText(this, "Already downloaded", Toast.LENGTH_SHORT).show()
            return
        }
        // 0.6.3 (WS-D Bug D): the transfer runs on the Downloads
        // store's own process-lifetime executor. 0.6.2 ran it on
        // THIS activity's executor, whose shutdownNow() in
        // onDestroy interrupted the transfer thread mid-stream —
        // the field report's "Download failed:
        // InterruptedIOException: Thread interrupted" toast, and
        // a download that died whenever the screen did. Leaving,
        // recreating or stacking activities cannot touch it now;
        // events come back through onDownloadEvent.
        Downloads.start(this, track, effectiveQuality())
        refreshDownloadButton()
        if (visibleScreen == "Downloads") showDownloads()
        Toast.makeText(this, "Downloading ${track.title}\u2026", Toast.LENGTH_SHORT).show()
    }

    /**
     * Download events from the process-level manager (0.6.3):
     * fired on the download thread; everything here hops to the
     * UI thread and re-reads the vault, so rows and buttons
     * always render the store's state, never a cached copy. The
     * failure toast is human — the row carries the reason; the
     * raw exception never reaches the screen.
     */
    private fun onDownloadEvent(key: String, event: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val pending = Downloads.pendingRows(this@MainActivity)
                .firstOrNull { Downloads.key(it.first) == key }
            if (event == "progress") {
                val st = pending?.second
                dlProgressViews[key]?.let { (bar, tv) ->
                    val pct = st?.progress ?: 0
                    bar.isIndeterminate = pct <= 0
                    if (pct > 0) bar.progress = pct
                    tv.text = "Downloading \u00b7 $pct%"
                }
                refreshDownloadButton()
                return@runOnUiThread
            }
            val entry = Downloads.entries(this).firstOrNull { Downloads.key(it.track) == key }
            val title = entry?.track?.title ?: pending?.first?.title
            when (event) {
                "done" -> Toast.makeText(
                    this, "Downloaded: ${title ?: "track"}", Toast.LENGTH_SHORT
                ).show()
                "paused" -> Toast.makeText(
                    this, "Paused: ${title ?: "track"}", Toast.LENGTH_SHORT
                ).show()
                "removed" -> Toast.makeText(
                    this, "Download removed", Toast.LENGTH_SHORT
                ).show()
                "failed" -> Toast.makeText(
                    this, "Download failed. Open Downloads for details",
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshDownloadButton()
            refreshPlaybackUi()
            if (visibleScreen == "Downloads") showDownloads()
        }
    }

    /** Pause: an in-flight transfer stops at the next chunk check;
     *  a queued one is simply re-labeled. */
    private fun pauseDownload(track: Track) {
        Downloads.pause(this, track)
        if (visibleScreen == "Downloads") showDownloads()
    }

    /** Remove: an in-flight transfer aborts and its state row is
     *  dropped on completion; an idle row is dropped immediately. */
    private fun cancelDownload(track: Track) {
        Downloads.removePending(this, track)
        if (visibleScreen == "Downloads") showDownloads()
    }

    private fun showDownloads() {
        navRoot("tab:downloads") { showDownloads() }
        if (restoreRoot("Downloads")) return
        generation++
        beginScreen("Downloads", showAvatar = false)
        displayedRootTab = "Downloads"
        dlProgressViews.clear()
        val entries = Downloads.entries(this)
        val pending = Downloads.pendingRows(this)
        if (entries.isEmpty() && pending.isEmpty()) {
            content.addView(sectionTitle("No downloads yet"))
            content.addView(
                label(
                    "Tracks you download from Now Playing or a track's ⋯ menu live here and play offline.",
                    15f, white50
                )
            )
            return
        }
        content.addView(
            statusLine(
                "${entries.size} downloaded · ${fmtBytes(Downloads.storageBytes(this))} on this device"
            )
        )
        if (pending.isNotEmpty()) {
            content.addView(sectionTitle("Not finished"))
            pending.forEachIndexed { i, (track, st) ->
                if (i > 0) content.addView(hairline())
                content.addView(downloadStateRow(track, st))
            }
        }
        if (entries.isNotEmpty()) {
            val list = entries.map { it.track }
            content.addView(sectionTitle("${list.size} downloaded"))
            val gen = generation
            addRowsChunked(list, content, queueContext = list, alive = { gen == generation })
        }
    }

    /** One vault row for a track that is NOT fully downloaded yet:
     *  state, live progress while downloading, and the actions that
     *  state honestly supports (Pause/Resume/Retry/Remove). */
    private fun downloadStateRow(track: Track, st: Downloads.Status): LinearLayout {
        val key = Downloads.key(track)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            rippleRect()
            setOnClickListener { play(track) }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 9)
        }
        ArtworkLoader.load(track.artworkSmall ?: track.artworkLarge, img, null, dp(52))
        row.addView(img, LinearLayout.LayoutParams(dp(52), dp(52)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        text.addView(label(track.title, 16f, Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val statusText = when (st.state) {
            Downloads.ST_QUEUED -> "Queued"
            Downloads.ST_DOWNLOADING -> "Downloading · ${st.progress}%"
            Downloads.ST_PAUSED -> "Paused · ${st.progress}% · resumes from the start"
            Downloads.ST_FAILED -> "Failed · ${st.error ?: "unknown error"}"
            else -> st.state
        }
        val statusTv = label(statusText, 13.5f, if (st.state == Downloads.ST_FAILED) accent else white50).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        text.addView(statusTv)
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        if (st.state == Downloads.ST_DOWNLOADING || st.state == Downloads.ST_PAUSED) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = st.progress
                isIndeterminate = st.state == Downloads.ST_DOWNLOADING && st.progress <= 0
                progressTintList = ColorStateList.valueOf(accent)
            }
            val bLp = LinearLayout.LayoutParams(-1, dp(3))
            bLp.setMargins(dp(65), dp(2), dp(8), dp(2))
            box.addView(bar, bLp)
            if (st.state == Downloads.ST_DOWNLOADING) {
                dlProgressViews[key] = bar to statusTv
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(65), dp(2), 0, dp(6))
        }
        fun action(name: String, run: () -> Unit) {
            val tv = label(name, 14f, accent, mediumTf).apply {
                setPadding(0, dp(4), dp(22), dp(4))
                isClickable = true
                setOnClickListener { run() }
            }
            actions.addView(tv)
        }
        when (st.state) {
            Downloads.ST_DOWNLOADING -> {
                action("Pause") { pauseDownload(track) }
                action("Remove") { cancelDownload(track) }
            }
            Downloads.ST_QUEUED -> action("Remove") { cancelDownload(track) }
            Downloads.ST_PAUSED -> {
                action("Resume") { startDownload(track) }
                action("Remove") { cancelDownload(track) }
            }
            Downloads.ST_FAILED -> {
                action("Retry") { startDownload(track) }
                action("Remove") { cancelDownload(track) }
            }
        }
        box.addView(actions)
        return box
    }

    // ------------------------------------------------------------ lyrics overlay

    private fun buildLyricsOverlay(root: FrameLayout) {
        lyricsOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            // Ambient artwork color when available (set again at
            // show time); neutral app background otherwise.
            setBackgroundColor(bgColor)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(48), dp(22), dp(20))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lyricsTitle = label("", 21f, Color.WHITE, boldTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        head.addView(lyricsTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val done = label("Done", 16f, accent, mediumTf).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { hideLyrics() }
        }
        head.addView(done)
        inner.addView(head)
        lyricsStatus = label("", 13f, white50)
        val stLp = LinearLayout.LayoutParams(-1, -2)
        stLp.topMargin = dp(4)
        inner.addView(lyricsStatus, stLp)
        // Loading spinner + sync offset steppers (v6): the spinner
        // replaces the old text-only "Loading lyrics…" state; the
        // steppers shift THIS track's line timing by ±0.1s (the
        // LyricOffsets sign convention applies it to the highlight
        // and to tap-to-seek alike).
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cLp = LinearLayout.LayoutParams(-1, -2)
        cLp.topMargin = dp(8)
        ctrlRow.layoutParams = cLp
        lyricsSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(accent)
        }
        ctrlRow.addView(lyricsSpinner, LinearLayout.LayoutParams(dp(22), dp(22)))
        val offBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        offBox.addView(label("Sync", 12.5f, white55, semiboldTf).apply {
            setPadding(dp(10), 0, dp(4), 0)
        })
        fun offBtn(txt: String, delta: Long): TextView =
            label(txt, 20f, Color.WHITE, boldTf).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { adjustLyricOffset(delta) }
            }
        offBox.addView(offBtn("−", -100L))
        lyricsOffsetLabel = label("+0.0s", 14f, Color.WHITE, semiboldTf).apply {
            gravity = Gravity.CENTER
            minWidth = dp(54)
        }
        offBox.addView(lyricsOffsetLabel)
        offBox.addView(offBtn("+", 100L))
        offBox.addView(label("Reset", 13f, accent, mediumTf).apply {
            isClickable = true
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                lyricsTrack?.let { LyricOffsets.clearTrack(this@MainActivity, it) }
                refreshOffsetLabel()
                lyricsActiveLine = -2
                updateLyricsHighlight()
                npLyricIdx = -2
                updateNpLyricLine()
            }
        })
        ctrlRow.addView(offBox, LinearLayout.LayoutParams(0, -2, 1f))
        inner.addView(ctrlRow)
        lyricsScroll = ScrollView(this).apply {
            // 0.6.9: grabbing the lyrics kills any running
            // auto-scroll animation instantly — the reader wins.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    lyricsScrollAnim?.cancel()
                    lyricsScrollAnim = null
                }
                false
            }
        }
        lyricsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(40))
        }
        lyricsScroll.addView(lyricsList)
        val sLp = LinearLayout.LayoutParams(-1, 0, 1f)
        sLp.topMargin = dp(10)
        inner.addView(lyricsScroll, sLp)
        lyricsOverlay.addView(inner, FrameLayout.LayoutParams(-1, -1))
        root.addView(lyricsOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    /** Refreshes the lyrics overlay's offset readout for the
     *  overlay's current track (its own offset, else the global). */
    private fun refreshOffsetLabel() {
        if (!::lyricsOffsetLabel.isInitialized) return
        val t = lyricsTrack ?: return
        lyricsOffsetLabel.text = fmtOffset(LyricOffsets.effectiveMs(this, t))
    }

    /** One stepper tap in the lyrics view: shift the track's own
     *  offset by [deltaMs] (seeded from the effective value so the
     *  first tap starts from what is actually applied), persist
     *  immediately, and re-derive both highlights. */
    private fun adjustLyricOffset(deltaMs: Long) {
        val t = lyricsTrack ?: return
        val now = (LyricOffsets.effectiveMs(this, t) + deltaMs).coerceIn(-5000L, 5000L)
        LyricOffsets.setTrackMs(this, t, now)
        refreshOffsetLabel()
        lyricsActiveLine = -2
        updateLyricsHighlight()
        npLyricIdx = -2
        updateNpLyricLine()
    }

    private fun hideLyrics() {
        handler.removeCallbacks(lyricsTicker)
        if (::lyricsOverlay.isInitialized) {
            Springs.cancel(lyricsOverlay)
            lyricsScrollAnim?.cancel()
            lyricsScrollAnim = null
            lyricsOverlay.visibility = View.GONE
        }
    }

    /** Highlight driver for the lyrics overlay: 150ms cadence so
     *  the active line tracks the player's REAL position closely
     *  (the 500ms progress ticker steps visibly). Self-stops when
     *  the overlay is hidden; the highlight reads positionMs fresh
     *  on every run, so no fabricated interpolation is involved. */
    private val lyricsTicker = object : Runnable {
        override fun run() {
            if (!::lyricsOverlay.isInitialized || lyricsOverlay.visibility != View.VISIBLE) return
            updateLyricsHighlight()
            handler.postDelayed(this, 150)
        }
    }

    /**
     * Opens the lyrics overlay for the current track. Synced (LRC)
     * lyrics highlight line by line from the ticker and tap-to-seek;
     * plain lyrics are labeled "Not synchronized" — timing is never
     * invented. A miss says "Lyrics unavailable", plainly.
     */
    private fun showLyrics() {
        val t = current ?: run {
            Toast.makeText(this, "Nothing playing", Toast.LENGTH_SHORT).show()
            return
        }
        ensureOverlays()
        lyricsTrack = t
        // Ambient backdrop: the current artwork's color, solid;
        // the neutral background when the toggle is off or no
        // color was extracted.
        lyricsOverlay.setBackgroundColor(
            if (ambientOn() && ambientColor != null) ambientColor!! else bgColor
        )
        lyricsOverlay.visibility = View.VISIBLE
        refreshOffsetLabel()
        // 0.6.9: spring entrance (fade + rise from 24dp) instead
        // of the alpha-only 180ms fade.
        if (springsOff()) {
            lyricsOverlay.alpha = 1f
            lyricsOverlay.translationY = 0f
        } else {
            lyricsOverlay.alpha = 0f
            lyricsOverlay.translationY = dp(24).toFloat()
            Springs.alpha(lyricsOverlay, 1f, 0.9f, 700f, instant = false)
            Springs.translationY(lyricsOverlay, 0f, 0.85f, 650f, instant = false)
        }
        handler.removeCallbacks(lyricsTicker)
        handler.post(lyricsTicker)
        lyricsTitle.text = t.title
        val key = Downloads.key(t)
        if (key == lyricsKey && (lyricsLineViews.isNotEmpty() || lyricsLoading)) {
            // Already showing (or already fetching) this track.
            return
        }
        lyricsKey = key
        lyricsSyncedLines = emptyList()
        lyricsLineViews = emptyList()
        lyricsActiveLine = -2
        lyricsList.removeAllViews()
        lyricsStatus.text = t.artist
        if (::lyricsSpinner.isInitialized) lyricsSpinner.visibility = View.VISIBLE
        lyricsLoading = true
        // Fine-cadence highlight driver, alive only while the
        // overlay is visible (see lyricsTicker).
        handler.removeCallbacks(lyricsTicker)
        handler.post(lyricsTicker)
        lyricsExecutor.execute {
            val data = try {
                Lyrics.fetch(this@MainActivity, t)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (lyricsKey != key) return@runOnUiThread
                lyricsLoading = false
                renderLyrics(data)
            }
        }
    }

    private fun renderLyrics(data: LyricsData?) {
        if (::lyricsSpinner.isInitialized) lyricsSpinner.visibility = View.GONE
        lyricsList.removeAllViews()
        lyricsSyncedLines = emptyList()
        lyricsLineViews = emptyList()
        lyricsActiveLine = -2
        when {
            data == null -> {
                lyricsStatus.text = "Lyrics unavailable for this track."
                lyricsList.addView(label("No lyrics provider had this song.", 15f, white50))
                // Printed diagnosis, same philosophy as Now Playing:
                // the last fetch's per-provider outcomes, so a phone
                // screenshot names the layer that failed.
                if (Lyrics.lastReport.isNotEmpty()) {
                    lyricsList.addView(label(Lyrics.lastReport, 12.5f, white50).apply {
                        setPadding(0, dp(10), 0, 0)
                    })
                }
            }
            data.instrumental -> {
                lyricsStatus.text = "Instrumental · via ${data.source}"
                lyricsList.addView(label("This track is instrumental.", 15f, white50))
            }
            data.synced != null -> {
                lyricsStatus.text = "Synced · ${data.source} · tap a line to jump"
                val lines = data.synced
                lyricsSyncedLines = lines
                // Inspo screen 3: LARGE bold lines over the ambient
                // hue; upcoming lines sit dim, the current line
                // burns full-bright, sung lines dim further (the
                // three states are applied in updateLyricsHighlight).
                val seekOffset = lyricsTrack?.let { LyricOffsets.effectiveMs(this, it) } ?: 0L
                val views = ArrayList<TextView>(lines.size)
                lines.forEach { line ->
                    val tv = label(line.text.ifEmpty { "♪" }, 26f, Color.argb(120, 255, 255, 255), semiboldTf).apply {
                        setPadding(0, dp(10), 0, dp(10))
                        isClickable = true
                        setOnClickListener {
                            // Tap-to-seek lands where the highlight
                            // says the line is: offset applied.
                            // The glow leaves the old line HERE:
                            // resetting the active index would
                            // otherwise orphan its shadow (the
                            // highlight's prev-line cleanup keys off
                            // the index being reset).
                            lyricsLineViews.getOrNull(lyricsActiveLine)?.let { setLyricGlow(it, 0) }
                            player?.seekTo((line.timeMs - seekOffset).coerceAtLeast(0L))
                            lyricsActiveLine = -2
                            updateLyricsHighlight()
                        }
                    }
                    views.add(tv)
                    lyricsList.addView(tv)
                }
                lyricsLineViews = views
                updateLyricsHighlight()
            }
            else -> {
                lyricsStatus.text = "Not synchronized · plain lyrics via ${data.source}"
                data.plainLines.forEach { line ->
                    lyricsList.addView(label(line.ifEmpty { " " }, 16.5f, white75).apply {
                        setPadding(0, dp(3), 0, dp(3))
                    })
                }
            }
        }
    }

    /** Ticker hook (500ms): moves the synced-lyrics highlight to the
     *  line the playback position has reached. Cheap no-op whenever
     *  the overlay is closed or the lyrics are plain. */
    private fun updateLyricsHighlight() {
        if (!::lyricsOverlay.isInitialized || lyricsOverlay.visibility != View.VISIBLE) return
        if (lyricsSyncedLines.isEmpty() || lyricsLineViews.isEmpty()) return
        val pos = player?.positionMs ?: return
        // The offset shifts every line's effective start; the
        // highlight reads the same adjusted timeline tap-to-seek
        // uses, so the two can never disagree.
        val offset = lyricsTrack?.let { LyricOffsets.effectiveMs(this, it) } ?: 0L
        val idx = lyricsSyncedLines.indexOfLast { it.timeMs - offset <= pos }
        if (idx == lyricsActiveLine) return
        val prev = lyricsActiveLine
        if (prev == -2 && idx > 0) {
            // First derivation (open mid-song): everything before
            // the current line is already sung.
            for (i in 0 until idx.coerceAtMost(lyricsLineViews.size)) {
                lyricsLineViews[i].setTextColor(Color.argb(64, 255, 255, 255))
            }
        }
        lyricsLineViews.getOrNull(prev)?.let {
            // Leaving a line by moving FORWARD = sung (dimmer);
            // by seeking BACK = upcoming again (brighter dim).
            it.setTextColor(
                if (idx > prev) Color.argb(64, 255, 255, 255) else Color.argb(120, 255, 255, 255)
            )
            it.typeface = semiboldTf
            // The glow belongs to the current line ONLY: it leaves
            // with the highlight.
            setLyricGlow(it, 0)
            // 0.6.9: the activation scale leaves with it too.
            Springs.scale(it, 1f, 0.75f, 700f, instant = springsOff())
        }
        lyricsActiveLine = idx
        val view = lyricsLineViews.getOrNull(idx) ?: return
        view.setTextColor(Color.WHITE)
        view.typeface = boldTf
        // Luminous halo around the live line (inspo board): soft
        // shadowLayer, no bitmap work, set once per line change.
        setLyricGlow(view, 18)
        // 0.6.9: the live line grows a touch as it activates
        // (Apple Music's synced-lyrics read), anchored at the
        // left edge where the text starts; transform only, so the
        // list layout never moves.
        view.pivotX = 0f
        Springs.scale(view, 1.04f, 0.75f, 700f, instant = springsOff())
        // Keep the current line centered-ish (inspo): a third of
        // the viewport down, not pinned to a fixed offset.
        val y = (view.top - lyricsScroll.height / 3).coerceAtLeast(0)
        scrollLyricsTo(y)
    }

    /** Animated lyrics auto-scroll (0.6.9): a single cancellable
     *  animator glides the scroll to [y]; a user touch on the
     *  lyrics scroll cancels it (buildLyricsOverlay) and a new
     *  line target replaces it, so the highlight never fights
     *  the reader. Motion off keeps the instant scrollTo. */
    private fun scrollLyricsTo(y: Int) {
        lyricsScrollAnim?.cancel()
        lyricsScrollAnim = null
        if (springsOff()) {
            lyricsScroll.scrollTo(0, y)
            return
        }
        val from = lyricsScroll.scrollY
        if (from == y) return
        lyricsScrollAnim = ValueAnimator.ofInt(from, y).apply {
            duration = 380
            interpolator = iosEase
            addUpdateListener { a ->
                if (::lyricsScroll.isInitialized) {
                    lyricsScroll.scrollTo(0, a.animatedValue as Int)
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (lyricsScrollAnim === a) lyricsScrollAnim = null
                }

                override fun onAnimationCancel(a: android.animation.Animator) {
                    if (lyricsScrollAnim === a) lyricsScrollAnim = null
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------ queue screen

    private fun buildQueueOverlay(root: FrameLayout) {
        queueOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            // Glass backdrop (0.6.1): the near-opaque black softens
            // so the screen behind ghosts through; solid when off.
            // 0.6.3 (WS-B): lightened a step further (216 -> 198
            // alpha) so the ghosting is actually perceptible.
            setBackgroundColor(
                if (glassOn()) Color.argb(198, 10, 10, 13) else Color.argb(250, 0, 0, 0)
            )
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(48), dp(22), dp(20))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label("Queue", 26f, Color.WHITE, boldTf), LinearLayout.LayoutParams(0, -2, 1f))
        val done = label("Done", 16f, accent, mediumTf).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { hideQueue() }
        }
        head.addView(done)
        inner.addView(head)
        // 0.6.3 (audit S1): a RecyclerView with a real
        // LinearLayoutManager — the queue is the one list that
        // owns its viewport, so rows RECYCLE here instead of a
        // 200-track queue building 200 row trees in one pass.
        queueRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            itemAnimator = null
            isVerticalScrollBarEnabled = false
        }
        val sLp = LinearLayout.LayoutParams(-1, 0, 1f)
        sLp.topMargin = dp(8)
        inner.addView(queueRecycler, sLp)
        queueOverlay.addView(inner, FrameLayout.LayoutParams(-1, -1))
        root.addView(queueOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    /** Queue overlay items (0.6.3): the flattened section/row
     *  structure the RecyclerView adapter binds. */
    private sealed class QueueItem {
        object Chips : QueueItem()
        data class Note(val text: String) : QueueItem()
        data class Section(val title: String, val clear: Boolean) : QueueItem()
        data class Row(val track: Track, val isCurrent: Boolean, val queuePos: Int) : QueueItem()
    }

    private fun showQueue() {
        ensureOverlays()
        // Same composition as the hand-built list had (0.6.3,
        // audit S1): mode chips, Now Playing, the manual section
        // with its Clear action, then Next Up — now as adapter
        // items, so opening a 200-track queue binds only the
        // rows on screen instead of building 200 row trees.
        val items = ArrayList<QueueItem>()
        items.add(QueueItem.Chips)
        if (queue.isEmpty() || queueIndex < 0) {
            items.add(QueueItem.Note("Queue is empty."))
        } else {
            items.add(QueueItem.Section("Now Playing", clear = false))
            items.add(QueueItem.Row(queue[queueIndex], isCurrent = true, queuePos = -1))
            val pos = currentOrderPos()
            val upcoming = if (pos >= 0) playOrder.drop(pos + 1) else emptyList()
            // Spotify's section rule: what the user queued by hand is
            // its own section and plays before the source continuation.
            val manual = upcoming.filter { it in manualIndices }
            val rest = upcoming.filter { it !in manualIndices }
            if (manual.isNotEmpty()) {
                items.add(QueueItem.Section("Next in Queue", clear = true))
                manual.forEach { qi -> items.add(QueueItem.Row(queue[qi], isCurrent = false, queuePos = qi)) }
            }
            if (rest.isNotEmpty()) {
                items.add(QueueItem.Section("Next Up", clear = false))
                rest.forEach { qi -> items.add(QueueItem.Row(queue[qi], isCurrent = false, queuePos = qi)) }
            }
        }
        queueRecycler.adapter = QueueAdapter(items)
        queueOverlay.visibility = View.VISIBLE
        // 0.6.9: spring entrance (fade + rise from 24dp) instead
        // of the alpha-only 180ms fade.
        if (springsOff()) {
            queueOverlay.alpha = 1f
            queueOverlay.translationY = 0f
        } else {
            queueOverlay.alpha = 0f
            queueOverlay.translationY = dp(24).toFloat()
            Springs.alpha(queueOverlay, 1f, 0.9f, 700f, instant = false)
            Springs.translationY(queueOverlay, 0f, 0.85f, 650f, instant = false)
        }
    }

    /**
     * The queue's RecyclerView adapter (0.6.3, audit S1). Row
     * holders carry a fixed skeleton (art, title, artist,
     * visualizer slot, remove) bound per track — every behavior
     * of the hand-built rows is preserved: tap a non-current row
     * to play it, the current row shows the live visualizer
     * while playing, manual rows carry the remove action. The
     * visualizer set a bind installs leaves the registry through
     * the S4 detach pruning when the holder recycles.
     */
    private inner class QueueAdapter(private val items: List<QueueItem>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is QueueItem.Chips -> 0
            is QueueItem.Note -> 1
            is QueueItem.Section -> 2
            is QueueItem.Row -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                0 -> {
                    val box = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(10), 0, dp(2))
                        layoutParams = RecyclerView.LayoutParams(-1, -2)
                    }
                    object : RecyclerView.ViewHolder(box) {}
                }
                1 -> {
                    val tv = label("", 15f, white50).apply {
                        setPadding(0, dp(16), 0, 0)
                        layoutParams = RecyclerView.LayoutParams(-1, -2)
                    }
                    object : RecyclerView.ViewHolder(tv) {}
                }
                2 -> SectionHolder()
                else -> RowHolder()
            }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is QueueItem.Chips -> {
                    val box = holder.itemView as LinearLayout
                    box.removeAllViews()
                    box.addView(modeChip("Shuffle", shuffleOn) { toggleShuffle(); showQueue() })
                    val repeatText = when (repeatMode) {
                        1 -> "Repeat All"
                        2 -> "Repeat One"
                        else -> "Repeat"
                    }
                    box.addView(modeChip(repeatText, repeatMode > 0) { toggleRepeat(); showQueue() })
                }
                is QueueItem.Note -> (holder.itemView as TextView).text = item.text
                is QueueItem.Section -> (holder as SectionHolder).bind(item)
                is QueueItem.Row -> (holder as RowHolder).bind(item)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is RowHolder) holder.vizSlot.removeAllViews()
        }

        inner class SectionHolder : RecyclerView.ViewHolder(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    topMargin = dp(24)
                    bottomMargin = dp(6)
                }
            }
        ) {
            val titleView: TextView = label("", 21f, Color.WHITE, boldTf)
            val clearView: TextView = label("Clear", 14.5f, accent, mediumTf).apply {
                setPadding(dp(10), dp(4), dp(2), dp(4))
                isClickable = true
                setOnClickListener { clearManualQueue() }
            }

            init {
                val box = itemView as LinearLayout
                box.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
                box.addView(clearView)
            }

            fun bind(item: QueueItem.Section) {
                titleView.text = item.title
                clearView.visibility = if (item.clear) View.VISIBLE else View.GONE
            }
        }

        inner class RowHolder : RecyclerView.ViewHolder(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
        ) {
            val img: ImageView = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                roundedClip(this, 8)
            }
            val titleView: TextView = label("", 16f, Color.WHITE, mediumTf).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val artistView: TextView = label("", 14f, white50).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val vizSlot = FrameLayout(this@MainActivity)
            val removeView: TextView = label("✕", 15f, white50).apply {
                setPadding(dp(10), dp(6), dp(4), dp(6))
                isClickable = true
                contentDescription = "Remove from queue"
                setOnClickListener { if (queuePos >= 0) removeFromQueue(queuePos) }
            }
            var queuePos: Int = -1
            var boundTrack: Track? = null
            var boundCurrent: Boolean = false

            init {
                val row = itemView as LinearLayout
                row.addView(img, LinearLayout.LayoutParams(dp(46), dp(46)))
                val text = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                }
                text.addView(titleView)
                text.addView(artistView)
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(vizSlot)
                row.addView(removeView)
                row.setOnClickListener {
                    val t = boundTrack
                    if (!boundCurrent && t != null) {
                        hideQueue()
                        play(t)
                    }
                }
            }

            fun bind(item: QueueItem.Row) {
                boundTrack = item.track
                boundCurrent = item.isCurrent
                queuePos = item.queuePos
                titleView.text = item.track.title
                titleView.setTextColor(if (item.isCurrent) accent else Color.WHITE)
                artistView.text = item.track.artist
                ArtworkLoader.load(
                    item.track.artworkSmall ?: item.track.artworkLarge, img, null, dp(46)
                )
                vizSlot.removeAllViews()
                if (item.isCurrent && isPlayingSafe()) vizSlot.addView(visualizer())
                removeView.visibility =
                    if (!item.isCurrent && item.queuePos >= 0) View.VISIBLE else View.GONE
                if (item.isCurrent) {
                    itemView.background = null
                } else {
                    itemView.rippleRect()
                }
            }
        }
    }

    private fun modeChip(text: String, active: Boolean, onClick: () -> Unit): TextView {
        val chip = label(text, 13.5f, if (active) accent else white55, boldTf).apply {
            gravity = Gravity.CENTER
            background = if (active) {
                solidBg(999f, Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent)))
            } else {
                solidBg(999f, Color.argb(24, 255, 255, 255))
            }
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.marginEnd = dp(10)
        chip.layoutParams = lp
        return chip
    }

    /** Queue section header with an optional Clear action (Apple:
     *  Clear empties only the manually queued part). */

    private fun clearManualQueue() {
        if (manualIndices.isEmpty()) return
        val keep = queue.indices.filter { it == queueIndex || it !in manualIndices }
        if (keep.size == queue.size) return
        val keepSet = keep.toSet()
        playOrder = playOrder.filter { it in keepSet }.map { keep.indexOf(it) }
        queue = keep.map { queue[it] }
        queueIndex = keep.indexOf(queueIndex)
        manualIndices.clear()
        showQueue()
    }

    private fun hideQueue() {
        if (::queueOverlay.isInitialized) {
            Springs.cancel(queueOverlay)
            queueOverlay.visibility = View.GONE
        }
    }

    /** Removes one non-current track from the queue, remapping the
     *  play order and the manual-queue set through the same index
     *  shift clearManualQueue uses. The current track is never
     *  removable here (it has no ✕). */
    private fun removeFromQueue(qi: Int) {
        if (qi !in queue.indices || qi == queueIndex) return
        val kept = queue.indices.filter { it != qi }
        val oldToNew = kept.mapIndexed { newIdx, oldIdx -> oldIdx to newIdx }.toMap()
        playOrder = playOrder.filter { it != qi }.map { oldToNew.getValue(it) }
        val newManual = manualIndices.filter { it != qi }.map { oldToNew.getValue(it) }
        manualIndices.clear()
        manualIndices.addAll(newManual)
        queue = kept.map { queue[it] }
        if (qi < queueIndex) queueIndex -= 1
        showQueue()
    }


    // ---------------------------------------------------------------- recents

    private fun loadRecents(): List<Track> {
        val raw = getPreferences(MODE_PRIVATE).getString("recent_tracks", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Track(
                    o.getString("id"), o.getString("title"), o.getString("artist"),
                    o.optInt("duration"),
                    o.optString("artworkSmall", null)?.takeIf { it.isNotEmpty() },
                    o.optString("artworkLarge", null)?.takeIf { it.isNotEmpty() },
                    o.optString("source", SOURCE_AUDIUS),
                    o.optString("artistId", null)?.takeIf { it.isNotEmpty() }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRecent(track: Track) {
        recentTracks.removeAll { it.id == track.id && it.source == track.source }
        recentTracks.add(0, track)
        while (recentTracks.size > 10) recentTracks.removeAt(recentTracks.size - 1)
        // 0.6.3 (audit S10): the in-memory list above is the
        // synchronous truth; the JSON serialize + prefs write move
        // to the order-preserving stats thread, off play()'s
        // main-thread prelude.
        val snapshot = ArrayList(recentTracks)
        statsExecutor.execute {
            val arr = JSONArray()
            snapshot.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("duration", t.duration)
                    put("source", t.source)
                    put("artistId", t.artistId ?: JSONObject.NULL)
                    put("artworkSmall", t.artworkSmall ?: JSONObject.NULL)
                    put("artworkLarge", t.artworkLarge ?: JSONObject.NULL)
                })
            }
            getPreferences(MODE_PRIVATE).edit()
                .putString("recent_tracks", arr.toString()).apply()
        }
    }

    // ---------------------------------------------------------------- helpers kept from the original UI

    /** Leading glyph per switch-setting (0.6.1 icon pass — Arz:
     *  "add icons at settings"): drawn in the app's stroke
     *  language, one per row, secondary tint. */
    private fun settingIconRes(name: String): Int? = when (name) {
        "Data saver" -> R.drawable.ic_download
        "Autoplay" -> R.drawable.ic_next
        "Gapless playback" -> R.drawable.ic_queue
        "Ambient color from artwork" -> R.drawable.ic_ambient
        "Visualizer" -> R.drawable.ic_visual
        "Glass effects" -> R.drawable.ic_glass
        "Compact mini-player" -> R.drawable.ic_compact
        "Reduce motion" -> R.drawable.ic_motion
        else -> null
    }

    private fun setting(name: String, default: Boolean, onChange: ((Boolean) -> Unit)? = null): View {
        val key = "setting_$name"
        val sw = Switch(this).apply {
            text = name
            textSize = 16f
            setTextColor(Color.WHITE)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(accent, Color.argb(160, 255, 255, 255)))
            trackTintList = ColorStateList(states, intArrayOf(Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(60, 255, 255, 255)))
            isChecked = getPreferences(MODE_PRIVATE).getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                getPreferences(MODE_PRIVATE).edit().putBoolean(key, checked).apply()
                if (name == "Visualizer") {
                    updateVisualizerState()
                }
                if (name == "Compact mini-player") {
                    val lp = mini.layoutParams as LinearLayout.LayoutParams
                    lp.height = dp(if (checked) 48 else 58)
                    mini.layoutParams = lp
                    // The layout listener re-fits the content inset.
                }
                onChange?.invoke(checked)
            }
        }
        // Icon slot (0.6.1; 24dp since 0.6.4 WS-3): same slot
        // geometry as the tappable rows, and the same 60dp row
        // floor, so toggle rows and tappable rows share one
        // rhythm down the whole Settings screen. Toggle rows
        // had NO vertical padding before WS-3.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            minimumHeight = dp(60)
        }
        settingIconRes(name)?.let { res ->
            val ivLp = LinearLayout.LayoutParams(dp(24), dp(24))
            ivLp.marginEnd = dp(14)
            row.addView(icon(res, white55), ivLp)
        }
        row.addView(sw, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun visualizer(permanent: Boolean = false): LinearLayout {
        val bars = ArrayList<View>(4)
        val layout = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(2), 0)
            for (height in listOf(6, 12, 16, 9)) {
                val bar = View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(accent)
                        cornerRadius = dp(2).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(4), dp(height)).apply {
                        setMargins(dp(1), 0, dp(1), 0)
                    }
                    // The pulse scales bars from their base.
                    pivotY = dp(height).toFloat()
                }
                bars.add(bar)
                addView(bar)
            }
        }
        val arr = bars.toTypedArray()
        if (permanent) {
            miniVisualizerBars = arr
        } else {
            // Rows rebuild wholesale on every render; cap the
            // registry so detached sets don't accumulate.
            if (visualizerBarSets.size > 24) visualizerBarSets.clear()
            visualizerBarSets.add(arr)
            // Registry hygiene (0.6.3, audit S4): a set leaves
            // the registry when its row leaves the window, so
            // the ~30fps writers never animate discarded trees.
            // Tab retention snapshots the registry BEFORE the
            // detach and re-registers on restore, so retained
            // rows keep their sets across tab switches.
            layout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    visualizerBarSets.remove(arr)
                }
            })
        }
        return layout
    }

    private fun fetch(path: String): List<Track>? {
        return try {
            val connection = (URL("https://api.audius.co/v1$path&app_name=arzxsh").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (connection.responseCode !in 200..299) return null
            val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("data")
            List(data.length()) { index ->
                val item = data.getJSONObject(index)
                val art = item.optJSONObject("artwork")
                Track(
                    item.getString("id"), item.getString("title"),
                    item.getJSONObject("user").getString("name"), item.optInt("duration"),
                    art?.optString("150x150", null)?.takeIf { it.isNotEmpty() },
                    // Hero variant: 1000x1000 where the API exposes
                    // it (480x480 stretched over Now Playing was the
                    // other half of the "low quality cover art").
                    art?.optString("1000x1000", null)?.takeIf { it.isNotEmpty() }
                        ?: art?.optString("480x480", null)?.takeIf { it.isNotEmpty() }
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------- v6.1: back navigation

    /** Marks [restore] as the root of the current tab, dropping
     *  any content-screen history. Called at the top of every tab
     *  root screen; a no-op while a pop is re-rendering. */
    private fun navRoot(key: String, restore: () -> Unit) {
        if (navRestoring) return
        navStack.clear()
        navStack.add(NavEntry(key, restore))
    }

    /** Pushes a content screen onto the back-stack. Re-entering
     *  the SAME screen (a refresh re-render, e.g. after a rename)
     *  replaces the top entry instead of stacking a duplicate, so
     *  Back never lands on the screen you're already looking at. */
    private fun pushNav(key: String, restore: () -> Unit) {
        if (navRestoring) return
        val top = navStack.lastOrNull()
        if (top != null && top.key == key) {
            navStack[navStack.lastIndex] = NavEntry(key, restore)
        } else {
            navStack.add(NavEntry(key, restore))
        }
    }

    /** Pops exactly one level: re-renders the screen beneath the
     *  current one. Returns false at a tab root (nothing to pop),
     *  where the system Back behavior takes over. */
    private fun goBack(): Boolean {
        if (navStack.size <= 1) return false
        navStack.removeAt(navStack.lastIndex)
        val target = navStack.last()
        navRestoring = true
        try {
            target.restore()
        } finally {
            navRestoring = false
        }
        return true
    }

    /** The shared back-arrow affordance (36dp, soft circle). */
    private fun backArrowView(): ImageView = icon(R.drawable.ic_back, Color.WHITE).apply {
        contentDescription = "Back"
        background = GradientDrawable().apply {
            setColor(Color.argb(30, 255, 255, 255))
            shape = GradientDrawable.OVAL
        }
        setPadding(dp(7), dp(7), dp(7), dp(7))
        setOnClickListener { goBack() }
    }

    /** Back-arrow row for the custom-header screens (artist,
     *  album, remote playlist), which don't use [beginScreen].
     *  Adds nothing at a tab root (stack size 1). Called at screen
     *  entry AND after the async re-render replaces the content. */
    private fun addScreenBackArrow() {
        if (navStack.size <= 1) return
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(backArrowView(), LinearLayout.LayoutParams(dp(36), dp(36)))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(4)
        row.layoutParams = lp
        content.addView(row)
    }

    // ------------------------------------------------- v6.1: tab-root retention

    /**
     * A retained tab-root screen: its detached view tree, scroll
     * position, build time, data fingerprint and visualizer bar
     * registrations. Retention is BOUNDED by design: at most the
     * four roots Home / Search / Library / Downloads. Pushed pages
     * are never retained, and Settings is deliberately excluded —
     * it has no fetch cost and its switches must always render
     * live state.
     */
    private class RetainedRoot(
        val children: List<View>,
        val scrollY: Int,
        var builtAtMs: Long,
        val fingerprint: Int,
        val barSets: List<Array<View>>
    )

    private val retainedRoots = LinkedHashMap<String, RetainedRoot>()

    /** The tab root currently on screen, or null on pushed
     *  screens, overlays and Settings. */
    private var displayedRootTab: String? = null

    /** Home is retained only when SETTLED: main render landed and
     *  no Made-for-You mix fetch still in flight — a retained
     *  skeleton would be a frozen lie. */
    private var homeMainDone = false
    private var homeMixPending = false
    private var homeWarmToken = 0

    /** Search is retained only when no search is in flight. */
    private var searchInFlight = false

    /** Incremented whenever a render replaces the content
     *  (beginScreen) and on every fresh Search build: a search
     *  completion renders only while its captured epoch is still
     *  current. Unlike the build-time generation, the epoch is a
     *  FIELD — searches started from a restored Search screen
     *  capture the live value and can complete. */
    private var searchEpoch = 0

    private val homeTtlMs = 30L * 60L * 1000L

    /** Detaches the displayed tab root's tree into the retention
     *  cache. Called whenever a render is about to replace the
     *  content (beginScreen + the custom-header screens). */
    private fun saveCurrentRoot() {
        val tab = displayedRootTab ?: return
        displayedRootTab = null
        val settled = when (tab) {
            "Home" -> homeMainDone && !homeMixPending
            "Search" -> !searchInFlight
            // Chunked list builds (0.6.3) must have landed: a
            // half-built Downloads/Library tree is not a root
            // worth retaining.
            "Downloads", "Library" -> chunkBuildsInFlight == 0
            else -> true
        }
        if (!settled) {
            retainedRoots.remove(tab)
            return
        }
        val kids = ArrayList<View>(content.childCount)
        for (i in 0 until content.childCount) kids.add(content.getChildAt(i))
        content.removeAllViews()
        val sv = content.parent as? ScrollView
        retainedRoots[tab] = RetainedRoot(
            kids,
            sv?.scrollY ?: 0,
            System.currentTimeMillis(),
            rootFingerprint(tab),
            ArrayList(visualizerBarSets)
        )
        // Retention trim (0.6.3, audit S8): at most TWO tab trees
        // stay resident — Home (the anchor) plus the most recent
        // other tab. A third full tree (with its bitmaps) was
        // memory the Helio G95 felt as GC pressure everywhere.
        // An evicted tab simply rebuilds on next visit, exactly
        // like a fingerprint miss today; restore semantics from
        // the user's side are unchanged.
        while (retainedRoots.size > 2) {
            val victim = retainedRoots.keys.firstOrNull { it != "Home" && it != tab } ?: break
            retainedRoots.remove(victim)
        }
    }

    /** Cheap local fingerprints: if the underlying data changed
     *  while the tab was away, the retained tree is dropped and
     *  the tab rebuilds. No network involved in any of these.
     *  0.6.3 (audit S2/S6): these are O(1) — the Library stores
     *  read from their in-memory caches (sizes only, no JSON
     *  parse) and Downloads keeps a versioned index counter
     *  instead of stat-ing every file on every navigation. */
    private fun rootFingerprint(tab: String): Int = when (tab) {
        "Library" -> {
            val ps = Playlists.all(this)
            ps.size * 131 + ps.sumOf { it.tracks.size } * 17 + Favorites.liked(this).size
        }
        "Downloads" -> Downloads.fingerprint(this)
        "Search" -> sourceMode().hashCode()
        else -> 0
    }

    /** Re-attaches a retained root: instant, no fetch, no
     *  skeleton. False when there is no usable entry and the
     *  caller must rebuild. */
    private fun restoreRoot(tab: String): Boolean {
        // The outgoing root (if any) is saved first: restore never
        // destroys a sibling tab's retained tree.
        saveCurrentRoot()
        val entry = retainedRoots[tab] ?: return false
        if (tab == "Downloads" && Downloads.anyActive()) return false
        if (entry.fingerprint != rootFingerprint(tab)) {
            retainedRoots.remove(tab)
            return false
        }
        generation++
        content.removeAllViews()
        entry.children.forEach { content.addView(it) }
        visualizerBarSets.clear()
        visualizerBarSets.addAll(entry.barSets)
        visibleScreen = when (tab) {
            "Home" -> "Listen Now"
            else -> tab
        }
        val sv = content.parent as? ScrollView
        val y = entry.scrollY
        if (sv != null) sv.post { sv.scrollTo(0, y) }
        displayedRootTab = tab
        return true
    }

    private fun beginScreen(title: String, showAvatar: Boolean) {
        saveCurrentRoot()
        searchEpoch++
        visibleScreen = title
        visualizerBarSets.clear()
        clearSkeletons()
        content.removeAllViews()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The avatar must never be cut by the row's bounds (below).
            clipChildren = false
            if (showAvatar) {
                // Root cause of the clipped avatar (v4 screenshots):
                // the row's height was driven by the TITLE's text
                // metrics — at smaller font scales the 32sp title
                // measures ~31dp, less than the avatar's 34dp + margin
                // footprint — and the row clipped the circle's bottom.
                // The row now guarantees room for the full circle.
                minimumHeight = dp(44)
            }
        }
        // Back arrow (0.6.1): present exactly when the back-stack
        // has somewhere to go — content screens get it, the five
        // tab roots (stack size 1) never do.
        if (navStack.size > 1) {
            val bLp = LinearLayout.LayoutParams(dp(36), dp(36))
            bLp.marginEnd = dp(10)
            head.addView(backArrowView(), bLp)
        }
        head.addView(label(title, 32f, Color.WHITE, boldTf).apply {
            letterSpacing = -0.01f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (showAvatar) {
            // Local profile corner (0.6.4, WS-2): the profile
            // photo when one is set, else the display-name
            // initial on a SOLID theme-accent circle — the old
            // accent→purple gradient is gone (it read as stock
            // AI decoration). Tapping opens the profile sheet.
            val avatarBox = FrameLayout(this).apply {
                isClickable = true
                contentDescription = "Profile"
                setOnClickListener { showProfileSheet() }
            }
            fillAvatar(avatarBox, 34)
            avatarBoxes.add(avatarBox)
            // No top margin (vertical centering handles placement);
            // the end side keeps the content's standard 22dp inset via
            // the content padding, aligned with every other row.
            val aLp = LinearLayout.LayoutParams(dp(34), dp(34))
            aLp.marginStart = dp(10)
            head.addView(avatarBox, aLp)
        }
        if (title == "Listen Now") {
            // Brand lockup (0.6.4, WS-4/WS-5): the living
            // Equalizer A + wordmark as a compact brand row
            // ABOVE the title — beside it would crowd the 32sp
            // headline and the corner avatar; stacked, the
            // composition reads brand → screen → content and
            // the title stays the biggest type on the screen.
            // Listen Now only.
            val brand = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(10))
            }
            val logo = EqLogoView(this).apply {
                motionAllowed = { !activityStopped && !reduceMotion() }
                playingProvider = { isPlayingSafe() }
                contentDescription = "arzxsh"
            }
            brand.addView(logo, LinearLayout.LayoutParams(dp(28), dp(28)))
            val wm = label("arzxsh", 17f, Color.WHITE, semiboldTf)
            val wmLp = LinearLayout.LayoutParams(-2, -2)
            wmLp.marginStart = dp(9)
            brand.addView(wm, wmLp)
            content.addView(brand, 0)
        }
        content.addView(head)
        // Tab/page switches (0.6.9): fade + an 8dp vertical
        // slide on one cheap spring pair, replacing the flat
        // 150ms fade — no stagger, nothing per-row, transform
        // and alpha only.
        if (springsOff()) {
            content.alpha = 1f
            content.translationY = 0f
        } else {
            content.alpha = 0f
            content.translationY = dp(8).toFloat()
            Springs.alpha(content, 1f, 0.9f, 800f, instant = false)
            Springs.translationY(content, 0f, 0.88f, 750f, instant = false)
        }
    }

    // ------------------------------------------------- local profile (0.6.4, WS-2)

    /** (Re)renders an avatar into [box] at [sizeDp]: the
     *  profile photo (center-cropped, circle-clipped) when one
     *  is set, else the display-name initial on a solid
     *  theme-accent circle. Used by the header corner (34dp)
     *  and the profile sheet preview (96dp). */
    private fun fillAvatar(box: FrameLayout, sizeDp: Int) {
        box.removeAllViews()
        val bmp = Profile.photoBitmap(this, dp(sizeDp))
        if (bmp != null) {
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
            }
            box.addView(iv, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        } else {
            val tv = label(
                Profile.initial(this), sizeDp * 0.42f, Color.WHITE, boldTf
            ).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(accent)
                    shape = GradientDrawable.OVAL
                }
            }
            box.addView(tv, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        }
    }

    /** Refreshes every live header avatar after a profile
     *  change (tab retention can keep several roots alive). */
    private fun refreshAvatars() {
        for (box in avatarBoxes) fillAvatar(box, 34)
    }

    /**
     * The profile sheet: current picture (tap it — or the photo
     * row — to change it), an editable display name, and photo
     * removal. Everything is local (see Profile): no account,
     * no server, works signed-out forever. When v7's Google
     * sign-in ships, the Google photo/name take precedence and
     * this becomes the fallback — not built here.
     */
    private fun showProfileSheet() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(4))
        }
        val preview = FrameLayout(this).apply {
            isClickable = true
            contentDescription = "Change profile photo"
            setOnClickListener { launchProfilePhotoPicker() }
        }
        fillAvatar(preview, 96)
        val pLp = LinearLayout.LayoutParams(dp(96), dp(96))
        pLp.gravity = Gravity.CENTER_HORIZONTAL
        box.addView(preview, pLp)
        profilePreviewBox = preview
        box.addView(label("On this device only — no account, nothing uploaded", 12.5f, white50).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        val nameLabel = label("Display name", 13f, white50, semiboldTf)
        val nlLp = LinearLayout.LayoutParams(-1, -2)
        nlLp.topMargin = dp(18)
        box.addView(nameLabel, nlLp)
        val nameInput = EditText(this).apply {
            setText(Profile.displayName(this@MainActivity))
            hint = "Your name"
            setTextColor(Color.WHITE)
            setHintTextColor(white50)
            typeface = regularTf
            setTextSize(15.5f)
            background = solidBg(10f, surface2)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(40))
            isSingleLine = true
        }
        val niLp = LinearLayout.LayoutParams(-1, -2)
        niLp.topMargin = dp(8)
        niLp.bottomMargin = dp(8)
        box.addView(nameInput, niLp)
        box.addView(
            settingsRow(
                if (Profile.hasPhoto(this)) "Change photo" else "Set photo",
                "Pick from your photos",
                iconRes = R.drawable.ic_image
            ) { launchProfilePhotoPicker() }
        )
        if (Profile.hasPhoto(this)) {
            box.addView(
                settingsRow("Remove photo", null, errorColor, iconRes = R.drawable.ic_trash) {
                    Profile.removePhoto(this@MainActivity)
                    refreshAvatars()
                    // Rebuild the sheet so the photo rows match
                    // the new state (Remove disappears).
                    profileDialog?.dismiss()
                    showProfileSheet()
                }
            )
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Profile")
            .setView(box)
            .setPositiveButton("Done") { _, _ ->
                Profile.setDisplayName(this, nameInput.text.toString())
                refreshAvatars()
            }
            .setNegativeButton("Cancel", null)
            .create()
        profileDialog = dialog
        dialog.setOnDismissListener {
            if (profileDialog === dialog) {
                profileDialog = null
                profilePreviewBox = null
            }
        }
        dialog.show()
    }

    /** System photo picker — NO storage permission: API 33+
     *  gets the dedicated photo picker (ACTION_PICK_IMAGES),
     *  older versions the document picker in image mode. The
     *  returned URI's read grant is transient, so the result
     *  handler copies the image into private storage at once. */
    private fun launchProfilePhotoPicker() {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
        try {
            startActivityForResult(intent, reqProfilePhoto)
        } catch (_: Exception) {
            Toast.makeText(this, "No photo picker available on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun onProfilePhotoPicked(uri: Uri) {
        executor.execute {
            val ok = Profile.savePhotoFromUri(this@MainActivity, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    refreshAvatars()
                    profilePreviewBox?.let { fillAvatar(it, 96) }
                    Toast.makeText(
                        this@MainActivity, "Profile photo set", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity, "Couldn't use that photo", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun sectionTitle(text: String): TextView {
        val tv = label(text, 21f, Color.WHITE, boldTf)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(24)
        lp.bottomMargin = dp(6)
        tv.layoutParams = lp
        return tv
    }

    private fun label(text: String, size: Float, color: Int, tf: Typeface = regularTf) = TextView(this).apply {
        this.text = text
        setTextSize(size)
        setTextColor(color)
        typeface = tf
        setPadding(0, dp(2), 0, dp(2))
    }

    override fun onDestroy() {
        // Invalidate in-flight playback work FIRST (0.6.3, WS-D
        // Bug A): resolution completions and ladder steps check
        // playToken, and after this bump none of them may build
        // or start a player inside this dying instance.
        playToken++
        suppressAutoPlay = true
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(lyricsTicker)
        visualizerAnimator?.cancel()
        flushListened()
        handler.removeCallbacks(crossfadeTick)
        handler.removeCallbacks(sleepFadeStart)
        handler.removeCallbacks(sleepFadeTick)
        if (::eqController.isInitialized) eqController.release()
        discardGaplessPrep()
        // Release EVERY player this instance created — the main
        // player, the crossfade partner, the ARMED GAPLESS PREP
        // and the fade-out source — through the process registry.
        // The pre-0.6.3 teardown released the first two by hand
        // and never touched fadeSource at all; the registry list
        // is written at creation time, so nothing can be missed.
        PlayerRegistry.releaseOwner(instanceId)
        outPlayer = null
        player = null
        fadeSource = null
        // End the bridge + service ONLY if they are still mine:
        // when a newer instance has already claimed the process,
        // its controls and its service are not mine to kill (a
        // stale destroy unplugging the live instance was the
        // other half of the split-brain).
        if (myControls != null && PlaybackBridge.controls === myControls) {
            PlaybackBridge.controls = null
        }
        if (myDlListener != null && Downloads.uiListener === myDlListener) {
            Downloads.uiListener = null
        }
        if (PlayerRegistry.isOwner(instanceId)) {
            PlaybackBridge.clearState()
            PlaybackService.stop(this)
        }
        executor.shutdownNow()
        playerExecutor.shutdownNow()
        lyricsExecutor.shutdownNow()
        statsExecutor.shutdownNow()
        gaplessExecutor.shutdownNow()
        fetchPool.shutdownNow()
        super.onDestroy()
    }
}
