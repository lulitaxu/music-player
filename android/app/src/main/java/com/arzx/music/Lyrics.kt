package com.arzx.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One line of synchronized (LRC) lyrics. */
data class LyriTimber(val timeMs: Long, val text: String)

/**
 * Lyrics for a track. [synced] is non-null only when a provider
 * returned real LRC timing — timing is NEVER fabricated. Plain
 * lyrics carry lines with no timing and the UI labels them
 * "not synchronized".
 */
data class LyricsData(
    val synced: List<LyriTimber>?,
    val plainLines: List<String>,
    val source: String,
    val instrumental: Boolean = false
)

/**
 * Lyrics engine with a provider fallback chain:
 *
 *   LRCLIB /api/get (exact title+artist+duration)
 *   → LRCLIB /api/search (best duration match)
 *   → lyrics.ovh (plain only)
 *   → null (the UI says "Lyrics unavailable", honestly)
 *
 * Contracts verified live from the sandbox on 2026-11-06:
 * LRCLIB returns {trackName, artistName, duration, instrumental,
 * plainLyrics, syncedLyrics}; /api/get answers 404
 * {"name":"TrackNotFound"} on a miss. lyrics.ovh answers
 * {"lyrics": "..."} or an error object.
 *
 * Positive results are cached per track under filesDir/lyrics;
 * misses are cached for 7 days so re-opening a lyrics-less track
 * doesn't re-hammer the providers. All functions do network/disk
 * work: background thread only, except [cacheBytes]/[clearCache].
 */
object Lyrics {
    private const val UA = "arzxsh/0.5.0 (personal music player; github.com/lulitaxu/music-player)"
    private const val MISS_TTL_MS = 7L * 24 * 3600 * 1000

    private fun dir(ctx: Context): File = File(ctx.filesDir, "lyrics").apply { mkdirs() }

