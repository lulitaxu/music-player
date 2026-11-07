package com.arzx.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Offline download store: audio files in the app's private files dir
 * plus a small JSON index in SharedPreferences. Works for both sources —
 * Audius via its stream URL, YouTube via the shared on-device resolver
 * ([StreamResolver], the same chain playback uses). Downloaded files
 * are an in-app offline cache only; nothing is exported to shared
 * storage.
 * All functions do disk/network work: background thread only, except
 * [entries]/[localFile]/[isDownloaded] which are cheap index reads.
 */
object Downloads {
    data class Entry(val track: Track, val fileName: String)

    /**
     * Vault state for a track that is NOT (yet) fully downloaded.
     * Downloaded-ness itself is derived from the file index — a
     * state row only exists for queued / downloading / paused /
     * failed tracks, so it can never claim "downloaded" falsely.
     */
    data class Status(val state: String, val progress: Int, val error: String?)

    const val ST_QUEUED = "queued"
    const val ST_DOWNLOADING = "downloading"
    const val ST_PAUSED = "paused"
    const val ST_FAILED = "failed"

    /** Thrown by [downloadSync] when the caller's stop flag fires. */
    class StoppedException : RuntimeException("stopped")

    // ------------------------------------------------------------ download manager (0.6.3, WS-D Bug D)

    /**
     * Downloads run HERE, on a process-lifetime executor owned by
     * this store object — NEVER on an activity executor. 0.6.2 ran
     * transfers on MainActivity's shared executor, whose
     * shutdownNow() in onDestroy INTERRUPTED the transfer thread
     * mid-stream: the field failure was a toast reading
     * "Download failed: InterruptedIOException: Thread
     * interrupted", and the download died with the screen. This
     * executor is never shut down; activity death cannot touch a
     * transfer, and neither can a second instance's lifecycle.
     */
    private val dlExecutor = Executors.newSingleThreadExecutor()

    /** Keys with a transfer running or queued on [dlExecutor]. */
    private val activeKeys = HashSet<String>()

    /** Pending stop intents per key: "pause" / "remove". Polled by
     *  the transfer loop (the StoppedException path), exactly as
     *  the activity-side map was — it just lives at process level
     *  now, next to the transfers it controls. */
    private val stopModes = HashMap<String, String>()

    /**
     * UI event sink, registered by the live activity: (trackKey,
     * event) with event in "progress" / "done" / "paused" /
     * "removed" / "failed". Invoked from the download thread; the
     * activity hops to the UI thread itself. Null when no UI is
     * alive — the vault states remain the source of truth and the
     * Downloads screen renders them on next open.
     */
    @Volatile
    var uiListener: ((String, String) -> Unit)? = null

