package com.arzx.music

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * [Downloader] for NewPipeExtractor backed by [HttpURLConnection], so the
 * YouTube source needs no extra HTTP client dependency.
 *
 * Encoding is handled deliberately: the extractor sets its own
 * Accept-Encoding, which disables HttpURLConnection's transparent
 * decompression, so this class forces "Accept-Encoding: gzip" and decodes
 * gzip/deflate itself. (v3 passed the extractor's headers through and only
 * decoded gzip; any other content encoding turned the JSON body into
 * garbage and the whole source silently returned nothing.)
 */
private class HttpDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
            requestMethod = request.httpMethod()
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        request.headers().forEach { (name, values) ->
            if (name.equals("Accept-Encoding", ignoreCase = true)) return@forEach
            values.forEach { value -> conn.addRequestProperty(name, value) }
        }
        conn.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val data = request.dataToSend()
            if (data != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(data) }
            }
            val code = conn.responseCode
            val raw: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            var body = ""
            if (raw != null) {
                val encoding = conn.getHeaderField("Content-Encoding") ?: ""
                val decoded: InputStream = when {
                    encoding.contains("gzip", ignoreCase = true) -> GZIPInputStream(raw)
                    encoding.contains("deflate", ignoreCase = true) -> InflaterInputStream(raw)
                    else -> raw
                }
                body = decoded.use { s ->
                    // 0.6.13 audit fix: a response body is
                    // buffered whole, so it is CAPPED — a hostile
                    // or broken endpoint must not be able to grow
                    // memory until the process dies.
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = s.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BODY_BYTES) {
                            throw java.io.IOException("response body too large")
                        }
                        buf.write(chunk, 0, n)
                    }
                    buf.toString(Charsets.UTF_8.name())
                }
            }
            val headers = HashMap<String, List<String>>()
            conn.headerFields.forEach { (name, values) -> if (name != null) headers[name] = values }
            return Response(code, conn.responseMessage ?: "", headers, body, conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 16 * 1024 * 1024
    }
}

/** Outcome of a YouTube song search: tracks plus a diagnostic note. */
data class SongSearchResult(
    val tracks: List<Track>,
    /** Set when something noteworthy happened (fallback used, partial
     *  failure, or an empty page); the UI shows it instead of guessing. */
    val note: String? = null
)

/**
 * On-device YouTube Music source built on NewPipeExtractor. All calls do
 * network work and MUST be made from a background thread (the activity's
 * executor). Search returns [Track]s with [Track.source] set to
 * [SOURCE_YOUTUBE] whose id is the YouTube video id; playback resolves a
 * direct audio stream URL on-device, so no backend is involved.
 *
 * Failure policy: nothing is swallowed. A search that fails on every
 * filter throws the last exception; a search that succeeds on a fallback
 * or returns an empty page says so in [SongSearchResult.note].
 */
