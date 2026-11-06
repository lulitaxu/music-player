package com.arzx.music

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors

const val SOURCE_AUDIUS = "audius"
const val SOURCE_YOUTUBE = "youtube"

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
 * Tiny artwork loader for the UI layer: downloads Audius artwork on a
 * background pool, caches bitmaps, and guards against view recycling by
 * tagging each ImageView with the URL it is waiting for.
 */
private object ArtworkLoader {
    private val cache = LruCache<String, Bitmap>(64)
    private val pool = Executors.newFixedThreadPool(3)

    fun load(url: String?, into: ImageView) {
        if (url.isNullOrEmpty()) {
            into.tag = null
            into.setImageDrawable(null)
            return
        }
        into.tag = url
        val hit = cache.get(url)
        if (hit != null) {
            into.setImageBitmap(hit)
            return
        }
        into.setImageDrawable(null)
        pool.execute {
            val bmp = download(url)
            if (bmp != null) cache.put(url, bmp)
            into.post {
                if (bmp != null && into.tag == url) into.setImageBitmap(bmp)
            }
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
        pool.execute {
            val src = cache.get(url) ?: download(url)?.also { cache.put(url, it) }
            val blurred = src?.let { blur(it) }
            if (blurred != null) cache.put(key, blurred)
            into.post {
                if (blurred != null && into.tag == key) into.setImageBitmap(blurred)
            }
        }
    }

    private fun download(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        if (conn.responseCode !in 200..299) null
        else conn.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun blur(src: Bitmap): Bitmap {
        val w = 28
        val h = (src.height * (w.toFloat() / src.width)).toInt().coerceAtLeast(1)
        var bmp = Bitmap.createScaledBitmap(src, w, h, true)
        repeat(3) { bmp = boxBlur(bmp, 5) }
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

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val tracks = mutableListOf<Track>()
    private var player: MediaPlayer? = null
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
    private lateinit var npVolume: SeekBar

    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false
    private var generation = 0
    private var playToken = 0
    private var activeTab = "Home"
    private var shuffleOn = false
    private var repeatMode = 0 // 0 = off, 1 = all, 2 = one
    private var playOrder: List<Int> = emptyList()
    private val downloading = mutableSetOf<String>()
    private var sleepDeadline = 0L

    private lateinit var npShuffleBtn: ImageView
    private lateinit var npRepeatBtn: ImageView
    private lateinit var npShuffleDot: View
    private lateinit var npRepeatDot: View
    private lateinit var npDownloadBtn: ImageView
    private lateinit var npDownloadProgress: ProgressBar
    private lateinit var npSleepLabel: TextView
    private val manualIndices = mutableSetOf<Int>()
    private var sleepAfterTrack = false
    private var artWasPlaying = false

    private lateinit var menuOverlay: FrameLayout
    private lateinit var menuPanel: LinearLayout
    private lateinit var menuTitle: TextView
    private lateinit var menuList: LinearLayout

    private lateinit var queueOverlay: FrameLayout
    private lateinit var queueList: LinearLayout

    private val sleepFire = Runnable {
        sleepDeadline = 0L
        fadeOutAndPause()
        updateSleepLabel()
        Toast.makeText(this@MainActivity, "Sleep timer ended", Toast.LENGTH_SHORT).show()
    }
    private val tabViews = mutableMapOf<String, Triple<LinearLayout, ImageView, TextView>>()
    private val categoryArtViews = mutableListOf<ImageView>()

    private val accent = Color.rgb(252, 60, 68)
    private val purple = Color.rgb(125, 42, 232)
    private val white50 = Color.argb(128, 255, 255, 255)
    private val white55 = Color.argb(140, 255, 255, 255)
    private val white75 = Color.argb(191, 255, 255, 255)
    private val placeholder = Color.rgb(23, 23, 27)

    private val boldTf: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val mediumTf: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regularTf: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        recentTracks.addAll(loadRecents())
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        showShell()
        showHome()
        handler.post(ticker)
    }

    // ---------------------------------------------------------------- shell

    private fun showShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(196))
        }
        scroll.addView(content, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        bottomBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- glass mini player ----
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBg(16f, Color.argb(158, 38, 38, 40), Color.argb(31, 255, 255, 255))
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
            setOnClickListener { togglePlayback() }
        }
        mini.addView(miniPlayButton, LinearLayout.LayoutParams(dp(38), dp(38)))
        miniNextButton = icon(R.drawable.ic_next, Color.WHITE).apply {
            setOnClickListener { playNext() }
        }
        mini.addView(miniNextButton, LinearLayout.LayoutParams(dp(38), dp(38)))
        visualizerView = visualizer().apply {
            visibility = if (getPreferences(MODE_PRIVATE).getBoolean("setting_Visualizer", true)) View.VISIBLE else View.GONE
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
        val miniLp = LinearLayout.LayoutParams(-1, if (getPreferences(MODE_PRIVATE).getBoolean("setting_Compact mini-player", false)) dp(48) else dp(58))
        miniLp.setMargins(dp(14), 0, dp(14), 0)
        bottomBar.addView(mini, miniLp)

        // ---- glass pill tab bar ----
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glassBg(999f, Color.argb(140, 30, 30, 32), Color.argb(36, 255, 255, 255))
            setPadding(dp(8), 0, dp(8), 0)
            elevation = dp(12).toFloat()
        }
        val tabs = listOf(
            Triple("Home", R.drawable.ic_home, Runnable { showHome() }),
            Triple("Search", R.drawable.ic_search, Runnable { showSearch() }),
            Triple("Library", R.drawable.ic_library, Runnable { showEmpty("Library", "Your library is empty", "Imported files and playlists will appear here.") }),
            Triple("Downloads", R.drawable.ic_download, Runnable { showDownloads() }),
            Triple("Settings", R.drawable.ic_settings, Runnable { showSettings() })
        )
        tabs.forEach { (name, iconRes, action) ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
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
        val navLp = LinearLayout.LayoutParams(-1, dp(64))
        navLp.setMargins(dp(14), dp(10), dp(14), dp(14))
        bottomBar.addView(nav, navLp)
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        buildNowPlaying(root)
        buildQueueOverlay(root)
        buildMenuOverlay(root)
        setContentView(root)
        setActiveTab("Home")
        bottomBar.post { fitContentPadding() }
    }

