package com.arzx.music

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal [Downloader] for NewPipeExtractor backed by [HttpURLConnection],
 * so the YouTube source needs no extra HTTP client dependency. The
 * extractor supplies all request headers (including Accept-Language);
 * this class only has to move bytes and report the final URL after
 * redirects. Gzip bodies are decoded here because HttpURLConnection
 * does not do it transparently.
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
            values.forEach { value -> conn.addRequestProperty(name, value) }
        }
        val data = request.dataToSend()
        if (data != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(data) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        var body = ""
        if (stream != null) {
            val decoded = if ("gzip".equals(conn.getHeaderField("Content-Encoding"), ignoreCase = true)) {
                GZIPInputStream(stream)
            } else {
                stream
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

/**
 * On-device YouTube Music source built on NewPipeExtractor. All calls do
 * network work and MUST be made from a background thread (the activity's
 * executor). Search returns [Track]s with [Track.source] set to
 * [SOURCE_YOUTUBE] whose id is the YouTube video id; playback resolves a
 * direct audio stream URL on-device, so no backend is involved.
 */
object YoutubeSource {
    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(HttpDownloader())
                initialized = true
            }
        }
    }

    /** YouTube Music song search, mapped onto the shared [Track] model. */
    fun searchSongs(query: String, limit: Int = 20): List<Track> {
        ensureInit()
        val service = ServiceList.YouTube
        val queryHandler = service.searchQHFactory.fromQuery(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            ""
        )
        val extractor = service.getSearchExtractor(queryHandler)
        extractor.fetchPage()
        val out = ArrayList<Track>()
        for (item in extractor.initialPage.items) {
            if (item !is StreamInfoItem) continue
            val videoId = try {
                service.streamLHFactory.fromUrl(item.url).id
            } catch (_: Exception) {
                continue
            }
            val thumbs = item.thumbnails
            val large = thumbs.maxByOrNull { it.height * it.width }
            val small = thumbs.filter { it.height >= 100 }.minByOrNull { it.height * it.width } ?: large
            out.add(
                Track(
                    id = videoId,
                    title = item.name,
                    artist = item.uploaderName,
                    duration = item.duration.toInt().coerceAtLeast(0),
                    artworkSmall = small?.url,
                    artworkLarge = large?.url,
                    source = SOURCE_YOUTUBE
                )
            )
            if (out.size >= limit) break
        }
        return out
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