    private fun cacheFile(ctx: Context, track: Track): File =
        File(dir(ctx), Downloads.key(track).replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun cacheBytes(ctx: Context): Long =
        dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Cache read: a stored result, a still-valid miss (null data,
     *  [miss] true), or no cache entry at all ([miss] false). */
    private data class Cached(val data: LyricsData?, val miss: Boolean)

    private fun readCache(ctx: Context, track: Track): Cached? {
        val f = cacheFile(ctx, track)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            if (o.optBoolean("miss")) {
                val fresh = System.currentTimeMillis() - o.optLong("ts") < MISS_TTL_MS
                if (fresh) Cached(null, miss = true) else null
            } else {
                val syncedArr = o.optJSONArray("synced")
                val synced = syncedArr?.let { arr ->
                    List(arr.length()) { i ->
                        val lo = arr.getJSONObject(i)
                        LyriTimber(lo.getLong("t"), lo.getString("x"))
                    }
                }
                val plainArr = o.optJSONArray("plain") ?: JSONArray()
                Cached(
                    LyricsData(
                        synced = synced,
                        plainLines = List(plainArr.length()) { plainArr.getString(it) },
                        source = o.optString("source", "LRCLIB"),
                        instrumental = o.optBoolean("instrumental")
                    ),
                    miss = false
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(ctx: Context, track: Track, data: LyricsData?) {
        try {
            val o = JSONObject()
            if (data == null) {
                o.put("miss", true)
                o.put("ts", System.currentTimeMillis())
            } else {
                o.put("source", data.source)
                o.put("instrumental", data.instrumental)
                data.synced?.let { lines ->
                    val arr = JSONArray()
                    lines.forEach { arr.put(JSONObject().put("t", it.timeMs).put("x", it.text)) }
                    o.put("synced", arr)
                }
                val plain = JSONArray()
                data.plainLines.forEach { plain.put(it) }
                o.put("plain", plain)
            }
            cacheFile(ctx, track).writeText(o.toString())
        } catch (_: Exception) {
            // Cache failure must never break lyrics display.
        }
    }

    /** Fetches lyrics through the provider chain (cache first). */
    fun fetch(ctx: Context, track: Track): LyricsData? {
        readCache(ctx, track)?.let { return it.data }
        val result = lrclibGet(track)
            ?: lrclibSearch(track)
            ?: lyricsOvh(track)
        writeCache(ctx, track, result)
        return result
    }

    // ------------------------------------------------------------ providers

    private fun get(url: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Builds a result from one LRCLIB record, or null when it
     *  carries no usable lyrics. Instrumental records are returned
     *  flagged (they're a real answer, not a miss). */
    private fun fromLrclibRecord(o: JSONObject): LyricsData? {
        val syncedText = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
        val plainText = o.optString("plainLyrics", "").takeIf { it.isNotBlank() }
        val instrumental = o.optBoolean("instrumental")
        if (syncedText == null && plainText == null) {
            return if (instrumental) {
                LyricsData(null, emptyList(), "LRCLIB", instrumental = true)
            } else null
        }
        val synced = syncedText?.let { parseLrc(it) }?.takeIf { it.isNotEmpty() }
        val plain = when {
            plainText != null -> plainText.lines().map { it.trimEnd() }
            synced != null -> synced.map { it.text }
            else -> emptyList()
        }
        return LyricsData(synced, plain, "LRCLIB", instrumental)
    }

    private fun lrclibGet(track: Track): LyricsData? = try {
        val sb = StringBuilder("https://lrclib.net/api/get?track_name=")
        sb.append(enc(track.title)).append("&artist_name=").append(enc(track.artist))
        if (track.duration > 0) sb.append("&duration=").append(track.duration)
        val (code, text) = get(sb.toString())
        if (code == 200) fromLrclibRecord(JSONObject(text)) else null
    } catch (_: Exception) {
        null
    }

    private fun lrclibSearch(track: Track): LyricsData? = try {
        val url = "https://lrclib.net/api/search?track_name=" + enc(track.title) +
            "&artist_name=" + enc(track.artist)
        val (code, text) = get(url)
        if (code != 200) {
            null
        } else {
            val arr = JSONArray(text)
            var best: Pair<JSONObject, Int>? = null // record, rank (lower = better)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val hasSynced = o.optString("syncedLyrics", "").isNotBlank()
                val hasPlain = o.optString("plainLyrics", "").isNotBlank()
                if (!hasSynced && !hasPlain && !o.optBoolean("instrumental")) continue
                val durDiff = if (track.duration > 0) {
                    kotlin.math.abs(o.optDouble("duration", 0.0) - track.duration).toInt()
                } else 0
                // Synced beats plain; then closest duration wins.
                val rank = (if (hasSynced) 0 else 100_000) + durDiff
                if (best == null || rank < best.second) best = o to rank
            }
            best?.let { fromLrclibRecord(it.first) }
        }
    } catch (_: Exception) {
        null
    }

    private fun lyricsOvh(track: Track): LyricsData? = try {
        if (track.artist.isBlank()) {
            null
        } else {
            val url = "https://api.lyrics.ovh/v1/" + enc(track.artist) + "/" + enc(track.title)
            val (code, text) = get(url)
            if (code != 200) {
                null
            } else {
                val plain = JSONObject(text).optString("lyrics", "").takeIf { it.isNotBlank() }
                plain?.let {
                    LyricsData(null, it.lines().map { l -> l.trimEnd() }, "lyrics.ovh")
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------ LRC parsing

    private val lrcTag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    /**
     * Parses LRC text into timed lines. A line may carry several
     * timestamps (repeats) — each becomes its own entry. Metadata
     * tags ([ti:], [ar:], [offset:]…) never match the numeric tag
     * pattern. Lines are sorted by time; empty-text entries are kept
     * (they mark instrumental gaps in real LRC files).
     */
    fun parseLrc(text: String): List<LyriTimber> {
        val out = ArrayList<LyriTimber>()
        for (raw in text.lineSequence()) {
            val tags = lrcTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val lyric = raw.replace(lrcTag, "").trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracRaw = m.groupValues[3]
                val fracMs = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                out.add(LyriTimber(min * 60_000 + sec * 1000 + fracMs, lyric))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
