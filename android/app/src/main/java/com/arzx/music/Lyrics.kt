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
 * Lyrics engine with a provider fallback chain (v5.1 cascade):
 *
 *   LRCLIB /api/get on NORMALIZED title+artist+duration
 *   → LRCLIB /api/search (normalized; candidate must pass a title-
 *     similarity gate, then synced > similarity > closest duration)
 *   → LRCLIB /api/search on the RAW title (last LRCLIB attempt)
 *   → lyrics.ovh (plain only; normalized, then raw)
 *   → null (the UI says "Lyrics unavailable", honestly — and prints
 *     [lastReport], the per-provider outcomes of the last fetch, so
 *     a failure screenshot names the layer that failed)
 *
 * Normalization exists because YouTube Music metadata defeats exact
 * matching: titles carry "(Official Video)" / "[Official Audio]" /
 * "(Lyrics)" suffixes and featuring credits, artists arrive as
 * "Sia - Topic". /api/get never sends album_name: YouTube tracks
 * routinely lack album metadata and an empty required param would
 * kill the match.
 *
 * Contracts verified live from the sandbox on 2026-11-06:
 * LRCLIB returns {trackName, artistName, duration, instrumental,
 * plainLyrics, syncedLyrics}; /api/get answers 404
 * {"name":"TrackNotFound"} on a miss. lyrics.ovh answers
 * {"lyrics": "..."} or an error object.
 *
 * Every request carries an identifying User-Agent (LRCLIB's API
 * etiquette: anonymous/default agents get throttled or refused).
 *
 * Positive results are cached per track under filesDir/lyrics;
 * misses are cached for 7 days so re-opening a lyrics-less track
 * doesn't re-hammer the providers. Cache entries are versioned —
 * v5's pre-normalization misses do not suppress the new cascade.
 * All functions do network/disk work: background thread only,
 * except [cacheBytes]/[clearCache].
 */
object Lyrics {
    private const val UA = "arzxsh/0.6.0 (personal music player; github.com/lulitaxu/music-player)"
    private const val MISS_TTL_MS = 7L * 24 * 3600 * 1000

    /** A miss caused by every provider THROWING (offline, DNS,
     *  provider down) is not knowledge — it expires in an hour
     *  instead of suppressing retries for a week (0.6.13). */
    private const val FAIL_MISS_TTL_MS = 3600 * 1000L

    /** Positive results expire too (0.6.13): a wrong search-gate
     *  match used to be permanent until a manual cache clear. */
    private const val HIT_TTL_MS = 90L * 24 * 3600 * 1000
    private const val MAX_CACHE_FILES = 1000
    private const val CACHE_VER = 2

    /** Per-provider outcomes of the most recent [fetch], for the
     *  lyrics screen's printed diagnosis. */
    @Volatile
    var lastReport: String = ""
        private set

    private fun dir(ctx: Context): File = File(ctx.filesDir, "lyrics").apply { mkdirs() }

