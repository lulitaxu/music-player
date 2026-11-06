package com.arzx.music

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** An artist in YouTube Music search results, chart rows, related lists. */
data class YtmArtistRef(
    val id: String,
    val name: String,
    val meta: String? = null,
    val artworkSmall: String? = null,
    val artworkLarge: String? = null
)

/** An album or single in YouTube Music search results and artist pages. */
data class YtmAlbumRef(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val year: String? = null,
    val artworkSmall: String? = null,
    val artworkLarge: String? = null
)

data class YtmArtistPage(
    val id: String,
    val name: String,
    val subscribers: String? = null,
    val artworkSmall: String? = null,
    val artworkLarge: String? = null,
    val topSongs: List<Track> = emptyList(),
    val albums: List<YtmAlbumRef> = emptyList(),
    val singles: List<YtmAlbumRef> = emptyList(),
    val related: List<YtmArtistRef> = emptyList()
)

data class YtmAlbumPage(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val year: String? = null,
    val artworkLarge: String? = null,
    val tracks: List<Track> = emptyList()
)

data class YtmCharts(
    val songs: List<Track>,
    val artists: List<YtmArtistRef>
)

/**
 * YouTube Music catalog over the raw Innertube API (music.youtube.com
 * youtubei/v1), the same unauthenticated WEB_REMIX client shape
 * ytmusicapi uses. NewPipeExtractor does not model YTM artist pages,
 * albums or charts, so those are hand-rolled here; playback still goes
 * through [YoutubeSource]. Every function throws on transport/parse
 * failure — callers render the error, nothing fails silently.
 * All calls do network work: background thread only.
 */
object YtmApi {
    // The stock web-client key embedded in music.youtube.com and in
    // ytmusicapi's public source; not a user credential.
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val PARAMS_SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private const val PARAMS_ALBUMS = "EgWKAQIYAWoMEA4QChADEAQQCRAF"
    private const val PARAMS_ARTISTS = "EgWKAQIgAWoMEA4QChADEAQQCRAF"

    private val durationRe = Regex("""^\d{1,3}:\d{2}(:\d{2})?$""")
    private val yearRe = Regex("""^(19|20)\d{2}$""")

    @Volatile
    private var visitor: String? = null
    @Volatile
    private var visitorTried = false

    // ------------------------------------------------------------ transport

