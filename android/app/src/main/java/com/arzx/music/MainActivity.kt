package com.arzx.music

import android.app.Activity
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
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors

private data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Int,
    val artworkSmall: String? = null,
    val artworkLarge: String? = null
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
    private var activeTab = "Home"
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

        val bottomBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

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
            Triple("Downloads", R.drawable.ic_download, Runnable { showEmpty("Downloads", "No downloads yet", "Only authorized provider downloads will appear here.") }),
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
        setContentView(root)
        setActiveTab("Home")
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
    }

    // ---------------------------------------------------------------- home

    private fun showHome() {
        val gen = ++generation
        beginScreen("Listen Now", showAvatar = true)
        val loading = label("Loading trending…", 16f, white50)
        content.addView(loading)
        executor.execute {
            val result = fetch("/tracks/trending?limit=25")
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                content.removeView(loading)
                if (result == null || result.isEmpty()) {
                    content.addView(label("No trending tracks", 18f, Color.WHITE, boldTf))
                    content.addView(label("Playable tracks are unavailable right now.", 14f, white50))
                } else {
                    tracks.clear()
                    tracks.addAll(result)
                    queue = result
                    content.addView(featuredCard(result.first()))
                    if (recentTracks.isNotEmpty()) {
                        content.addView(sectionTitle("Recently Played"))
                        addRows(recentTracks, withHairlines = true)
                    } else {
                        content.addView(sectionTitle("Trending Now"))
                        addRows(result, withHairlines = true)
                    }
                }
            }
        }
    }

    private fun featuredCard(track: Track): FrameLayout {
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 20, 23))
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
            setOnClickListener { play(track) }
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
        labels.addView(label("FEATURED MIX", 11f, white75, boldTf).apply { letterSpacing = 0.11f })
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
                val found = fetch("/tracks/search?query=${URLEncoder.encode(q, "UTF-8")}&limit=25")
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    resultsBox.removeAllViews()
                    when {
                        found == null -> resultsBox.addView(label("Playable results are unavailable right now.", 14f, white50))
                        found.isEmpty() -> resultsBox.addView(label("No results", 18f, Color.WHITE, boldTf))
                        else -> {
                            queue = found
                            addRows(found, resultsBox, withHairlines = true)
                        }
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
            setOnClickListener { play(track) }
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
        content.addView(label("Streams via Audius. Metadata via MusicBrainz.", 15f, white50))
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

    private fun addRows(list: List<Track>, target: LinearLayout = content, withHairlines: Boolean) {
        list.forEachIndexed { i, track ->
            if (withHairlines && i > 0) target.addView(hairline())
            addTrackRow(track, target)
        }
    }

    private fun hairline(): View {
        val v = View(this).apply { setBackgroundColor(Color.argb(23, 255, 255, 255)) }
        val lp = LinearLayout.LayoutParams(-1, 1)
        lp.marginStart = dp(65)
        v.layoutParams = lp
        return v
    }

    private fun addTrackRow(track: Track, target: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { play(track) }
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
        text.addView(label(track.artist, 14.5f, white50).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        if (track.id == current?.id) {
            row.addView(visualizer())
        } else {
            row.addView(icon(R.drawable.ic_more, Color.argb(115, 255, 255, 255)), LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        target.addView(row)
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
        val prevBtn = icon(R.drawable.ic_skip_prev, Color.WHITE).apply { setOnClickListener { playPrevious() } }
        npPlayButton = icon(R.drawable.ic_play, Color.WHITE).apply { setOnClickListener { togglePlayback() } }
        val nextBtn = icon(R.drawable.ic_skip_next, Color.WHITE).apply { setOnClickListener { playNext() } }
        controls.addView(prevBtn, LinearLayout.LayoutParams(0, dp(56), 1f))
        controls.addView(npPlayButton, LinearLayout.LayoutParams(0, dp(72), 1f))
        controls.addView(nextBtn, LinearLayout.LayoutParams(0, dp(56), 1f))
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

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(22), 0, dp(22), 0)
        }
        val bottomLp = LinearLayout.LayoutParams(-1, -2)
        bottomLp.topMargin = dp(30)
        bottomRow.layoutParams = bottomLp
        val lyricsBtn = icon(R.drawable.ic_lyrics, white75).apply {
            setOnClickListener { Toast.makeText(this@MainActivity, "Lyrics: Not available yet", Toast.LENGTH_SHORT).show() }
        }
        val airBtn = icon(R.drawable.ic_airplay, white75).apply {
            setOnClickListener { Toast.makeText(this@MainActivity, "Casting is not available in this build", Toast.LENGTH_SHORT).show() }
        }
        val queueBtn = icon(R.drawable.ic_queue, white75).apply {
            setOnClickListener { hideNowPlaying() }
        }
        bottomRow.addView(lyricsBtn, LinearLayout.LayoutParams(0, dp(32), 1f))
        bottomRow.addView(airBtn, LinearLayout.LayoutParams(0, dp(32), 1f))
        bottomRow.addView(queueBtn, LinearLayout.LayoutParams(0, dp(32), 1f))
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
    }

    private fun hideNowPlaying() {
        nowPlaying.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (::nowPlaying.isInitialized && nowPlaying.visibility == View.VISIBLE) {
            hideNowPlaying()
        } else {
            super.onBackPressed()
        }
    }

    private fun updateProgress() {
        if (!::nowPlaying.isInitialized || nowPlaying.visibility != View.VISIBLE || seeking) return
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
        val idx = queue.indexOfFirst { it.id == track.id }
        if (idx >= 0) {
            queueIndex = idx
        } else {
            queue = listOf(track)
            queueIndex = 0
        }
        current = track
        saveRecent(track)
        refreshPlaybackUi()
        val stream = "https://api.audius.co/v1/tracks/${track.id}/stream?app_name=arzxsh"
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(stream)
            setOnPreparedListener {
                it.start()
                refreshPlaybackUi()
            }
            setOnCompletionListener { refreshPlaybackUi() }
            setOnErrorListener { _, _, _ ->
                refreshPlaybackUi()
                Toast.makeText(this@MainActivity, "This track could not be played.", Toast.LENGTH_SHORT).show()
                true
            }
            prepareAsync()
        }
    }

    private fun playNext() {
        if (queue.isEmpty() || queueIndex < 0) return
        val next = queueIndex + 1
        if (next < queue.size) {
            play(queue[next])
        } else {
            Toast.makeText(this, "No next track", Toast.LENGTH_SHORT).show()
        }
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
        val prev = queueIndex - 1
        if (prev >= 0) {
            play(queue[prev])
        } else {
            try {
                p?.seekTo(0)
            } catch (_: IllegalStateException) {
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
                    o.optString("artworkLarge", null)?.takeIf { it.isNotEmpty() }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRecent(track: Track) {
        recentTracks.removeAll { it.id == track.id }
        recentTracks.add(0, track)
        while (recentTracks.size > 10) recentTracks.removeAt(recentTracks.size - 1)
        val arr = JSONArray()
        recentTracks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("artist", t.artist)
                put("duration", t.duration)
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