    private fun cacheFile(ctx: Context, track: Track): File =
        File(dir(ctx), Downloads.key(track).replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun cacheBytes(ctx: Context): Long =
        dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Cache read: a stored result, a still-valid miss (null data,
     *  [miss] true), or no cache entry at all ([miss] false).
     *  Entries from older cache versions are ignored entirely. */
    private data class Cached(val data: LyricsData?, val miss: Boolean, val report: String? = null)

    private fun readCache(ctx: Context, track: Track): Cached? {
        val f = cacheFile(ctx, track)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            if (o.optInt("ver") != CACHE_VER) return null
            val rep = o.optString("rep", "").takeIf { it.isNotEmpty() }
            if (o.optBoolean("miss")) {
                val ttl = if (o.optBoolean("failMiss")) FAIL_MISS_TTL_MS else MISS_TTL_MS
                val fresh = System.currentTimeMillis() - o.optLong("ts") < ttl
                if (fresh) Cached(null, miss = true, report = rep) else null
            } else if (System.currentTimeMillis() - o.optLong("ts") > HIT_TTL_MS) {
                // Stale hit (pre-0.6.13 entries carry no ts and
                // read as epoch 0 — they expire here too, once).
                null
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
                    miss = false,
                    report = rep
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(
        ctx: Context,
        track: Track,
        data: LyricsData?,
        report: String = "",
        failMiss: Boolean = false
    ) {
        try {
            val o = JSONObject()
            o.put("ver", CACHE_VER)
            o.put("ts", System.currentTimeMillis())
            if (report.isNotEmpty()) o.put("rep", report)
            if (data == null) {
                o.put("miss", true)
                if (failMiss) o.put("failMiss", true)
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
            // Atomic publish (0.6.13): fetch now also runs from
            // download completion, concurrently with the lyrics
            // screen — a bare writeText could interleave two
            // writers into unparseable JSON. Temp + rename.
            val dest = cacheFile(ctx, track)
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            evictCacheOverflow(ctx, keep = dest)
        } catch (_: Exception) {
            // Cache failure must never break lyrics display.
        }
    }

    /** Bounds the cache directory (0.6.13): one file per track
     *  ever fetched grew without limit. Oldest files beyond
     *  [MAX_CACHE_FILES] are evicted on write. */
    private fun evictCacheOverflow(ctx: Context, keep: File) {
        try {
            val files = dir(ctx).listFiles() ?: return
            if (files.size <= MAX_CACHE_FILES) return
            files.filter { it != keep }
                .sortedBy { it.lastModified() }
                .take(files.size - MAX_CACHE_FILES)
                .forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /** Fetches lyrics through the provider chain (cache first).
     *  Every step appends its outcome to the fetch's notes; the
     *  joined notes become [lastReport] either way, so a total
     *  failure still explains itself on screen. */
    fun fetch(ctx: Context, track: Track): LyricsData? {
        readCache(ctx, track)?.let { cached ->
            lastReport = cached.report
                ?: if (cached.miss) "Providers had nothing when last asked (cached)"
                else "From the on-device lyrics cache"
            return cached.data
        }
        val notes = ArrayList<String>()
        val cleanTitle = normalizeTitle(track.title)
        val cleanArtist = normalizeArtist(track.artist)
        val clean = Track(
            track.id, cleanTitle, cleanArtist, track.duration,
            track.artworkSmall, track.artworkLarge, track.source, track.artistId
        )
        val changed = cleanTitle != track.title || cleanArtist != track.artist
        val result = lrclibGet(clean, notes)
            ?: lrclibSearch(cleanTitle, cleanArtist, track.duration, notes, "normalized")
            ?: lrclibSearch(track.title, null, track.duration, notes, "raw title")
            ?: lyricsOvh(cleanTitle, cleanArtist, notes)
            ?: if (changed) lyricsOvh(track.title, track.artist, notes) else null
        lastReport = notes.joinToString(" · ")
        // A miss only earns the long TTL when at least one
        // provider actually ANSWERED (an HTTP status came back).
        // If every attempt threw — offline, DNS, providers down —
        // the miss is a failure, cached for an hour, not a week.
        val anyResponse = notes.any {
            it.contains("HTTP ") || Regex(""": 200\b""").containsMatchIn(it)
        }
        writeCache(ctx, track, result, lastReport, failMiss = result == null && !anyResponse)
        return result
    }

    // ------------------------------------------------------------ metadata normalization

    private val bracketSegment = Regex("""\s*[\(\[][^\(\)\[\]]*[\)\]]""")
    private val featSegment = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)

    /** Title as LRCLIB knows it: bracketed/parenthesized segments
     *  removed ("(Official Video)", "[Official Audio]", "(Lyrics)",
     *  "(Visualizer)", "(Remastered)"), featuring credits cut,
     *  whitespace collapsed. A trailing "- Remaster" style dash tag
     *  is NOT cut: plenty of real titles contain dashes and LRCLIB
     *  indexes editions under their own names. */
    fun normalizeTitle(raw: String): String {
        var t = raw.replace(bracketSegment, " ")
        t = t.replace(featSegment, " ")
        return t.replace(Regex("""\s+"""), " ").trim().trim('-').trim()
    }

    /** Artist as LRCLIB knows it: YouTube's auto-generated channels
     *  append " - Topic"; bracketed segments go too. */
    fun normalizeArtist(raw: String): String {
        var a = raw.replace(bracketSegment, " ")
        a = a.replace(Regex("""\s+-\s+Topic\s*$""", RegexOption.IGNORE_CASE), " ")
        return a.replace(Regex("""\s+"""), " ").trim()
    }

    /** Comparison form for title similarity: lowercase alphanumerics
     *  and single spaces, nothing else. */
    private fun compareForm(s: String): String =
        s.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    /** 0..100 similarity between two titles: exact 100, containment
     *  82, otherwise token overlap (Jaccard) scaled to 70. */
    private fun titleSimilarity(a: String, b: String): Int {
        val ca = compareForm(a)
        val cb = compareForm(b)
        if (ca.isEmpty() || cb.isEmpty()) return 0
        if (ca == cb) return 100
        if (ca.contains(cb) || cb.contains(ca)) return 82
        val ta = ca.split(' ').toSet()
        val tb = cb.split(' ').toSet()
        val union = ta.union(tb).size
        if (union == 0) return 0
        return (ta.intersect(tb).size * 70) / union
    }

    private fun shortOutcome(e: Exception): String {
        val msg = e.message?.replace('\n', ' ')?.trim().orEmpty()
        return e.javaClass.simpleName + if (msg.isEmpty()) "" else ": ${msg.take(60)}"
    }

    // ------------------------------------------------------------ providers

    private fun get(url: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            // 0.6.13: capped read — the old readText() buffered
            // an unbounded response (search payloads are partly
            // attacker-influenceable via track metadata).
            // Overflow throws and counts as a provider failure.
            val text = stream?.bufferedReader()?.use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.length > 2 * 1024 * 1024) {
                        throw java.io.IOException("lyrics response too large")
                    }
                }
                sb.toString()
            } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
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

    private fun lrclibGet(track: Track, notes: MutableList<String>): LyricsData? = try {
        val sb = StringBuilder("https://lrclib.net/api/get?track_name=")
        sb.append(enc(track.title)).append("&artist_name=").append(enc(track.artist))
        if (track.duration > 0) sb.append("&duration=").append(track.duration)
        // Deliberately no album_name: YouTube tracks routinely lack
        // album metadata, and an empty required param kills the match.
        val (code, text) = get(sb.toString())
        when {
            code == 200 -> {
                val data = fromLrclibRecord(JSONObject(text))
                notes += if (data != null) "LRCLIB get: 200" else "LRCLIB get: 200, record had no lyrics"
                data
            }
            else -> {
                notes += "LRCLIB get: HTTP $code"
                null
            }
        }
    } catch (e: Exception) {
        notes += "LRCLIB get: ${shortOutcome(e)}"
        null
    }

    /** Candidate picking with the title gate: a record must BE this
     *  song (similarity >= 45) before duration may steer the choice.
     *  YouTube durations differ from LRCLIB's by a few seconds, so
     *  duration is a preference, never an exact-match requirement. */
    private fun lrclibSearch(
        title: String,
        artist: String?,
        durationSec: Int,
        notes: MutableList<String>,
        tag: String
    ): LyricsData? {
        return try {
        val sb = StringBuilder("https://lrclib.net/api/search?track_name=")
        sb.append(enc(title))
        if (!artist.isNullOrEmpty()) sb.append("&artist_name=").append(enc(artist))
        val (code, text) = get(sb.toString())
        if (code != 200) {
            notes += "LRCLIB search ($tag): HTTP $code"
            null
        } else {
            val arr = JSONArray(text)
            var considered = 0
            var bestSim = 0
            var best: Pair<LyricsData, Double>? = null
            // v6 sync fix: among SYNCED candidates the winner is the
            // one whose duration is CLOSEST to the track's real
            // duration — a synced file for a different edit of the
            // song (radio edit, extended mix) drifts against the
            // audio no matter how good its title match is. The old
            // flat +5000 "has sync" bonus let a high-similarity but
            // wrong-duration candidate beat the right one.
            var bestSynced: LyricsData? = null
            var bestSyncedDiff = Double.MAX_VALUE
            var bestSyncedSim = -1
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val data = fromLrclibRecord(item) ?: continue
                val sim = titleSimilarity(title, item.optString("trackName", ""))
                if (sim > bestSim) bestSim = sim
                if (sim < 45) continue
                considered++
                val durDiff = if (durationSec > 0) {
                    kotlin.math.abs(item.optDouble("duration", 0.0) - durationSec)
                        .coerceAtMost(120.0)
                } else {
                    30.0
                }
                if (data.synced != null &&
                    (durDiff < bestSyncedDiff ||
                        (durDiff == bestSyncedDiff && sim > bestSyncedSim))
                ) {
                    bestSynced = data
                    bestSyncedDiff = durDiff
                    bestSyncedSim = sim
                }
                val score = sim * 100.0 - durDiff * 15.0 +
                    (if (data.synced != null) 5000.0 else 0.0)
                if (best == null || score > best!!.second) best = data to score
            }
            if (bestSynced != null) {
                notes += "LRCLIB search ($tag): 200, ${arr.length()} candidates, " +
                    "synced pick by closest duration (off by ${bestSyncedDiff.toInt()}s)"
                return bestSynced
            }
            if (best == null) {
                notes += "LRCLIB search ($tag): 200, ${arr.length()} candidates, " +
                    "best title match $bestSim%${if (considered == 0) " (rejected)" else ""}"
                null
            } else {
                notes += "LRCLIB search ($tag): 200, ${arr.length()} candidates, accepted"
                best!!.first
            }
        }
        } catch (e: Exception) {
            notes += "LRCLIB search ($tag): ${shortOutcome(e)}"
            null
        }
    }

    private fun lyricsOvh(title: String, artist: String, notes: MutableList<String>): LyricsData? = try {
        if (artist.isBlank()) {
            notes += "lyrics.ovh: skipped (no artist)"
            null
        } else {
            // Path segments, not a query string: the form
            // encoder's "+" for spaces is NOT decoded as a
            // space in a path, so multi-word artists/titles
            // 404'd here (0.6.13). Query-string uses of enc()
            // elsewhere are correct as they are.
            val url = "https://api.lyrics.ovh/v1/" +
                enc(artist).replace("+", "%20") + "/" + enc(title).replace("+", "%20")
            val (code, text) = get(url)
            if (code != 200) {
                notes += "lyrics.ovh: HTTP $code"
                null
            } else {
                val plain = JSONObject(text).optString("lyrics", "").takeIf { it.isNotBlank() }
                if (plain == null) {
                    notes += "lyrics.ovh: 200, empty body"
                    null
                } else {
                    LyricsData(null, plain.lines().map { l -> l.trimEnd() }, "lyrics.ovh")
                }
            }
        }
    } catch (e: Exception) {
        notes += "lyrics.ovh: ${shortOutcome(e)}"
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