    private fun clientVersion(): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return "1." + fmt.format(Date()) + ".01.00"
    }

    private fun visitorId(): String? {
        if (visitorTried) return visitor
        synchronized(this) {
            if (visitorTried) return visitor
            visitorTried = true
            visitor = try {
                val conn = (URL("https://music.youtube.com/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }
                if (conn.responseCode !in 200..299) null
                else {
                    val html = readBody(conn, conn.inputStream)
                    Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
                }
            } catch (_: Throwable) {
                null
            }
        }
        return visitor
    }

    private fun readBody(conn: HttpURLConnection, raw: InputStream): String {
        val encoding = conn.getHeaderField("Content-Encoding") ?: ""
        val decoded: InputStream = when {
            encoding.contains("gzip", ignoreCase = true) -> GZIPInputStream(raw)
            encoding.contains("deflate", ignoreCase = true) -> InflaterInputStream(raw)
            else -> raw
        }
        return decoded.use { s ->
            val buf = ByteArrayOutputStream()
            s.copyTo(buf)
            buf.toString(Charsets.UTF_8.name())
        }
    }

    private fun post(endpoint: String, body: JSONObject): JSONObject {
        val context = JSONObject()
            .put(
                "client", JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", clientVersion())
                    .put("hl", "en")
                    .put("gl", "US")
            )
            .put("user", JSONObject())
        body.put("context", context)
        val conn = (URL("https://music.youtube.com/youtubei/v1/$endpoint?key=$API_KEY&alt=json")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            setRequestProperty("X-Goog-Api-Format-Version", "1")
            setRequestProperty("X-YouTube-Client-Name", "67")
            setRequestProperty("X-YouTube-Client-Version", clientVersion())
            visitorId()?.let { setRequestProperty("X-Goog-Visitor-Id", it) }
            setRequestProperty("Accept-Encoding", "gzip")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { readBody(conn, it) } ?: ""
        if (code !in 200..299) throw RuntimeException("Innertube $endpoint HTTP $code")
        return JSONObject(text)
    }

    // ------------------------------------------------------------ json helpers

    private fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)
    private fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)

    private fun dig(obj: JSONObject?, vararg keys: String): JSONObject? {
        var cur = obj
        for (k in keys) cur = cur?.obj(k) ?: return null
        return cur
    }

    /** Depth-first collection of every object stored under [key]. Used
     *  only for unique renderers (page headers, the radio panel). */
    private fun collectByKey(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val v = node.opt(key)
                if (v is JSONObject) out.add(v)
                val keys = node.keys()
                while (keys.hasNext()) collectByKey(node.opt(keys.next()), key, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectByKey(node.opt(i), key, out)
        }
    }

    private fun findByKey(root: JSONObject, key: String): JSONObject? {
        val out = ArrayList<JSONObject>()
        collectByKey(root, key, out)
        return out.firstOrNull()
    }

    private fun runsText(runs: JSONArray?): String {
        if (runs == null) return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
        }
        return sb.toString()
    }

    private fun textOf(obj: JSONObject?): String {
        if (obj == null) return ""
        obj.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        return runsText(obj.arr("runs"))
    }

    /** (small, large) artwork urls from a thumbnails array. */
    private fun thumbPair(arr: JSONArray?): Pair<String?, String?> {
        if (arr == null || arr.length() == 0) return null to null
        var large: String? = null
        var largeW = -1
        var small: String? = null
        var smallW = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val url = t.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val w = t.optInt("width")
            if (w > largeW) {
                largeW = w
                large = url
            }
            if (w >= 100 && w < smallW) {
                smallW = w
                small = url
            }
        }
        return (small ?: large) to large
    }

    private fun itemThumbs(item: JSONObject): Pair<String?, String?> {
        val fromThumbnail = dig(item, "thumbnail", "musicThumbnailRenderer", "thumbnail")?.arr("thumbnails")
        if (fromThumbnail != null) return thumbPair(fromThumbnail)
        val fromRenderer = dig(item, "thumbnailRenderer", "musicThumbnailRenderer", "thumbnail")?.arr("thumbnails")
        if (fromRenderer != null) return thumbPair(fromRenderer)
        val headerThumb = dig(item, "thumbnail", "musicThumbnailRenderer", "thumbnail")?.arr("thumbnails")
        return thumbPair(headerThumb)
    }

    private fun browseIdOf(endpoint: JSONObject?): String? =
        endpoint?.obj("browseEndpoint")?.optString("browseId")?.takeIf { it.isNotEmpty() }

    private fun watchIdOf(endpoint: JSONObject?): String? =
        endpoint?.obj("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotEmpty() }

    /** First watchEndpoint videoId anywhere under [node] (bounded DFS). */
    private fun deepWatchId(node: Any?, depth: Int = 0): String? {
        if (depth > 14) return null
        when (node) {
            is JSONObject -> {
                node.optJSONObject("watchEndpoint")?.optString("videoId")
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    deepWatchId(node.opt(keys.next()), depth + 1)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                deepWatchId(node.opt(i), depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun parseDuration(text: String): Int {
        val parts = text.trim().split(":")
        if (parts.size !in 2..3) return 0
        return try {
            parts.fold(0) { acc, p -> acc * 60 + p.trim().toInt() }
        } catch (_: Exception) {
            0
        }
    }

    /** Section entries of a tabbed results renderer, in document order. */
    private fun tabbedSections(response: JSONObject, rendererKey: String): List<JSONObject> {
        val tabs = dig(response, "contents", rendererKey)?.arr("tabs") ?: return emptyList()
        val content = tabs.optJSONObject(0)?.obj("tabRenderer")?.obj("content")
        val sections = content?.obj("sectionListRenderer")?.arr("contents") ?: return emptyList()
        return List(sections.length()) { sections.getJSONObject(it) }
    }

    /** Sections of a browse response (single-column, else two-column
     *  primary + secondary, in that order). */
    private fun browseSections(response: JSONObject): List<JSONObject> {
        val single = tabbedSections(response, "singleColumnBrowseResultsRenderer")
        if (single.isNotEmpty()) return single
        val out = ArrayList<JSONObject>()
        out.addAll(tabbedSections(response, "twoColumnBrowseResultsRenderer"))
        val secondary = dig(response, "contents", "twoColumnBrowseResultsRenderer", "secondaryContents")
            ?.obj("sectionListRenderer")?.arr("contents")
        if (secondary != null) {
            for (i in 0 until secondary.length()) secondary.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    private fun shelfItems(section: JSONObject): List<JSONObject> {
        val shelf = section.obj("musicShelfRenderer") ?: section.obj("musicPlaylistShelfRenderer")
        ?: return emptyList()
        val contents = shelf.arr("contents") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until contents.length()) {
            val item = contents.optJSONObject(i)?.obj("musicResponsiveListItemRenderer")
            if (item != null) out.add(item)
        }
        return out
    }

    // ------------------------------------------------------------ item parsing

    private class ListItemData(
        val title: String,
        val videoId: String?,
        val browseId: String?,
        val artistName: String?,
        val artistId: String?,
        val albumName: String?,
        val durationSec: Int,
        val year: String?,
        val metaTexts: List<String>,
        val artworkSmall: String?,
        val artworkLarge: String?
    ) {
        fun toTrack(fallbackArtist: String? = null, fallbackArtistId: String? = null): Track? {
            val vid = videoId ?: return null
            return Track(
                id = vid,
                title = title,
                artist = artistName ?: fallbackArtist ?: "",
                duration = durationSec,
                artworkSmall = artworkSmall,
                artworkLarge = artworkLarge,
                source = SOURCE_YOUTUBE,
                artistId = artistId ?: fallbackArtistId
            )
        }
    }

    /** Parses one musicResponsiveListItemRenderer (search rows, shelf
     *  rows, chart rows, album tracks). */
    private fun parseListItem(item: JSONObject): ListItemData? {
        val flex = item.arr("flexColumns") ?: return null
        fun colRuns(i: Int): JSONArray? =
            flex.optJSONObject(i)?.obj("musicResponsiveListItemFlexColumnRenderer")
                ?.obj("text")?.arr("runs")

        val titleRuns = colRuns(0) ?: return null
        val titleRun = titleRuns.optJSONObject(0) ?: return null
        val title = titleRun.optString("text").takeIf { it.isNotEmpty() } ?: return null

        var videoId = watchIdOf(titleRun.obj("navigationEndpoint"))
        if (videoId == null) {
            videoId = dig(
                item, "overlay", "musicItemThumbnailOverlayRenderer", "content",
                "musicPlayButtonRenderer", "playNavigationEndpoint"
            )?.let { watchIdOf(it) }
        }
        if (videoId == null) {
            videoId = item.obj("playlistItemData")?.optString("videoId")?.takeIf { it.isNotEmpty() }
        }
        if (videoId == null) videoId = deepWatchId(item)

        var browseId = browseIdOf(item.obj("navigationEndpoint"))
            ?: browseIdOf(titleRun.obj("navigationEndpoint"))

        var artistName: String? = null
        var artistId: String? = null
        var albumName: String? = null
        var durationSec = 0
        var year: String? = null
        val metaTexts = ArrayList<String>()
        val metaRuns = colRuns(1)
        if (metaRuns != null) {
            for (i in 0 until metaRuns.length()) {
                val run = metaRuns.optJSONObject(i) ?: continue
                val text = run.optString("text")
                if (text.isBlank() || text.trim() == "•") continue
                val bid = browseIdOf(run.obj("navigationEndpoint"))
                when {
                    bid != null && (bid.startsWith("UC") || bid.startsWith("MPLA")) -> {
                        artistName = text
                        artistId = bid
                    }
                    bid != null && bid.startsWith("MPRE") -> albumName = text
                    durationRe.matches(text.trim()) -> durationSec = parseDuration(text)
                    yearRe.matches(text.trim()) -> year = text.trim()
                    else -> metaTexts.add(text)
                }
                if (browseId == null && bid != null) browseId = bid
            }
        }
        if (durationSec == 0) {
            val fixed = item.arr("fixedColumns")?.optJSONObject(0)
                ?.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text")?.arr("runs")
            val fixedText = fixed?.optJSONObject(0)?.optString("text") ?: ""
            if (durationRe.matches(fixedText.trim())) durationSec = parseDuration(fixedText)
        }
        val (small, large) = itemThumbs(item)
        return ListItemData(
            title, videoId, browseId, artistName, artistId, albumName,
            durationSec, year, metaTexts, small, large
        )
    }

    /** Parses one musicTwoRowItemRenderer (carousel cards: albums,
     *  singles, related artists, chart playlists). */
    private class TwoRowData(
        val title: String,
        val browseId: String?,
        val year: String?,
        val subtitleText: String,
        val artworkSmall: String?,
        val artworkLarge: String?
    )

    private fun parseTwoRowItem(item: JSONObject): TwoRowData? {
        val titleRuns = item.obj("title")?.arr("runs") ?: return null
        val first = titleRuns.optJSONObject(0) ?: return null
        val title = first.optString("text").takeIf { it.isNotEmpty() } ?: return null
        val browseId = browseIdOf(first.obj("navigationEndpoint"))
            ?: browseIdOf(item.obj("navigationEndpoint"))
        var year: String? = null
        val subParts = ArrayList<String>()
        val subRuns = item.obj("subtitle")?.arr("runs")
        if (subRuns != null) {
            for (i in 0 until subRuns.length()) {
                val run = subRuns.optJSONObject(i) ?: continue
                val text = run.optString("text")
                if (text.isBlank() || text.trim() == "•") continue
                if (yearRe.matches(text.trim())) year = text.trim() else subParts.add(text)
            }
        }
        val (small, large) = itemThumbs(item)
        return TwoRowData(title, browseId, year, subParts.joinToString(" • "), small, large)
    }

    private fun carouselItems(section: JSONObject): List<JSONObject> {
        val carousel = section.obj("musicCarouselShelfRenderer") ?: return emptyList()
        val contents = carousel.arr("contents") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until contents.length()) contents.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun carouselTitle(section: JSONObject): String =
        dig(section, "musicCarouselShelfRenderer", "header", "musicCarouselShelfBasicHeaderRenderer", "title")
            ?.let { runsText(it.arr("runs")) } ?: ""

    // ------------------------------------------------------------ search

    private fun searchItems(query: String, params: String): List<ListItemData> {
        val body = JSONObject().put("query", query).put("params", params)
        val resp = post("search", body)
        val sections = tabbedSections(resp, "tabbedSearchResultsRenderer")
        for (section in sections) {
            val items = shelfItems(section)
            if (items.isNotEmpty()) {
                return items.mapNotNull { parseListItem(it) }
            }
        }
        return emptyList()
    }

    fun searchArtists(query: String, limit: Int = 10): List<YtmArtistRef> {
        val out = ArrayList<YtmArtistRef>()
        for (item in searchItems(query, PARAMS_ARTISTS)) {
            val bid = item.browseId ?: continue
            if (item.videoId != null) continue
            if (!bid.startsWith("UC") && !bid.startsWith("MPLA")) continue
            out.add(
                YtmArtistRef(
                    id = bid,
                    name = item.title,
                    meta = item.metaTexts.filter { !it.equals("Artist", ignoreCase = true) }
                        .joinToString(" • ").takeIf { it.isNotEmpty() },
                    artworkSmall = item.artworkSmall,
                    artworkLarge = item.artworkLarge
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    fun searchAlbums(query: String, limit: Int = 10): List<YtmAlbumRef> {
        val out = ArrayList<YtmAlbumRef>()
        for (item in searchItems(query, PARAMS_ALBUMS)) {
            val bid = item.browseId ?: continue
            if (!bid.startsWith("MPRE")) continue
            out.add(
                YtmAlbumRef(
                    id = bid,
                    title = item.title,
                    artist = item.artistName ?: "",
                    artistId = item.artistId,
                    year = item.year,
                    artworkSmall = item.artworkSmall,
                    artworkLarge = item.artworkLarge
                )
            )
            if (out.size >= limit) break
        }
        return out
    }

    // ------------------------------------------------------------ artist page

    fun artistPage(channelId: String): YtmArtistPage {
        val resp = post("browse", JSONObject().put("browseId", channelId))
        val header = findByKey(resp, "musicImmersiveHeaderRenderer")
        val name = header?.obj("title")?.let { runsText(it.arr("runs")) }?.takeIf { it.isNotEmpty() }
            ?: throw RuntimeException("artist response had no header")
        val (hSmall, hLarge) = if (header != null) itemThumbs(header) else (null to null)
        val subscribers = dig(header, "subscriptionButton", "subscribeButtonRenderer", "subscriberCountText")
            ?.let { textOf(it).takeIf { t -> t.isNotEmpty() } }

        var topSongs: List<Track> = emptyList()
        val albums = ArrayList<YtmAlbumRef>()
        val singles = ArrayList<YtmAlbumRef>()
        val related = ArrayList<YtmArtistRef>()
        var songsDone = false
        for (section in browseSections(resp)) {
            if (!songsDone && section.has("musicShelfRenderer")) {
                topSongs = shelfItems(section).mapNotNull {
                    parseListItem(it)?.toTrack(fallbackArtist = name, fallbackArtistId = channelId)
                }.take(10)
                songsDone = true
                continue
            }
            if (!section.has("musicCarouselShelfRenderer")) continue
            val title = carouselTitle(section).lowercase(Locale.US)
            val cards = carouselItems(section)
            when {
                title.contains("single") -> cards.forEach { c ->
                    val d = c.obj("musicTwoRowItemRenderer")?.let { parseTwoRowItem(it) } ?: return@forEach
                    val bid = d.browseId ?: return@forEach
                    if (bid.startsWith("MPRE")) {
                        singles.add(YtmAlbumRef(bid, d.title, name, channelId, d.year, d.artworkSmall, d.artworkLarge))
                    }
                }
                title.contains("album") -> cards.forEach { c ->
                    val d = c.obj("musicTwoRowItemRenderer")?.let { parseTwoRowItem(it) } ?: return@forEach
                    val bid = d.browseId ?: return@forEach
                    if (bid.startsWith("MPRE")) {
                        albums.add(YtmAlbumRef(bid, d.title, name, channelId, d.year, d.artworkSmall, d.artworkLarge))
                    }
                }
                title.contains("fans") || title.contains("related") || title.contains("similar") ->
                    cards.forEach { c ->
                        val d = c.obj("musicTwoRowItemRenderer")?.let { parseTwoRowItem(it) } ?: return@forEach
                        val bid = d.browseId ?: return@forEach
                        if (bid.startsWith("UC") || bid.startsWith("MPLA")) {
                            related.add(
                                YtmArtistRef(
                                    bid, d.title,
                                    d.subtitleText.takeIf { it.isNotEmpty() },
                                    d.artworkSmall, d.artworkLarge
                                )
                            )
                        }
                    }
            }
        }
        return YtmArtistPage(
            id = channelId,
            name = name,
            subscribers = subscribers,
            artworkSmall = hSmall,
            artworkLarge = hLarge,
            topSongs = topSongs,
            albums = albums,
            singles = singles,
            related = related
        )
    }

    // ------------------------------------------------------------ album page

    fun albumPage(ref: YtmAlbumRef): YtmAlbumPage {
        val resp = post("browse", JSONObject().put("browseId", ref.id))
        val header = findByKey(resp, "musicResponsiveHeaderRenderer")
        var title = ref.title
        var artist = ref.artist
        var artistId = ref.artistId
        var year = ref.year
        var artworkLarge = ref.artworkLarge
        if (header != null) {
            val hTitle = header.obj("title")?.let { runsText(it.arr("runs")) }
            if (!hTitle.isNullOrEmpty()) title = hTitle
            val strapRuns = dig(header, "straplineTextOne")?.arr("runs")
            val strapFirst = strapRuns?.optJSONObject(0)
            if (strapFirst != null) {
                val n = strapFirst.optString("text")
                if (n.isNotEmpty()) artist = n
                browseIdOf(strapFirst.obj("navigationEndpoint"))?.let { artistId = it }
            }
            val subRuns = header.obj("subtitle")?.arr("runs")
            if (subRuns != null) {
                for (i in 0 until subRuns.length()) {
                    val t = subRuns.optJSONObject(i)?.optString("text")?.trim() ?: continue
                    if (yearRe.matches(t)) year = t
                }
            }
            val (_, hLarge) = itemThumbs(header)
            if (hLarge != null) artworkLarge = hLarge
        }
        val tracks = ArrayList<Track>()
        for (section in browseSections(resp)) {
            for (item in shelfItems(section)) {
                parseListItem(item)?.toTrack(fallbackArtist = artist, fallbackArtistId = artistId)
                    ?.let { tracks.add(it) }
            }
        }
        return YtmAlbumPage(ref.id, title, artist, artistId, year, artworkLarge, tracks)
    }

    // ------------------------------------------------------------ playlists & charts

    fun playlistTracks(playlistId: String, limit: Int = 50): List<Track> {
        val resp = post("browse", JSONObject().put("browseId", "VL$playlistId"))
        val out = ArrayList<Track>()
        for (section in browseSections(resp)) {
            for (item in shelfItems(section)) {
                parseListItem(item)?.toTrack()?.let { out.add(it) }
                if (out.size >= limit) return out
            }
        }
        return out
    }

    /**
     * Global YouTube Music charts: the daily chart playlist's songs plus
     * the chart artists carousel. Throws when the response carries no
     * usable sections, so the caller can show the failure instead of an
     * empty hole.
     */
    fun charts(): YtmCharts {
        val body = JSONObject()
            .put("browseId", "FEmusic_charts")
            .put("formData", JSONObject().put("selectedValues", JSONArray().put("ZZ")))
        val resp = post("browse", body)
        val sections = browseSections(resp)
        var dailyPlaylistId: String? = null
        var artists: List<YtmArtistRef> = emptyList()
        for (section in sections) {
            if (!section.has("musicCarouselShelfRenderer")) continue
            val cards = carouselItems(section)
            if (cards.isEmpty()) continue
            if (dailyPlaylistId == null) {
                for (c in cards) {
                    val d = c.obj("musicTwoRowItemRenderer")?.let { parseTwoRowItem(it) } ?: continue
                    val bid = d.browseId ?: continue
                    if (bid.startsWith("VL")) {
                        dailyPlaylistId = bid.removePrefix("VL")
                        break
                    }
                }
            }
            if (artists.isEmpty()) {
                val parsed = ArrayList<YtmArtistRef>()
                for (c in cards) {
                    val item = c.obj("musicResponsiveListItemRenderer") ?: continue
                    val d = parseListItem(item) ?: continue
                    val bid = d.browseId ?: continue
                    if (d.videoId == null && (bid.startsWith("UC") || bid.startsWith("MPLA"))) {
                        parsed.add(
                            YtmArtistRef(
                                bid, d.title,
                                d.metaTexts.joinToString(" • ").takeIf { it.isNotEmpty() },
                                d.artworkSmall, d.artworkLarge
                            )
                        )
                    }
                }
                if (parsed.isNotEmpty()) artists = parsed
            }
        }
        val songs = dailyPlaylistId?.let { playlistTracks(it, 40) } ?: emptyList()
        if (songs.isEmpty() && artists.isEmpty()) {
            throw RuntimeException("charts response had no usable sections")
        }
        return YtmCharts(songs, artists)
    }

    // ------------------------------------------------------------ radio

    /**
     * Radio queue for a seed track via the watch-next endpoint
     * (RDAMVM automix), following radio continuations up to [limit].
     */
    fun radio(videoId: String, limit: Int = 48): List<Track> {
        val byId = LinkedHashMap<String, Track>()
        var continuation: String? = null
        var rounds = 0
        while (rounds < 4) {
            rounds++
            val body = if (continuation == null) {
                JSONObject()
                    .put("videoId", videoId)
                    .put("playlistId", "RDAMVM$videoId")
                    .put("isAudioOnly", true)
                    .put("enablePersistentPlaylistPanel", true)
                    .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            } else {
                JSONObject().put("continuation", continuation)
            }
            val resp = post("next", body)
            val panel = findByKey(resp, "playlistPanelRenderer")
                ?: findByKey(resp, "playlistPanelContinuation")
                ?: break
            val contents = panel.arr("contents") ?: break
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i)?.obj("playlistPanelVideoRenderer") ?: continue
                val vid = item.optString("videoId").takeIf { it.isNotEmpty() } ?: continue
                if (byId.containsKey(vid)) continue
                val title = item.obj("title")?.let { runsText(it.arr("runs")) } ?: continue
                if (title.isEmpty()) continue
                var artistName: String? = null
                var artistId: String? = null
                val byline = item.obj("longBylineText")?.arr("runs")
                if (byline != null) {
                    for (j in 0 until byline.length()) {
                        val run = byline.optJSONObject(j) ?: continue
                        val bid = browseIdOf(run.obj("navigationEndpoint")) ?: continue
                        if (bid.startsWith("UC") || bid.startsWith("MPLA")) {
                            artistName = run.optString("text")
                            artistId = bid
                            break
                        }
                    }
                }
                val lengthText = item.obj("lengthText")?.let { textOf(it) } ?: ""
                val durationSec = if (durationRe.matches(lengthText.trim())) parseDuration(lengthText) else 0
                val (small, large) = thumbPair(item.obj("thumbnail")?.arr("thumbnails"))
                byId[vid] = Track(
                    id = vid,
                    title = title,
                    artist = artistName ?: "",
                    duration = durationSec,
                    artworkSmall = small,
                    artworkLarge = large,
                    source = SOURCE_YOUTUBE,
                    artistId = artistId
                )
            }
            if (byId.size >= limit) break
            continuation = panel.arr("continuations")?.optJSONObject(0)
                ?.obj("nextRadioContinuationData")?.optString("continuation")
                ?.takeIf { it.isNotEmpty() }
            if (continuation == null) break
        }
        return byId.values.take(limit)
    }
}
