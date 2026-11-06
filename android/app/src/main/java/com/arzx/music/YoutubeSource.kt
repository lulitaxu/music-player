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
                val buf = ByteArrayOutputStream()
                s.copyTo(buf)
                buf.toString(Charsets.UTF_8.name())
            }
        }
        val headers = HashMap<String, List<String>>()
        conn.headerFields.forEach { (name, values) -> if (name != null) headers[name] = values }
        return Response(code, conn.responseMessage ?: "", headers, body, conn.url.toString())
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
     * Resolves the best direct audio stream URL for a video id: AAC/m4a
     * first (the platform player's most reliable format), then the
     * highest-bitrate progressive stream. Null when nothing playable is
     * exposed; throws on extraction/network failure.
     */
    fun resolveAudioUrl(videoId: String): String? {
        ensureInit()
        val extractor = ServiceList.YouTube.getStreamExtractor(
            "https://www.youtube.com/watch?v=$videoId"
        )
        extractor.fetchPage()
        val streams = extractor.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        if (streams.isEmpty()) return null
        val best = streams.filter { it.format?.suffix.equals("m4a", ignoreCase = true) }
            .maxByOrNull { it.averageBitrate }
            ?: streams.maxByOrNull { it.averageBitrate }
        return best?.content?.takeIf { it.isNotEmpty() }
    }
}