    /**
     * Sizes the scroll content's bottom inset from the MEASURED floating
     * bars (mini player + tab bar) plus breathing room, so the last rows
     * can always scroll fully clear of both bars on any device. (v3 used
     * a fixed guess and rows ended up hidden underneath.)
     */
    private fun fitContentPadding() {
        if (::bottomBar.isInitialized && ::content.isInitialized && bottomBar.height > 0) {
            content.setPadding(dp(22), dp(10), dp(22), bottomBar.height + dp(28))
        }
    }

    private fun setActiveTab(name: String) {
        activeTab = name
        tabViews.forEach { (tabName, views) ->
            val (box, iv, tv) = views
            if (tabName == name) {
                box.background = glassBg(999f, Color.argb(33, 255, 255, 255), Color.argb(20, 255, 255, 255))
                iv.setColorFilter(accent)
                tv.setTextColor(Color.WHITE)
            } else {
                box.background = null
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
                glassBg(999f, Color.argb(28, 255, 255, 255), Color.argb(20, 255, 255, 255))
            }
            isClickable = true
            setOnClickListener { onClick() }
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

    private fun glassBg(radiusDp: Float, fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setStroke(dp(1), stroke)
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
        val gen = ++generation
        beginScreen("Listen Now", showAvatar = true)
        val loading = label("Loading…", 16f, white50)
        content.addView(loading)
        executor.execute {
            val audius = fetch("/tracks/trending?limit=25")
            var charts: YtmCharts? = null
            var chartsErr: String? = null
            try {
                charts = YtmApi.charts()
            } catch (t: Throwable) {
                chartsErr = shortErr(t)
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                content.removeView(loading)
                var any = false
                if (audius != null && audius.isNotEmpty()) {
                    tracks.clear()
                    tracks.addAll(audius)
                }
                val featured = audius?.firstOrNull() ?: charts?.songs?.firstOrNull()
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
                if (audius != null && audius.size > 1) {
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
        ArtworkLoader.load(track.artworkLarge ?: track.artworkSmall, img)
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
        val gen = ++generation
        beginScreen("Search", showAvatar = true)
        categoryArtViews.clear()

        lateinit var resultsBox: LinearLayout
        lateinit var browseBox: LinearLayout
        lateinit var input: EditText

        fun runSearch(query: String) {
            val q = query.trim()
            if (q.isEmpty()) return
            browseBox.visibility = View.GONE
            resultsBox.visibility = View.VISIBLE
            resultsBox.removeAllViews()
            resultsBox.addView(label("Searching catalogs…", 14f, white50))
            executor.execute {
                val audius = fetch("/tracks/search?query=${URLEncoder.encode(q, "UTF-8")}&limit=25")
                // Every YouTube call is separately guarded and its
                // failure is RENDERED below the results (v3 swallowed
                // the exception and YouTube quietly never appeared).
                var ytSongs: List<Track> = emptyList()
                var ytNote: String? = null
                var ytErr: String? = null
                try {
                    val r = YoutubeSource.searchSongs(q)
                    ytSongs = r.tracks
                    ytNote = r.note
                } catch (t: Throwable) {
                    ytErr = shortErr(t)
                }
                var ytArtists: List<YtmArtistRef> = emptyList()
                var ytArtistsErr: String? = null
                try {
                    ytArtists = YtmApi.searchArtists(q)
                } catch (t: Throwable) {
                    ytArtistsErr = shortErr(t)
                }
                var ytAlbums: List<YtmAlbumRef> = emptyList()
                var ytAlbumsErr: String? = null
                try {
                    ytAlbums = YtmApi.searchAlbums(q)
                } catch (t: Throwable) {
                    ytAlbumsErr = shortErr(t)
                }
                // One merged song list (Audius first, then YouTube
                // Music) so next/previous playback flows across sources.
                val found = mutableListOf<Track>()
                audius?.let { found.addAll(it) }
                found.addAll(ytSongs)
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    resultsBox.removeAllViews()
                    val nothing = found.isEmpty() && ytArtists.isEmpty() && ytAlbums.isEmpty()
                    if (nothing) {
                        if (audius == null && ytErr != null) {
                            resultsBox.addView(label("Playable results are unavailable right now.", 14f, white50))
                        } else {
                            resultsBox.addView(label("No results", 18f, Color.WHITE, boldTf))
                        }
                    }
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
                    if (found.isNotEmpty()) {
                        if (ytArtists.isNotEmpty() || ytAlbums.isNotEmpty()) {
                            resultsBox.addView(sectionTitle("Songs"))
                        }
                        setQueue(found)
                        addRows(found, resultsBox, withHairlines = true)
                    }
                    // Source status: always on screen, success or failure.
                    val errors = listOfNotNull(
                        ytErr?.let { "songs: $it" },
                        ytArtistsErr?.let { "artists: $it" },
                        ytAlbumsErr?.let { "albums: $it" }
                    )
                    if (errors.isNotEmpty()) {
                        resultsBox.addView(errorLine("YouTube unavailable: " + errors.joinToString(" · ")))
                    }
                    if (ytNote != null) {
                        resultsBox.addView(statusLine("YouTube: $ytNote"))
                    }
                    if (errors.isEmpty() && ytNote == null) {
                        resultsBox.addView(
                            statusLine(
                                "YouTube Music: ${ytSongs.size} songs · ${ytArtists.size} artists · ${ytAlbums.size} albums"
                            )
                        )
                    }
                }
            }
        }

        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(28, 255, 255, 255))
                cornerRadius = dp(11).toFloat()
            }
            setPadding(dp(11), 0, dp(11), 0)
        }
        val fLp = LinearLayout.LayoutParams(-1, dp(38))
        fLp.topMargin = dp(14)
        field.layoutParams = fLp
        field.addView(icon(R.drawable.ic_search, white50), LinearLayout.LayoutParams(dp(17), dp(17)))
        input = EditText(this).apply {
            hint = "Artists, Songs, Lyrics and More"
            setTextColor(Color.WHITE)
            setHintTextColor(white50)
            setTextSize(16f)
            setSingleLine(true)
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(7), 0, 0, 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runSearch(text.toString())
                    true
                } else false
            }
        }
        field.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(field)

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
                    input.setText(name)
                    runSearch(term)
                }, cardLp)
            }
            browseBox.addView(rowLayout)
        }
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
                setColor(Color.rgb(24, 24, 28))
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
        ArtworkLoader.load(track.artworkSmall ?: track.artworkLarge, img)
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

    private fun showSettings() {
        generation++
        beginScreen("Settings", showAvatar = false)
        content.addView(sectionTitle("About"))
        content.addView(label("Version 0.2.0", 15f, Color.WHITE))
        content.addView(label("Streams via Audius and YouTube. Metadata via MusicBrainz.", 15f, white50))
        content.addView(label("Lyrics: Not available yet", 15f, white50))
        content.addView(label("No DRM bypass, ever.", 15f, white50))
        content.addView(sectionTitle("Built-in player features"))
        content.addView(setting("Visualizer", true))
        content.addView(setting("Compact mini-player", false))
        content.addView(label("Power-user controls are built into arzxsh instead of installed as add-ons.", 14f, white50))
    }

    private fun showEmpty(title: String, heading: String, message: String) {
        generation++
        beginScreen(title, showAvatar = false)
        content.addView(sectionTitle(heading))
        content.addView(label(message, 15f, white50))
    }

    // ---------------------------------------------------------------- rows

    private fun addRows(
        list: List<Track>,
        target: LinearLayout = content,
        withHairlines: Boolean,
        queueContext: List<Track>? = null
    ) {
        list.forEachIndexed { i, track ->
            if (withHairlines && i > 0) target.addView(hairline())
            addTrackRow(track, target, queueContext)
        }
    }

    private fun hairline(): View {
        val v = View(this).apply { setBackgroundColor(Color.argb(23, 255, 255, 255)) }
        val lp = LinearLayout.LayoutParams(-1, 1)
        lp.marginStart = dp(65)
        v.layoutParams = lp
        return v
    }

    private fun addTrackRow(track: Track, target: LinearLayout, queueContext: List<Track>? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            rippleRect()
            setOnClickListener {
                if (queueContext != null) setQueue(queueContext)
                play(track)
            }
            setOnLongClickListener {
                showTrackMenu(track)
                true
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 9)
        }
        ArtworkLoader.load(track.artworkSmall ?: track.artworkLarge, img)
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
        if (track.source == SOURCE_YOUTUBE) {
            // Filled accent pill: the YouTube marker must be unmissable
            // (v3's 9sp translucent chip effectively was invisible).
            val badge = label("YT", 10.5f, Color.WHITE, boldTf).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(5).toFloat()
                }
                setPadding(dp(7), dp(3), dp(7), dp(3))
            }
            val badgeLp = LinearLayout.LayoutParams(-2, -2)
            badgeLp.marginStart = dp(8)
            badgeLp.marginEnd = dp(2)
            row.addView(badge, badgeLp)
        }
        if (Downloads.isDownloaded(this, track)) {
            val doneIcon = icon(R.drawable.ic_downloaded, accent)
            val dLp = LinearLayout.LayoutParams(dp(19), dp(19))
            dLp.marginStart = dp(8)
            row.addView(doneIcon, dLp)
        }
        if (track.id == current?.id && track.source == current?.source) {
            row.addView(visualizer())
        } else {
            val moreIcon = icon(R.drawable.ic_more, Color.argb(115, 255, 255, 255)).apply {
                setOnClickListener { showTrackMenu(track) }
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

    /** Visible failure line: a dead YouTube section says why, in accent. */
    private fun errorLine(text: String): TextView {
        val tv = label(text, 13f, accent)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(14)
        tv.layoutParams = lp
        return tv
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
        ArtworkLoader.load(artist.artworkSmall ?: artist.artworkLarge, img)
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
        ArtworkLoader.load(album.artworkSmall ?: album.artworkLarge, img)
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
        val gen = ++generation
        content.removeAllViews()
        content.addView(label("Loading artist…", 16f, white50).apply {
            setPadding(0, dp(28), 0, 0)
        })
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
                content.removeAllViews()
                val p = page
                if (p == null) {
                    content.addView(label(fallbackName ?: "Artist", 30f, Color.WHITE, boldTf))
                    content.addView(errorLine("YouTube unavailable: $err"))
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
                        setQueue(p.topSongs)
                        play(p.topSongs.first())
                    }
                    actions.addView(playPill, LinearLayout.LayoutParams(dp(120), dp(40)))
                    val shufflePill = pillButton("Shuffle", filled = false) {
                        shuffleOn = true
                        setQueue(p.topSongs)
                        refreshTransportStates()
                        play(p.topSongs[playOrder.firstOrNull() ?: 0])
                    }
                    val sLp = LinearLayout.LayoutParams(dp(120), dp(40))
                    sLp.marginStart = dp(12)
                    actions.addView(shufflePill, sLp)
                    content.addView(actions)
                    content.addView(sectionTitle("Top Songs"))
                    addRows(p.topSongs, withHairlines = true, queueContext = p.topSongs)
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
        val gen = ++generation
        content.removeAllViews()
        content.addView(label("Loading album…", 16f, white50).apply {
            setPadding(0, dp(28), 0, 0)
        })
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
                content.removeAllViews()
                val p = page
                if (p == null) {
                    content.addView(label(ref.title, 26f, Color.WHITE, boldTf))
                    content.addView(errorLine("YouTube unavailable: $err"))
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
                        background = glassBg(999f, Color.argb(28, 255, 255, 255), Color.argb(20, 255, 255, 255))
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        setOnClickListener {
                            shuffleOn = true
                            setQueue(p.tracks)
                            refreshTransportStates()
                            play(p.tracks[playOrder.firstOrNull() ?: 0])
                        }
                    }
                    actions.addView(shuffleBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
                    val playAll = pillButton("Play", filled = true) {
                        setQueue(p.tracks)
                        play(p.tracks.first())
                    }
                    val playLp = LinearLayout.LayoutParams(dp(132), dp(40))
                    playLp.setMargins(dp(14), 0, dp(14), 0)
                    actions.addView(playAll, playLp)
                    val allDown = p.tracks.all { Downloads.isDownloaded(this, it) }
                    val dlBtn = icon(
                        if (allDown) R.drawable.ic_downloaded else R.drawable.ic_download,
                        if (allDown) accent else Color.WHITE
                    ).apply {
                        background = glassBg(999f, Color.argb(28, 255, 255, 255), Color.argb(20, 255, 255, 255))
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        setOnClickListener {
                            if (allDown) {
                                Toast.makeText(this@MainActivity, "Already downloaded", Toast.LENGTH_SHORT).show()
                            } else {
                                p.tracks.filter { !Downloads.isDownloaded(this@MainActivity, it) }
                                    .forEach { startDownload(it) }
                            }
                        }
                    }
                    actions.addView(dlBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
                    content.addView(actions)
                    addRows(p.tracks, withHairlines = true, queueContext = p.tracks)
                } else {
                    content.addView(label("No tracks listed for this album.", 15f, white50).apply {
                        setPadding(0, dp(18), 0, 0)
                    })
                }
            }
        }
    }

    // ---------------------------------------------------------------- now playing

    private fun buildNowPlaying(root: FrameLayout) {
        nowPlaying = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        npBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setColorFilter(Color.argb(96, 0, 0, 0))
        }
        nowPlaying.addView(npBackdrop, FrameLayout.LayoutParams(-1, -1))
        val shade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(38, 0, 0, 0), Color.argb(115, 0, 0, 0))
            )
        }
        nowPlaying.addView(shade, FrameLayout.LayoutParams(-1, -1))

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(48), dp(30), dp(28))
        }
        val grabber = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(89, 255, 255, 255))
                cornerRadius = dp(3).toFloat()
            }
            setOnClickListener { hideNowPlaying() }
        }
        val grabLp = LinearLayout.LayoutParams(dp(38), dp(5))
        grabLp.gravity = Gravity.CENTER_HORIZONTAL
        grabLp.bottomMargin = dp(30)
        inner.addView(grabber, grabLp)

        val artSize = resources.displayMetrics.widthPixels - dp(60)
        npArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 14)
            elevation = dp(24).toFloat()
        }
        inner.addView(npArtwork, LinearLayout.LayoutParams(artSize, artSize))

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val metaLp = LinearLayout.LayoutParams(-1, -2)
        metaLp.topMargin = dp(26)
        meta.layoutParams = metaLp
        val metaText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        npTitle = label("", 21.5f, Color.WHITE, boldTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        npArtist = label("", 20f, white55).apply {
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
            setOnClickListener { toggleFavorite() }
        }
        meta.addView(npHeart, LinearLayout.LayoutParams(dp(38), dp(38)))
        inner.addView(meta)

        npSeek = styledSeek(R.drawable.seek_thumb).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = try { player?.duration ?: 0 } catch (_: IllegalStateException) { 0 }
                        if (dur > 0) {
                            npElapsed.text = fmt(progress)
                            npRemaining.text = "-" + fmt((dur - progress).coerceAtLeast(0))
                        }
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seeking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seeking = false
                    try {
                        player?.seekTo(sb?.progress ?: 0)
                    } catch (_: IllegalStateException) {
                    }
                }
            })
        }
        val seekLp = LinearLayout.LayoutParams(-1, dp(26))
        seekLp.topMargin = dp(20)
        inner.addView(npSeek, seekLp)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        npElapsed = label("0:00", 12.5f, white55)
        npRemaining = label("-0:00", 12.5f, white55)
        times.addView(npElapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(npRemaining)
        inner.addView(times)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
        }
        val controlsLp = LinearLayout.LayoutParams(-1, -2)
        controlsLp.topMargin = dp(22)
        controls.layoutParams = controlsLp
        // Spotify state treatment for the v4 deviation (spec §2.1/§3):
        // active = accent glyph + a dot beneath it; repeat-one adds the
        // "1" worked into the glyph.
        npShuffleBtn = icon(R.drawable.ic_shuffle, white55).apply { setOnClickListener { toggleShuffle() } }
        npShuffleDot = dotView()
        val shuffleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        shuffleBox.addView(npShuffleBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        val shDotLp = LinearLayout.LayoutParams(dp(4), dp(4))
        shDotLp.topMargin = dp(3)
        shuffleBox.addView(npShuffleDot, shDotLp)
        val prevBtn = icon(R.drawable.ic_skip_prev, Color.WHITE).apply { setOnClickListener { playPrevious() } }
        npPlayButton = icon(R.drawable.ic_play, Color.WHITE).apply { setOnClickListener { togglePlayback() } }
        val nextBtn = icon(R.drawable.ic_skip_next, Color.WHITE).apply { setOnClickListener { playNext() } }
        npRepeatBtn = icon(R.drawable.ic_repeat, white55).apply { setOnClickListener { toggleRepeat() } }
        npRepeatDot = dotView()
        val repeatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        repeatBox.addView(npRepeatBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        val rpDotLp = LinearLayout.LayoutParams(dp(4), dp(4))
        rpDotLp.topMargin = dp(3)
        repeatBox.addView(npRepeatDot, rpDotLp)
        controls.addView(shuffleBox, LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(prevBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(npPlayButton, LinearLayout.LayoutParams(0, dp(68), 1.2f))
        controls.addView(nextBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(repeatBox, LinearLayout.LayoutParams(0, dp(44), 1f))
        inner.addView(controls)

        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val volLp = LinearLayout.LayoutParams(-1, -2)
        volLp.topMargin = dp(28)
        volRow.layoutParams = volLp
        volRow.addView(icon(R.drawable.ic_volume_low, white50), LinearLayout.LayoutParams(dp(17), dp(17)))
        npVolume = styledSeek(R.drawable.seek_thumb_clear).apply {
            max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val npVolLp = LinearLayout.LayoutParams(0, dp(26), 1f)
        npVolLp.setMargins(dp(12), 0, dp(12), 0)
        volRow.addView(npVolume, npVolLp)
        volRow.addView(icon(R.drawable.ic_volume_high, white50), LinearLayout.LayoutParams(dp(19), dp(19)))
        inner.addView(volRow)

        npSleepLabel = label("", 12.5f, white55).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val sleepLp = LinearLayout.LayoutParams(-1, -2)
        sleepLp.topMargin = dp(12)
        inner.addView(npSleepLabel, sleepLp)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(22), 0, dp(22), 0)
        }
        val bottomLp = LinearLayout.LayoutParams(-1, -2)
        bottomLp.topMargin = dp(24)
        bottomRow.layoutParams = bottomLp
        val lyricsBtn = icon(R.drawable.ic_lyrics, white75).apply {
            setOnClickListener { Toast.makeText(this@MainActivity, "Lyrics: Not available yet", Toast.LENGTH_SHORT).show() }
        }
        val queueBtn = icon(R.drawable.ic_queue, white75).apply {
            setOnClickListener { showQueue() }
        }
        npDownloadBtn = icon(R.drawable.ic_download, white75).apply {
            setOnClickListener { downloadCurrent() }
        }
        npDownloadProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(accent)
        }
        val shareBtn = icon(R.drawable.ic_share, white75).apply {
            setOnClickListener { shareTrack(current) }
        }
        val moreBtn = icon(R.drawable.ic_more, white75).apply {
            setOnClickListener { showNpMenu() }
        }
        bottomRow.addView(lyricsBtn, LinearLayout.LayoutParams(0, dp(32), 1f))
        bottomRow.addView(queueBtn, LinearLayout.LayoutParams(0, dp(32), 1f))
        // Download slot: arrow (idle) -> indeterminate ring (working) ->
        // accent check (done), the three states in one footprint.
        val downloadSlot = FrameLayout(this)
        downloadSlot.addView(npDownloadBtn, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        downloadSlot.addView(npDownloadProgress, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        bottomRow.addView(downloadSlot, LinearLayout.LayoutParams(0, dp(32), 1f))
        bottomRow.addView(shareBtn, LinearLayout.LayoutParams(0, dp(32), 1f))
        bottomRow.addView(moreBtn, LinearLayout.LayoutParams(0, dp(32), 1f))
        inner.addView(bottomRow)

        nowPlaying.addView(inner, FrameLayout.LayoutParams(-1, -1))
        root.addView(nowPlaying, FrameLayout.LayoutParams(-1, -1))
    }

    private fun styledSeek(thumbRes: Int): SeekBar = SeekBar(this).apply {
        progressDrawable = getDrawable(R.drawable.seek_progress)
        thumb = getDrawable(thumbRes)
        splitTrack = false
        setPadding(dp(7), 0, dp(7), 0)
    }

    private fun showNowPlaying() {
        nowPlaying.visibility = View.VISIBLE
        refreshPlaybackUi()
        try {
            npVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) {
        }
        // Slide up from the mini player instead of a hard cut; the
        // transform is composited, so it stays smooth on mid-range GPUs.
        val h = nowPlaying.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        nowPlaying.translationY = h.toFloat()
        nowPlaying.animate().translationY(0f).setDuration(300)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hideNowPlaying() {
        val h = nowPlaying.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        nowPlaying.animate().translationY(h.toFloat()).setDuration(230)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                nowPlaying.visibility = View.GONE
                nowPlaying.translationY = 0f
            }
            .start()
    }

    override fun onBackPressed() {
        when {
            ::menuOverlay.isInitialized && menuOverlay.visibility == View.VISIBLE -> hideMenu()
            ::queueOverlay.isInitialized && queueOverlay.visibility == View.VISIBLE -> hideQueue()
            ::nowPlaying.isInitialized && nowPlaying.visibility == View.VISIBLE -> hideNowPlaying()
            else -> super.onBackPressed()
        }
    }

    private fun updateProgress() {
        if (!::nowPlaying.isInitialized || nowPlaying.visibility != View.VISIBLE || seeking) return
        updateSleepLabel()
        val p = player ?: return
        try {
            val dur = p.duration
            if (dur > 0) {
                npSeek.max = dur
                npSeek.progress = p.currentPosition
                npElapsed.text = fmt(p.currentPosition)
                npRemaining.text = "-" + fmt((dur - p.currentPosition).coerceAtLeast(0))
            }
        } catch (_: IllegalStateException) {
        }
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun toggleFavorite() {
        val t = current ?: return
        val key = "fav_${t.id}"
        val prefs = getPreferences(MODE_PRIVATE)
        val now = !prefs.getBoolean(key, false)
        prefs.edit().putBoolean(key, now).apply()
        updateHeart()
    }

    private fun updateHeart() {
        val t = current ?: return
        val fav = getPreferences(MODE_PRIVATE).getBoolean("fav_${t.id}", false)
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
        val idx = queue.indexOfFirst { it.id == track.id && it.source == track.source }
        if (idx >= 0) {
            queueIndex = idx
            if (playOrder.isEmpty()) rebuildOrder()
        } else {
            setQueue(listOf(track), 0)
        }
        current = track
        saveRecent(track)
        refreshPlaybackUi()
        val token = ++playToken
        // A downloaded copy always wins: instant start, works offline.
        val local = Downloads.localFile(this, track)
        if (local != null) {
            startPlayback(local.absolutePath)
            return
        }
        if (track.source == SOURCE_YOUTUBE) {
            // Stop the previous track now; the direct stream URL is
            // resolved on-device in the background, then handed to the
            // same player path as every other source.
            player?.release()
            player = null
            executor.execute {
                val url = try {
                    YoutubeSource.resolveAudioUrl(track.id)
                } catch (_: Exception) {
                    null
                }
                runOnUiThread {
                    if (token != playToken) return@runOnUiThread
                    if (url == null) {
                        Toast.makeText(this@MainActivity, "This track could not be played.", Toast.LENGTH_SHORT).show()
                    } else {
                        startPlayback(url)
                    }
                }
            }
        } else {
            startPlayback("https://api.audius.co/v1/tracks/${track.id}/stream?app_name=arzxsh")
        }
    }

    /** Shared player setup for every source: [stream] is a direct audio URL. */
    private fun startPlayback(stream: String) {
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(stream)
            setOnPreparedListener {
                it.start()
                refreshPlaybackUi()
            }
            setOnCompletionListener { handleCompletion() }
            setOnErrorListener { _, _, _ ->
                refreshPlaybackUi()
                Toast.makeText(this@MainActivity, "This track could not be played.", Toast.LENGTH_SHORT).show()
                true
            }
            prepareAsync()
        }
    }

    // ------------------------------------------------------------ queue order, shuffle, repeat

    /** Replaces the queue and rebuilds the play order around it. */
    private fun setQueue(newQueue: List<Track>, startIndex: Int = -1) {
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
        refreshPlaybackUi()
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
        try {
            if (p != null && p.currentPosition > 3000) {
                p.seekTo(0)
                return
            }
        } catch (_: IllegalStateException) {
        }
        if (queue.isEmpty() || queueIndex < 0) return
        val pos = currentOrderPos()
        if (pos > 0) {
            play(queue[playOrder[pos - 1]])
        } else {
            try {
                p?.seekTo(0)
            } catch (_: IllegalStateException) {
            }
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
        executor.execute {
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
        val active = player ?: return
        try {
            if (active.isPlaying) {
                active.pause()
            } else {
                active.start()
            }
        } catch (_: IllegalStateException) {
        }
        // Small scale pop on the glyph swap (spec §13.4), transform-only.
        if (::npPlayButton.isInitialized && nowPlaying.visibility == View.VISIBLE) {
            npPlayButton.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70)
                .withEndAction {
                    npPlayButton.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                .start()
        }
        refreshPlaybackUi()
    }

    private fun isPlayingSafe(): Boolean = try {
        player?.isPlaying == true
    } catch (_: IllegalStateException) {
        false
    }

    private fun refreshPlaybackUi() {
        val t = current
        val playing = isPlayingSafe()
        miniPlayButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (::npPlayButton.isInitialized) {
            npPlayButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
        refreshTransportStates()
        refreshDownloadButton()
        // Apple signature detail (spec §13.3): the artwork breathes —
        // slightly smaller while paused, full size while playing.
        if (::npArtwork.isInitialized && playing != artWasPlaying) {
            artWasPlaying = playing
            val s = if (playing) 1f else 0.92f
            npArtwork.animate().scaleX(s).scaleY(s).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        if (t == null) return
        miniTitle.text = t.title
        miniArtist.text = t.artist
        ArtworkLoader.load(t.artworkSmall ?: t.artworkLarge, miniArtwork)
        if (::npTitle.isInitialized) {
            npTitle.text = t.title
            npArtist.text = t.artist
            ArtworkLoader.load(t.artworkLarge ?: t.artworkSmall, npArtwork)
            ArtworkLoader.loadBlurred(t.artworkLarge ?: t.artworkSmall, npBackdrop)
            updateHeart()
            npSeek.progress = 0
            npElapsed.text = "0:00"
            npRemaining.text = "-" + fmt(t.duration * 1000)
        }
    }

    // ---------------------------------------------------------------- menus, queue screen, sleep timer, downloads

    private fun buildMenuOverlay(root: FrameLayout) {
        menuOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnClickListener { hideMenu() }
        }
        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBg(22f, Color.argb(242, 30, 30, 33), Color.argb(36, 255, 255, 255))
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
        menuOverlay.alpha = 0f
        menuOverlay.animate().alpha(1f).setDuration(150).start()
        menuPanel.translationY = dp(28).toFloat()
        menuPanel.animate().translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hideMenu() {
        if (::menuOverlay.isInitialized) menuOverlay.visibility = View.GONE
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
            else -> "Sleep Timer"
        }
        val items = mutableListOf<Pair<String, () -> Unit>>()
        for (m in listOf(5, 10, 15, 30, 45, 60)) {
            items += (if (m == 60) "1 hour" else "$m minutes") to { setSleepTimer(m) }
        }
        items += "End of track" to { setSleepEndOfTrack() }
        if (sleepDeadline > now || sleepAfterTrack) {
            items += "Turn off timer" to { setSleepTimer(0) }
        }
        showMenu(title, items)
    }

    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepFire)
        sleepAfterTrack = false
        if (minutes <= 0) {
            sleepDeadline = 0L
            Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
        } else {
            sleepDeadline = System.currentTimeMillis() + minutes * 60_000L
            handler.postDelayed(sleepFire, minutes * 60_000L)
            // The single confirmation (spec §6); Android Toast stands
            // in for Spotify's white snackbar.
            Toast.makeText(this, "Your sleep timer is set.", Toast.LENGTH_SHORT).show()
        }
        updateSleepLabel()
    }

    private fun setSleepEndOfTrack() {
        handler.removeCallbacks(sleepFire)
        sleepDeadline = 0L
        sleepAfterTrack = true
        updateSleepLabel()
        Toast.makeText(this, "Your sleep timer is set.", Toast.LENGTH_SHORT).show()
    }

    /** Sleep-timer expiry fades the volume out over ~2s, then pauses
     *  (spec §6), instead of hard-cutting. */
    private fun fadeOutAndPause() {
        val p = player ?: return
        try {
            if (!p.isPlaying) return
        } catch (_: IllegalStateException) {
            return
        }
        var step = 0
        val fade = object : Runnable {
            override fun run() {
                step++
                try {
                    val v = (1f - step / 20f).coerceAtLeast(0f)
                    p.setVolume(v, v)
                    if (step < 20) {
                        handler.postDelayed(this, 100)
                    } else {
                        p.pause()
                        p.setVolume(1f, 1f)
                        refreshPlaybackUi()
                    }
                } catch (_: IllegalStateException) {
                }
            }
        }
        handler.post(fade)
    }

    /** The honest remaining-time label under the Now Playing controls. */
    private fun updateSleepLabel() {
        if (!::npSleepLabel.isInitialized) return
        if (sleepAfterTrack) {
            npSleepLabel.text = "Sleep timer · ends after this track"
            npSleepLabel.visibility = View.VISIBLE
            return
        }
        val remaining = sleepDeadline - System.currentTimeMillis()
        if (sleepDeadline > 0 && remaining > 0) {
            npSleepLabel.text = "Sleep timer · " + fmt(remaining.toInt())
            npSleepLabel.visibility = View.VISIBLE
        } else {
            npSleepLabel.visibility = View.GONE
        }
    }

    /** Context menu for any track row (⋯ button or long-press), on
     *  every screen that lists songs. */
    private fun showTrackMenu(track: Track) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Play Next" to { playNextInsert(track) }
        items += "Add to Queue" to { appendToQueue(track) }
        if (Downloads.isDownloaded(this, track)) {
            items += "Remove Download" to {
                Downloads.remove(this, track)
                Toast.makeText(this, "Download removed", Toast.LENGTH_SHORT).show()
                refreshPlaybackUi()
            }
        } else {
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
        val busy = downloading.contains(Downloads.key(t))
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
        if (!downloading.add(Downloads.key(track))) return
        refreshDownloadButton()
        Toast.makeText(this, "Downloading ${track.title}…", Toast.LENGTH_SHORT).show()
        executor.execute {
            var err: String? = null
            try {
                Downloads.downloadSync(this@MainActivity, track)
            } catch (e: Throwable) {
                err = shortErr(e)
            }
            runOnUiThread {
                downloading.remove(Downloads.key(track))
                if (err != null) {
                    Toast.makeText(this@MainActivity, "Download failed: $err", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Downloaded: ${track.title}", Toast.LENGTH_SHORT).show()
                }
                refreshPlaybackUi()
            }
        }
    }

    private fun showDownloads() {
        generation++
        beginScreen("Downloads", showAvatar = false)
        val entries = Downloads.entries(this)
        if (entries.isEmpty()) {
            content.addView(sectionTitle("No downloads yet"))
            content.addView(
                label(
                    "Tracks you download from Now Playing or a track's ⋯ menu live here and play offline.",
                    15f, white50
                )
            )
        } else {
            val list = entries.map { it.track }
            content.addView(sectionTitle("${list.size} downloaded"))
            addRows(list, withHairlines = true, queueContext = list)
        }
    }

    // ------------------------------------------------------------ queue screen

    private fun buildQueueOverlay(root: FrameLayout) {
        queueOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.argb(250, 0, 0, 0))
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
        val scroll = ScrollView(this)
        queueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(queueList)
        val sLp = LinearLayout.LayoutParams(-1, 0, 1f)
        sLp.topMargin = dp(8)
        inner.addView(scroll, sLp)
        queueOverlay.addView(inner, FrameLayout.LayoutParams(-1, -1))
        root.addView(queueOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showQueue() {
        queueList.removeAllViews()
        // Mode chips at the top of the queue (Apple puts the toggles
        // here), reflecting and driving the same player state.
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(2))
        }
        modes.addView(modeChip("Shuffle", shuffleOn) { toggleShuffle(); showQueue() })
        val repeatText = when (repeatMode) {
            1 -> "Repeat All"
            2 -> "Repeat One"
            else -> "Repeat"
        }
        modes.addView(modeChip(repeatText, repeatMode > 0) { toggleRepeat(); showQueue() })
        queueList.addView(modes)
        if (queue.isEmpty() || queueIndex < 0) {
            queueList.addView(label("Queue is empty.", 15f, white50).apply {
                setPadding(0, dp(16), 0, 0)
            })
        } else {
            queueList.addView(sectionTitle("Now Playing"))
            queueList.addView(queueRow(queue[queueIndex], isCurrent = true))
            val pos = currentOrderPos()
            val upcoming = if (pos >= 0) playOrder.drop(pos + 1) else emptyList()
            // Spotify's section rule: what the user queued by hand is
            // its own section and plays before the source continuation.
            val manual = upcoming.filter { it in manualIndices }
            val rest = upcoming.filter { it !in manualIndices }
            if (manual.isNotEmpty()) {
                queueList.addView(queueSectionHeader("Next in Queue", showClear = true))
                manual.forEach { qi -> queueList.addView(queueRow(queue[qi], isCurrent = false)) }
            }
            if (rest.isNotEmpty()) {
                queueList.addView(sectionTitle("Next Up"))
                rest.forEach { qi -> queueList.addView(queueRow(queue[qi], isCurrent = false)) }
            }
        }
        queueOverlay.visibility = View.VISIBLE
        queueOverlay.alpha = 0f
        queueOverlay.animate().alpha(1f).setDuration(180).start()
    }

    private fun modeChip(text: String, active: Boolean, onClick: () -> Unit): TextView {
        val chip = label(text, 13.5f, if (active) accent else white55, boldTf).apply {
            gravity = Gravity.CENTER
            background = glassBg(
                999f,
                if (active) Color.argb(46, 252, 60, 68) else Color.argb(24, 255, 255, 255),
                Color.argb(20, 255, 255, 255)
            )
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
    private fun queueSectionHeader(title: String, showClear: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(24)
        lp.bottomMargin = dp(6)
        row.layoutParams = lp
        row.addView(label(title, 21f, Color.WHITE, boldTf), LinearLayout.LayoutParams(0, -2, 1f))
        if (showClear) {
            val clear = label("Clear", 14.5f, accent, mediumTf).apply {
                setPadding(dp(10), dp(4), dp(2), dp(4))
                isClickable = true
                setOnClickListener { clearManualQueue() }
            }
            row.addView(clear)
        }
        return row
    }

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
        if (::queueOverlay.isInitialized) queueOverlay.visibility = View.GONE
    }

    private fun queueRow(track: Track, isCurrent: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            if (!isCurrent) {
                rippleRect()
                setOnClickListener {
                    hideQueue()
                    play(track)
                }
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 8)
        }
        ArtworkLoader.load(track.artworkSmall ?: track.artworkLarge, img)
        row.addView(img, LinearLayout.LayoutParams(dp(46), dp(46)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        text.addView(label(track.title, 16f, if (isCurrent) accent else Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        text.addView(label(track.artist, 14f, white50).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        if (isCurrent) row.addView(visualizer())
        return row
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
        val arr = JSONArray()
        recentTracks.forEach { t ->
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
        getPreferences(MODE_PRIVATE).edit().putString("recent_tracks", arr.toString()).apply()
    }

    // ---------------------------------------------------------------- helpers kept from the original UI

    private fun setting(name: String, default: Boolean): Switch {
        val key = "setting_$name"
        return Switch(this).apply {
            text = name
            textSize = 15f
            setTextColor(Color.WHITE)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(accent, Color.argb(160, 255, 255, 255)))
            trackTintList = ColorStateList(states, intArrayOf(Color.argb(120, 252, 60, 68), Color.argb(60, 255, 255, 255)))
            isChecked = getPreferences(MODE_PRIVATE).getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                getPreferences(MODE_PRIVATE).edit().putBoolean(key, checked).apply()
                if (name == "Visualizer") {
                    visualizerView.visibility = if (checked) View.VISIBLE else View.GONE
                }
                if (name == "Compact mini-player") {
                    val lp = mini.layoutParams as LinearLayout.LayoutParams
                    lp.height = dp(if (checked) 48 else 58)
                    mini.layoutParams = lp
                    bottomBar.post { fitContentPadding() }
                }
            }
        }
    }

    private fun visualizer(): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(2), 0)
            for (height in listOf(6, 12, 16, 9)) {
                addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(accent)
                        cornerRadius = dp(2).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(4), dp(height)).apply {
                        setMargins(dp(1), 0, dp(1), 0)
                    }
                })
            }
        }
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
                    art?.optString("480x480", null)?.takeIf { it.isNotEmpty() }
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun beginScreen(title: String, showAvatar: Boolean) {
        content.removeAllViews()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        head.addView(label(title, 32f, Color.WHITE, boldTf).apply {
            letterSpacing = -0.01f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (showAvatar) {
            val avatar = label("A", 14f, Color.WHITE, boldTf).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(accent, purple)
                ).apply { shape = GradientDrawable.OVAL }
            }
            val aLp = LinearLayout.LayoutParams(dp(34), dp(34))
            aLp.topMargin = dp(4)
            head.addView(avatar, aLp)
        }
        content.addView(head)
        // Cheap 150ms fade so tab switches don't hard-cut.
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(150).start()
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
        handler.removeCallbacks(ticker)
        player?.release()
        executor.shutdownNow()
        super.onDestroy()
    }
}
