package com.arzx.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

    private const val PREFS = "arzxsh_downloads"

    @Volatile
    private var cache: List<Entry>? = null

    fun key(track: Track): String = "${track.source}_${track.id}"

    private fun dir(ctx: Context): File = File(ctx.filesDir, "downloads").apply { mkdirs() }

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

    /**
     * Startup reconciliation: a state left at queued/downloading by
     * a dead process means the transfer was interrupted. Mark it
     * failed with the reason — never let it pose as in-progress or
     * (worse) downloaded.
     */
    fun reconcileOnStart(ctx: Context) {
        val tracks = statusTrackMap(ctx)
        statusMap(ctx).forEach { (k, s) ->
            if (s.state == ST_QUEUED || s.state == ST_DOWNLOADING) {
                tracks[k]?.let { t ->
                    setStatus(ctx, t, ST_FAILED, s.progress, "Interrupted before it finished")
                }
            }
        }
        // Orphaned partial files from a killed process.
        dir(ctx).listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
    }

    private fun writeAll(ctx: Context, list: List<Entry>) {
        cache = list
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