object YoutubeSource {
    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(HttpDownloader(), Localization("en", "US"), ContentCountry("US"))
                initialized = true
            }
        }
    }

    private fun shortError(t: Throwable): String {
        val msg = t.message?.replace('\n', ' ')?.trim().orEmpty()
        val cls = t.javaClass.simpleName
        return if (msg.isEmpty()) cls else "$cls: ${msg.take(140)}"
    }

    /** One raw search page for [filter]: its items, unfiltered by type. */
    private fun fetchItems(query: String, filter: String): List<InfoItem> {
        val service = ServiceList.YouTube
        val queryHandler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
        val extractor = service.getSearchExtractor(queryHandler)
        extractor.fetchPage()
        return extractor.initialPage.items
    }

    private fun mapSongs(items: List<InfoItem>, limit: Int): List<Track> {
        val service = ServiceList.YouTube
        val out = ArrayList<Track>()
        for (item in items) {
            if (item !is StreamInfoItem) continue
            val videoId = try {
                service.streamLHFactory.fromUrl(item.url).id
            } catch (_: Exception) {
                continue
            }
            if (videoId.isNullOrEmpty()) continue
            val thumbs = item.thumbnails
            val large = thumbs.maxByOrNull { it.height * it.width }
            val small = thumbs.filter { it.height >= 100 }.minByOrNull { it.height * it.width } ?: large
            val artistId = item.uploaderUrl?.let { u ->
                Regex("channel/(UC[0-9A-Za-z_-]+)").find(u)?.groupValues?.getOrNull(1)
            }
            out.add(
                Track(
                    id = videoId,
                    title = item.name,
                    artist = item.uploaderName,
                    duration = item.duration.toInt().coerceAtLeast(0),
                    artworkSmall = small?.url,
                    artworkLarge = large?.url,
                    source = SOURCE_YOUTUBE,
                    artistId = artistId
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * YouTube Music song search. Tries the music-songs filter first; if
     * that throws or yields no playable songs, falls back to the
     * unfiltered search (still mapped to song items only). Throws only
     * when every attempt failed.
     */
    fun searchSongs(query: String, limit: Int = 20): SongSearchResult {
        ensureInit()
        var note: String? = null
        val musicItems = try {
            fetchItems(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
        } catch (t: Throwable) {
            note = "music-filter search failed (${shortError(t)})"
            null
        }
        if (musicItems != null) {
            val songs = mapSongs(musicItems, limit)
            if (songs.isNotEmpty()) return SongSearchResult(songs)
            note = "music-filter search returned ${musicItems.size} items, none playable"
        }
        val allItems = try {
            fetchItems(query, YoutubeSearchQueryHandlerFactory.ALL)
        } catch (t: Throwable) {
            val combined = listOfNotNull(note, "all-results search failed (${shortError(t)})")
            throw RuntimeException(combined.joinToString("; "))
        }
        val songs = mapSongs(allItems, limit)
        if (songs.isEmpty()) {
            note = listOfNotNull(
                note,
                "YouTube returned no playable songs (${allItems.size} items on page)"
            ).joinToString("; ")
        }
        return SongSearchResult(songs, note)
    }

    /**
     * Resolves a direct audio stream URL for a video id from
     * NewPipeExtractor's progressive streams, steered by [quality]
     * ("data" = lowest bitrate, "normal" = best AAC at or under
     * ~192kbps, "high" = best available). Null when nothing directly
     * playable is exposed — note YouTube serves most audio as DASH,
     * which this filter excludes on purpose because the URL goes
     * straight to the player as a plain file; that scarcity is
     * exactly why the Innertube resolver in [StreamResolver] runs
     * first. Throws on extraction/network failure.
     */
    fun resolveAudioUrl(videoId: String, quality: String = "high"): String? {
        // 0.6.13 audit fix: the id is interpolated into the watch
        // URL — anything that is not shaped like a video id fails
        // fast instead of smuggling path/query into the request.
        if (!Regex("^[A-Za-z0-9_-]{6,20}$").matches(videoId)) {
            throw IllegalArgumentException("invalid video id")
        }
        ensureInit()
        val extractor = ServiceList.YouTube.getStreamExtractor(
            "https://www.youtube.com/watch?v=$videoId"
        )
        extractor.fetchPage()
        val streams = extractor.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        if (streams.isEmpty()) return null
        val m4a = streams.filter { it.format?.suffix.equals("m4a", ignoreCase = true) }
        val best = when (quality) {
            "data" -> streams.minByOrNull { it.averageBitrate }
            "normal" -> m4a.filter { it.averageBitrate <= 192_000 }.maxByOrNull { it.averageBitrate }
                ?: streams.minByOrNull { it.averageBitrate }
            else -> m4a.maxByOrNull { it.averageBitrate }
                ?: streams.maxByOrNull { it.averageBitrate }
        }
        // Only https stream URLs are ever handed to the player /
        // downloader (0.6.13): a cleartext or scheme-relative
        // answer is treated as no stream at all.
        return best?.content?.takeIf { it.startsWith("https://") }
    }
}

/**
 * The one YouTube stream-resolution path for playback AND downloads.
 * Primary: the app's own Innertube layer ([YtmApi.resolveStream]),
 * the same transport that already loads the catalog from the device —
 * the embedded ANDROID_MUSIC / IOS contexts expose direct audio file
 * URLs. Fallback: NewPipeExtractor. When both fail, [Resolved.report]
 * carries BOTH outcomes so the UI can print the real reason instead
 * of a generic failure. Background thread only.
 *
 * [Resolved.userAgent] is not decoration: googlevideo binds a stream
 * URL to the client that requested it, so whoever fetches the URL
 * (ExoPlayer for playback, HttpURLConnection for downloads) must
 * send the UA of the context that produced it, or the URL is
 * rejected — v5's bare-MediaPlayer -38 error was exactly that.
 */
object StreamResolver {
    data class Resolved(
        val url: String?,
        val report: String,
        val userAgent: String? = null,
        val clientLabel: String? = null
    )

    /** NewPipe extracts through the plain web client, so its stream
     *  URLs are paired with a desktop-browser UA. */
    const val LABEL_NEWPIPE = "NEWPIPE"
    private const val NEWPIPE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun resolveYoutube(videoId: String, quality: String): Resolved {
        val inner = try {
            YtmApi.resolveStream(videoId, quality)
        } catch (t: Throwable) {
            null
        }
        if (inner?.url != null) {
            return Resolved(inner.url, "Innertube ${inner.report}", inner.userAgent, inner.clientLabel)
        }
        val innerReport = inner?.report ?: "Innertube: resolver error"
        var npError: String? = null
        val npUrl = try {
            YoutubeSource.resolveAudioUrl(videoId, quality)
        } catch (t: Throwable) {
            npError = "${t.javaClass.simpleName}: ${t.message?.replace('\n', ' ')?.take(120) ?: ""}".trim()
            null
        }
        if (npUrl != null) return Resolved(npUrl, "NewPipe", NEWPIPE_UA, LABEL_NEWPIPE)
        val npReport = npError ?: "NewPipe: no direct progressive stream exposed"
        return Resolved(null, "Innertube: $innerReport · $npReport")
    }

    // ------------------------------------------------------------ playback ladder stages

    /**
     * Attempt order for live playback: the Innertube contexts in
     * [YtmApi.streamAttemptLabels] order (last-good first), NewPipe
     * as the final stage. The player drives one stage at a time so a
     * stream the player REJECTS can advance to the next context
     * instead of failing the track outright (v5.1 retry ladder).
     */
    fun youtubeStageLabels(): List<String> = YtmApi.streamAttemptLabels() + LABEL_NEWPIPE

    /** Resolves exactly ONE ladder stage. */
    fun resolveYoutubeStage(videoId: String, quality: String, label: String): Resolved {
        if (label == LABEL_NEWPIPE) {
            return try {
                val url = YoutubeSource.resolveAudioUrl(videoId, quality)
                if (url != null) Resolved(url, "NewPipe OK", NEWPIPE_UA, LABEL_NEWPIPE)
                else Resolved(null, "NewPipe: no direct progressive stream exposed")
            } catch (t: Throwable) {
                Resolved(
                    null,
                    "NewPipe: ${t.javaClass.simpleName}: ${t.message?.replace('\n', ' ')?.take(120) ?: ""}".trim()
                )
            }
        }
        val r = try {
            YtmApi.resolveStreamAttempt(videoId, quality, label)
        } catch (t: Throwable) {
            null
        }
        return if (r != null) Resolved(r.url, r.report, r.userAgent, r.clientLabel)
        else Resolved(null, "$label: resolver error")
    }

    // ------------------------------------------------------------ known-good session cache

    private data class CachedGood(val resolved: Resolved, val atMs: Long)

    private val goodCache = java.util.concurrent.ConcurrentHashMap<String, CachedGood>()

    /** A resolution stays reusable for a few minutes — long enough
     *  to make replays, repeat-one and queue returns instant, short
     *  enough to never serve a stale googlevideo URL for long. A
     *  player rejection evicts the entry immediately. */
    private const val GOOD_TTL_MS = 5 * 60_000L

    private fun goodKey(videoId: String, quality: String) = "$videoId|$quality"

    fun cachedGood(videoId: String, quality: String): Resolved? {
        val hit = goodCache[goodKey(videoId, quality)] ?: return null
        if (System.currentTimeMillis() - hit.atMs > GOOD_TTL_MS) {
            goodCache.remove(goodKey(videoId, quality))
            return null
        }
        return hit.resolved
    }

    fun rememberGood(videoId: String, quality: String, resolved: Resolved) {
        if (resolved.url == null) return
        // 0.6.13 audit fix: the cache was unbounded — over a
        // long session every resolved track+quality pair stayed
        // resident forever. Capped at ~64 entries, oldest first
        // out (the 5-minute TTL already makes old entries the
        // least valuable).
        if (goodCache.size >= 64 && !goodCache.containsKey(goodKey(videoId, quality))) {
            goodCache.entries.minByOrNull { it.value.atMs }?.let { goodCache.remove(it.key) }
        }
        goodCache[goodKey(videoId, quality)] = CachedGood(resolved, System.currentTimeMillis())
    }

    fun forgetGood(videoId: String, quality: String) {
        goodCache.remove(goodKey(videoId, quality))
    }
}