    private fun notifyUi(key: String, event: String) {
        try {
            uiListener?.invoke(key, event)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun isActive(track: Track): Boolean = activeKeys.contains(key(track))

    @Synchronized
    fun anyActive(): Boolean = activeKeys.isNotEmpty()

    /** Human failure text for the Downloads row. The raw exception
     *  NEVER reaches the UI (0.6.2 toasted it verbatim). */
    private fun humanError(t: Throwable): String {
        val msg = t.message ?: ""
        return when {
            msg.startsWith("HTTP ") -> "Server error ($msg) — try again later"
            msg.contains("stream resolution failed") ->
                "Couldn't resolve the stream — try again"
            t is java.net.UnknownHostException || t is java.net.ConnectException ||
                t is java.net.SocketTimeoutException || t is java.net.SocketException ->
                "Network error — check your connection and tap Retry"
            else -> "Download failed — tap Retry"
        }
    }

    /**
     * Starts (or queues) a download. All state writes, progress
     * and completion handling happen on [dlExecutor]; returns
     * immediately. Re-starting an already-active key is a no-op.
     */
    fun start(ctx: Context, track: Track, quality: String) {
        val appCtx = ctx.applicationContext
        val key = key(track)
        synchronized(this) {
            if (!activeKeys.add(key)) return
            stopModes.remove(key)
            setStatus(
                appCtx, track,
                if (activeKeys.size > 1) ST_QUEUED else ST_DOWNLOADING, 0
            )
        }
        notifyUi(key, "progress")
        dlExecutor.execute {
            var lastPct = 0
            var err: Throwable? = null
            var interrupted = false
            var stopped = false
            setStatus(appCtx, track, ST_DOWNLOADING, 0)
            notifyUi(key, "progress")
            try {
                downloadSync(
                    appCtx, track, quality,
                    shouldContinue = { synchronized(this) { !stopModes.containsKey(key) } },
                    onProgress = { pct ->
                        lastPct = pct
                        if (pct % 10 == 0) {
                            setStatus(appCtx, track, ST_DOWNLOADING, pct)
                            notifyUi(key, "progress")
                        }
                    }
                )
            } catch (e: StoppedException) {
                stopped = true
            } catch (e: InterruptedIOException) {
                interrupted = true
            } catch (e: InterruptedException) {
                interrupted = true
            } catch (t: Throwable) {
                // An interrupt surfacing in another wrapper still
                // means "interrupted", never "failed".
                if (t.cause is InterruptedIOException || t.cause is InterruptedException) {
                    interrupted = true
                } else {
                    err = t
                }
            }
            val mode: String?
            synchronized(this) {
                activeKeys.remove(key)
                mode = stopModes.remove(key)
            }
            when {
                stopped && mode == "pause" -> {
                    setStatus(appCtx, track, ST_PAUSED, lastPct)
                    notifyUi(key, "paused")
                }
                stopped -> {
                    clearStatus(appCtx, key)
                    notifyUi(key, "removed")
                }
                interrupted -> {
                    // Survival rule: an interruption is a PAUSE
                    // with its progress kept, resumable from the
                    // Downloads row — never a raw failure.
                    setStatus(appCtx, track, ST_PAUSED, lastPct)
                    notifyUi(key, "paused")
                }
                err != null -> {
                    setStatus(appCtx, track, ST_FAILED, lastPct, humanError(err))
                    notifyUi(key, "failed")
                }
                else -> notifyUi(key, "done")
            }
        }
    }

    /** Pause: an in-flight transfer stops at its next chunk check
     *  and lands as [ST_PAUSED]; an idle row is re-labeled now. */
    fun pause(ctx: Context, track: Track) {
        val key = key(track)
        val active = synchronized(this) {
            if (activeKeys.contains(key)) {
                stopModes[key] = "pause"
                true
            } else {
                false
            }
        }
        if (!active) {
            setStatus(ctx, track, ST_PAUSED, statusOf(ctx, track)?.progress ?: 0)
            notifyUi(key, "paused")
        }
    }

    /** Remove: an in-flight transfer aborts and its row is
     *  dropped on completion; an idle row is dropped now. */
    fun removePending(ctx: Context, track: Track) {
        val key = key(track)
        val active = synchronized(this) {
            if (activeKeys.contains(key)) {
                stopModes[key] = "remove"
                true
            } else {
                false
            }
        }
        if (!active) {
            clearStatus(ctx, key)
            notifyUi(key, "removed")
        }
    }

    /**
     * Startup survival (0.6.3): transfers left at queued /
     * downloading by a dead process RESUME automatically instead
     * of being stamped failed. (A user-paused row is a different
     * persisted state and is never auto-resumed.) Orphaned .part
     * files are cleaned first — a resumed transfer restarts from
     * zero, as the row copy already states.
     */
    fun resumeInterruptedAsync(ctx: Context, quality: String) {
        val appCtx = ctx.applicationContext
        dlExecutor.execute {
            dir(appCtx).listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
            val tracks = statusTrackMap(appCtx)
            statusMap(appCtx).forEach { (k, s) ->
                if (s.state == ST_QUEUED || s.state == ST_DOWNLOADING) {
                    tracks[k]?.let { start(appCtx, it, quality) }
                }
            }
        }
    }

    private const val PREFS = "arzxsh_downloads"

    @Volatile
    private var cache: List<Entry>? = null

    /** Bumped on every index write (0.6.3, audit S6): lets
     *  navigation fingerprints be O(1) instead of stat-ing every
     *  downloaded file on every tab switch / screen render. */
    @Volatile
    private var indexVersion = 0

    fun key(track: Track): String = "${track.source}_${track.id}"

    @Volatile
    private var dirCache: File? = null

    private fun dir(ctx: Context): File =
        dirCache ?: File(ctx.filesDir, "downloads").apply { mkdirs() }
            .also { dirCache = it }

    fun entries(ctx: Context): List<Entry> {
        cache?.let { return it }
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("items", null) ?: return emptyList()
        val parsed = try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    Track(
                        o.getString("id"), o.getString("title"), o.getString("artist"),
                        o.optInt("duration"),
                        o.optString("artworkSmall", null)?.takeIf { it.isNotEmpty() },
                        o.optString("artworkLarge", null)?.takeIf { it.isNotEmpty() },
                        o.optString("source", SOURCE_AUDIUS),
                        o.optString("artistId", null)?.takeIf { it.isNotEmpty() }
                    ),
                    o.getString("fileName")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        cache = parsed
        return parsed
    }

    fun localFile(ctx: Context, track: Track): File? {
        val entry = entries(ctx).firstOrNull { key(it.track) == key(track) } ?: return null
        val f = File(dir(ctx), entry.fileName)
        return f.takeIf { it.exists() && it.length() > 0 }
    }

    fun isDownloaded(ctx: Context, track: Track): Boolean = localFile(ctx, track) != null

    /** Downloaded track keys from the cached index — ZERO file
     *  stats (0.6.3, audit S6). List builders snapshot this once
     *  and test membership per row instead of running a linear
     *  scan + File.exists()/length() per row on the main thread.
     *  The index is the source of truth for row badges; files are
     *  verified lazily when actually played or downloaded. */
    fun downloadedKeys(ctx: Context): Set<String> =
        entries(ctx).mapTo(HashSet()) { key(it.track) }

    /** O(1) change fingerprint for navigation retention: entry
     *  count + a version that moves on every index write. */
    fun fingerprint(ctx: Context): Int = entries(ctx).size * 131 + indexVersion

    /** Total bytes the download store currently occupies on disk. */
    fun storageBytes(ctx: Context): Long =
        entries(ctx).sumOf { File(dir(ctx), it.fileName).length() }

    /** Deletes every downloaded file and clears the index. */
    fun removeAll(ctx: Context) {
        entries(ctx).forEach { File(dir(ctx), it.fileName).delete() }
        dir(ctx).listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("states").remove("state_tracks").apply()
        writeAll(ctx, emptyList())
    }

    /**
     * Downloads the track's audio into the store. Throws on failure.
     * [onProgress] receives 0–100 when the server reports a length
     * (invoked sparsely, on percent steps). [shouldContinue] is
     * polled during the transfer; returning false aborts with
     * [StoppedException] and removes the partial file (a paused
     * download restarts from zero on resume — the state label says
     * "Paused", it never pretends a resume offset exists).
     * Local tracks are their own file already and cannot be
     * "downloaded" — asking throws, honestly.
     */
    fun downloadSync(
        ctx: Context,
        track: Track,
        quality: String = "normal",
        shouldContinue: () -> Boolean = { true },
        onProgress: (Int) -> Unit = {}
    ): Entry {
        var streamUa: String? = null
        val url = when (track.source) {
            SOURCE_YOUTUBE -> {
                val resolved = StreamResolver.resolveYoutube(track.id, quality)
                // googlevideo binds the URL to the requesting client:
                // the download fetch must send the producing context's
                // UA, exactly like playback (v5 sent none at all).
                streamUa = resolved.userAgent
                resolved.url ?: throw RuntimeException("stream resolution failed: ${resolved.report}")
            }
            SOURCE_LOCAL -> throw RuntimeException("already on this device")
            else -> "https://api.audius.co/v1/tracks/${track.id}/stream?app_name=arzxsh"
        }
        val ext = url.substringBefore("?").substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("m4a", "mp3", "opus", "webm", "aac", "ogg") } ?: "m4a"
        val fileName = key(track).replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + ext
        val tmp = File(dir(ctx), "$fileName.part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            streamUa?.let { setRequestProperty("User-Agent", it) }
        }
        try {
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        if (!shouldContinue()) throw StoppedException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt().coerceIn(0, 99)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        if (tmp.length() == 0L) {
            tmp.delete()
            throw RuntimeException("empty download")
        }
        onProgress(100)
        val dest = File(dir(ctx), fileName)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        val entry = Entry(track, fileName)
        writeAll(ctx, entries(ctx).filter { key(it.track) != key(track) } + entry)
        clearStatus(ctx, key(track))
        return entry
    }

    fun remove(ctx: Context, track: Track) {
        val all = entries(ctx)
        val entry = all.firstOrNull { key(it.track) == key(track) } ?: return
        File(dir(ctx), entry.fileName).delete()
        clearStatus(ctx, key(track))
        writeAll(ctx, all.filter { key(it.track) != key(track) })
    }

    // ------------------------------------------------------------ vault states

    private fun statusMap(ctx: Context): LinkedHashMap<String, Status> {
        val out = LinkedHashMap<String, Status>()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("states", null) ?: return out
        try {
            val o = JSONObject(raw)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val s = o.getJSONObject(k)
                out[k] = Status(
                    s.getString("state"),
                    s.optInt("progress"),
                    s.optString("error", null)?.takeIf { it.isNotEmpty() }
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Track metadata for state rows, so the Downloads tab can
     *  render a failed/paused track without the file index. */
    private fun statusTrackMap(ctx: Context): Map<String, Track> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("state_tracks", null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = HashMap<String, Track>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                trackFromJson(o.getJSONObject(k))?.let { out[k] = it }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setStatus(ctx: Context, track: Track, state: String, progress: Int = 0, error: String? = null) {
        val map = statusMap(ctx)
        map[key(track)] = Status(state, progress, error)
        val tracks = statusTrackMap(ctx).toMutableMap()
        tracks[key(track)] = track
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject()
        map.forEach { (k, s) ->
            o.put(k, JSONObject().apply {
                put("state", s.state)
                put("progress", s.progress)
                put("error", s.error ?: JSONObject.NULL)
            })
        }
        val to = JSONObject()
        tracks.forEach { (k, t) -> to.put(k, trackToJson(t)) }
        prefs.edit()
            .putString("states", o.toString())
            .putString("state_tracks", to.toString())
            .apply()
    }

    fun clearStatus(ctx: Context, key: String) {
        val map = statusMap(ctx)
        if (map.remove(key) == null) return
        val tracks = statusTrackMap(ctx).toMutableMap()
        tracks.remove(key)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject()
        map.forEach { (k, s) ->
            o.put(k, JSONObject().apply {
                put("state", s.state)
                put("progress", s.progress)
                put("error", s.error ?: JSONObject.NULL)
            })
        }
        val to = JSONObject()
        tracks.forEach { (k, t) -> to.put(k, trackToJson(t)) }
        prefs.edit()
            .putString("states", o.toString())
            .putString("state_tracks", to.toString())
            .apply()
    }

    /** Status for a track not in the downloaded index, else null. */
    fun statusOf(ctx: Context, track: Track): Status? =
        statusMap(ctx)[key(track)]

    /** All non-downloaded vault rows: (track, status), failed first. */
    fun pendingRows(ctx: Context): List<Pair<Track, Status>> {
        val tracks = statusTrackMap(ctx)
        val downloaded = entries(ctx).map { key(it.track) }.toHashSet()
        return statusMap(ctx).entries
            .filter { it.key !in downloaded }
            .mapNotNull { (k, s) -> tracks[k]?.let { it to s } }
            .sortedBy {
                when (it.second.state) {
                    ST_FAILED -> 0
                    ST_DOWNLOADING -> 1
                    ST_QUEUED -> 2
                    else -> 3
                }
            }
    }

    private fun writeAll(ctx: Context, list: List<Entry>) {
        cache = list
        indexVersion++
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.track.id)
                put("title", e.track.title)
                put("artist", e.track.artist)
                put("duration", e.track.duration)
                put("source", e.track.source)
                put("artistId", e.track.artistId ?: JSONObject.NULL)
                put("artworkSmall", e.track.artworkSmall ?: JSONObject.NULL)
                put("artworkLarge", e.track.artworkLarge ?: JSONObject.NULL)
                put("fileName", e.fileName)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("items", arr.toString()).apply()
    }
}
