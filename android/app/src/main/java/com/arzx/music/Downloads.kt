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
 * Audius via its stream URL, YouTube via the on-device resolved stream
 * URL ([YoutubeSource.resolveAudioUrl]). Downloaded files are an in-app
 * offline cache only; nothing is exported to shared storage.
 * All functions do disk/network work: background thread only, except
 * [entries]/[localFile]/[isDownloaded] which are cheap index reads.
 */
object Downloads {
    data class Entry(val track: Track, val fileName: String)

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

    /** Downloads the track's audio into the store. Throws on failure. */
    fun downloadSync(ctx: Context, track: Track): Entry {
        val url = if (track.source == SOURCE_YOUTUBE) {
            YoutubeSource.resolveAudioUrl(track.id)
                ?: throw RuntimeException("no playable stream exposed")
        } else {
            "https://api.audius.co/v1/tracks/${track.id}/stream?app_name=arzxsh"
        }
        val ext = url.substringBefore("?").substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("m4a", "mp3", "opus", "webm", "aac", "ogg") } ?: "m4a"
        val fileName = key(track).replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + ext
        val tmp = File(dir(ctx), "$fileName.part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        if (tmp.length() == 0L) {
            tmp.delete()
            throw RuntimeException("empty download")
        }
        val dest = File(dir(ctx), fileName)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        val entry = Entry(track, fileName)
        writeAll(ctx, entries(ctx).filter { key(it.track) != key(track) } + entry)
        return entry
    }

    fun remove(ctx: Context, track: Track) {
        val all = entries(ctx)
        val entry = all.firstOrNull { key(it.track) == key(track) } ?: return
        File(dir(ctx), entry.fileName).delete()
        writeAll(ctx, all.filter { key(it.track) != key(track) })
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
