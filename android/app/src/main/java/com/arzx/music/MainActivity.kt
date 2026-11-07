package com.arzx.music

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.AudioEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import android.provider.OpenableColumns
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
const val SOURCE_LOCAL = "local"

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
 * Artwork loader for the UI layer: downloads artwork on a background
 * pool, caches bitmaps, and guards against view recycling by tagging
 * each ImageView with the URL it is waiting for.
 *
 * v5.1 jank pass: bitmaps are decoded AT the size they will be shown
 * (two-pass sampled decode into size buckets), never at the source's
 * full resolution — YouTube's large thumbnails are 2268px squares,
 * and decoding those for a 52dp row was the artwork half of the
 * scroll/touch jank on the Helio G95. Cache keys carry the bucket,
 * so the same URL can live at row size and Now Playing size side by
 * side. Bytes are fetched once per decode; the bounds pass runs on
 * the downloaded bytes, not on a second request.
 */
private object ArtworkLoader {
    private val cache = LruCache<String, Bitmap>(128)
    private val pool = Executors.newFixedThreadPool(3)

    /** Bytes currently held by the in-memory artwork cache. */
    fun cacheBytes(): Long = cache.snapshot().values.sumOf { it.byteCount.toLong() }

    /** Drops every cached bitmap (Settings → Clear cache). */
    fun clearCache() {
        cache.evictAll()
    }

    /** Decode bucket (long-edge px) for a requested display size. */
    private fun bucketFor(targetPx: Int): Int = when {
        targetPx <= 128 -> 128
        targetPx <= 256 -> 256
        targetPx <= 512 -> 512
        targetPx <= 1024 -> 1024
        else -> 2048
    }

    fun load(url: String?, into: ImageView) = load(url, into, null)

    /**
     * Loads [url]; on failure tries [fallbackUrl] before giving up.
     * (v4 loaded exactly one URL: when the small YouTube thumbnail
     * failed, the mini player rendered an empty box even though the
     * large artwork was right there.)
     */
    fun load(url: String?, into: ImageView, fallbackUrl: String?) {
        val lp = into.layoutParams
        val target = if (lp != null && lp.width > 0) lp.width else 512
        load(url, into, fallbackUrl, target)
    }

    /** [targetPx] is the display size the caller knows (row height,
     *  mini player size, Now Playing width); decode size follows it. */
    fun load(url: String?, into: ImageView, fallbackUrl: String?, targetPx: Int) {
        if (url.isNullOrEmpty()) {
            if (!fallbackUrl.isNullOrEmpty() && fallbackUrl != url) {
                load(fallbackUrl, into, null, targetPx)
            } else {
                into.tag = null
                into.setImageDrawable(null)
            }
            return
        }
        val bucket = bucketFor(targetPx)
        val key = "$url@$bucket"
        into.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            into.setImageBitmap(hit)
            return
        }
        into.setImageDrawable(null)
        pool.execute {
            var bmp = decode(url, bucket)
            if (bmp != null) {
                cache.put(key, bmp)
            } else if (!fallbackUrl.isNullOrEmpty() && fallbackUrl != url) {
                bmp = decode(fallbackUrl, bucket)
                if (bmp != null) cache.put(key, bmp)
            }
            val result = bmp
            into.post {
                if (result != null && into.tag == key) into.setImageBitmap(result)
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
            // The blur output is 28px wide; decoding anything bigger
            // is pure waste (v5 decoded the full-size artwork first).
            val small = decode(url, 128)
            val blurred = small?.let { blur(it) }
            if (blurred != null) cache.put(key, blurred)
            into.post {
                if (blurred != null && into.tag == key) into.setImageBitmap(blurred)
            }
        }
    }

    private fun downloadBytes(url: String): ByteArray? = try {
        if (url.startsWith("file:")) {
            // Local artwork extracted at import time lives on disk.
            java.io.FileInputStream(java.io.File(java.net.URI(url))).use { it.readBytes() }
        } else {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                // Some artwork CDNs refuse the default Java user agent;
                // identify the app like every other request it makes.
                setRequestProperty("User-Agent", "arzxsh (Android)")
            }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { it.readBytes() }
        }
    } catch (_: Exception) {
        null
    }

    /** Sampled decode: the result's dimensions land within
     *  [maxPx, 2×maxPx) on both axes instead of the source size. */
    private fun decode(url: String, maxPx: Int): Bitmap? {
        val bytes = downloadBytes(url) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxPx &&
                    bounds.outHeight / (sample * 2) >= maxPx
                ) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (_: Exception) {
            null
        }
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

/**
 * One playback engine instance: a Media3 ExoPlayer plus the state
 * the activity's engine semantics need around it.
 *
 * Why ExoPlayer (v5.1): googlevideo stream URLs are bound to the
 * client context that requested them. ExoPlayer's HTTP data source
 * sends the SAME User-Agent as that context, per player instance;
 * MediaPlayer could not set one at all, and Stagefright's default
 * UA got resolved URLs rejected on device (what=-38). [retired]
 * replaces MediaPlayer's listener-detach trick: when a new track
 * starts, the outgoing instance is retired and every callback checks
 * it, so a stale completion or error can never fire into the new
 * track's session. All calls happen on the main thread (ExoPlayer's
 * contract) and are exception-shielded like the MediaPlayer code was.
 */
@UnstableApi
private class EnginePlayer(val exo: ExoPlayer) {
    @Volatile
    var retired = false

    @Volatile
    var readyFired = false

    var listener: Player.Listener? = null

    val isPlaying: Boolean
        get() = try { exo.isPlaying } catch (_: Exception) { false }

    val isEnded: Boolean
        get() = try { exo.playbackState == Player.STATE_ENDED } catch (_: Exception) { false }

    /** Duration in ms; negative (C.TIME_UNSET) until the player knows it. */
    val durationMs: Long
        get() = try { exo.duration } catch (_: Exception) { 0L }

    val positionMs: Long
        get() = try { exo.currentPosition } catch (_: Exception) { 0L }

    val audioSessionId: Int
        get() = try { exo.audioSessionId } catch (_: Exception) { 0 }

    fun setVolume(v: Float) {
        try { exo.volume = v.coerceIn(0f, 1f) } catch (_: Exception) {}
    }

    fun play() {
        try { exo.play() } catch (_: Exception) {}
    }

    fun pause() {
        try { exo.pause() } catch (_: Exception) {}
    }

    fun seekTo(ms: Long) {
        try { exo.seekTo(ms.coerceAtLeast(0L)) } catch (_: Exception) {}
    }

    fun release() {
        retired = true
        try { listener?.let { exo.removeListener(it) } } catch (_: Exception) {}
        try { exo.release() } catch (_: Exception) {}
    }
}

@UnstableApi
class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    /** Playback-only executor. v5 ran stream resolution on the shared
     *  [executor], where a single in-flight DOWNLOAD (minutes long)
     *  blocked every YouTube resolution queued behind it — a real
     *  chunk of the "playback starts slowly" complaint. Resolution,
     *  the retry ladder, radio and the Audius fallback live here;
     *  catalog/search/downloads stay on [executor]. */
    private val playerExecutor = Executors.newSingleThreadExecutor()
    private val tracks = mutableListOf<Track>()
    private var player: EnginePlayer? = null
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
    private lateinit var npError: TextView
    /** One-shot Now Playing notice (e.g. the Audius fallback line),
     *  shown when the next stream successfully prepares. */
    private var pendingNpNotice: String? = null
    private val manualIndices = mutableSetOf<Int>()
    private var sleepAfterTrack = false
    private var sleepAfterQueue = false
    private var artWasPlaying = false

    // ---- v5: crossfade (dual player) ----
    /** The outgoing player while a crossfade runs; null otherwise. */
    private var outPlayer: EnginePlayer? = null
    /** Track the CURRENT [player] instance is playing (for the
     *  repeat-one no-fade rule and crossfade bookkeeping). */
    private var activePlayerTrack: Track? = null
    private var fadeStep = 0
    private var fadeTotalSteps = 0
    private val crossfadeTick = object : Runnable {
        override fun run() {
            val out = outPlayer
            val cur = player
            if (out == null || cur == null) return
            fadeStep++
            try {
                val t = (fadeStep.toFloat() / fadeTotalSteps).coerceIn(0f, 1f)
                out.setVolume(1f - t)
                cur.setVolume(t)
                if (fadeStep < fadeTotalSteps) {
                    handler.postDelayed(this, 100)
                } else {
                    finishCrossfadeNow()
                }
            } catch (_: Exception) {
                finishCrossfadeNow()
            }
        }
    }

    // ---- v5.1: YouTube retry ladder + crossfade source ----
    /** The player that was AUDIBLE when the current track was
     *  requested. Held across resolution (and across ladder attempts)
     *  so a crossfade can still start from the track the user is
     *  actually hearing; consumed by the next successful start. */
    private var fadeSource: EnginePlayer? = null
    /** Track the active YouTube ladder belongs to. */
    private var ytTrack: Track? = null
    /** Stage labels still to try, in order (see StreamResolver). */
    private var ytStageLabels: List<String> = emptyList()
    private var ytStage = -1
    /** Known-good cached resolution prepended as stage "CACHE". */
    private var ytCached: StreamResolver.Resolved? = null
    /** One report line per failed stage, printed if the ladder dies. */
    private val ytReports = mutableListOf<String>()
    private var ytQuality = "normal"

    // ---- v5.1: ticker change-guards (jank pass) ----
    private var lastNpElapsed = ""
    private var lastNpRemaining = ""
    private var lastSleepLabel = ""

    // ---- v5: downloads vault glue ----
    /** key -> "pause" | "remove": how an in-flight download should end. */
    private val dlStopModes = mutableMapOf<String, String>()
    /** Progress views of the visible Downloads rows, by track key. */
    private val dlProgressViews = mutableMapOf<String, Pair<ProgressBar, TextView>>()

    // ---- v5: listening stats accumulation ----
    private var sessionListenedMs = 0L
    private var lastTickMs = 0L

    // ---- v5: lyrics overlay ----
    private lateinit var lyricsOverlay: FrameLayout
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsList: LinearLayout
    private lateinit var lyricsTitle: TextView
    private lateinit var lyricsStatus: TextView
    private var lyricsSyncedLines: List<LyriTimber> = emptyList()
    private var lyricsLineViews: List<TextView> = emptyList()
    private var lyricsActiveLine = -2
    /** Downloads.key() of the track the overlay currently shows. */
    private var lyricsKey: String? = null
    /** True while a lyrics fetch is in flight (double-tap guard). */
    private var lyricsLoading = false

    /** Activity-result code for the local-file document picker. */
    private val reqImport = 4205

    /** Which full-screen section is showing (for live row refresh). */
    private var visibleScreen = "home"

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
            accumulateListening()
            updateProgress()
            updateLyricsHighlight()
            handler.postDelayed(this, 500)
        }
    }

    /**
     * Listening-time accumulation for the stats screen: while audio
     * is really playing, add the elapsed wall time since the last
     * tick. Flush to the store in ~5s chunks (and at every play()
     * and on destroy) so the total survives process death.
     */
    private fun accumulateListening() {
        val now = System.currentTimeMillis()
        if (lastTickMs == 0L) {
            lastTickMs = now
            return
        }
        val delta = now - lastTickMs
        lastTickMs = now
        if (delta in 1..2000 && isPlayingSafe()) {
            sessionListenedMs += delta
            if (sessionListenedMs >= 5000) flushListened()
        }
    }

    private fun flushListened() {
        if (sessionListenedMs <= 0) return
        PlayHistory.addListened(this, sessionListenedMs)
        sessionListenedMs = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        recentTracks.addAll(loadRecents())
        // Last-good Innertube client memory persists on disk so the
        // stream resolver tries the working context first.
        YtmApi.initStore(this)
        // Any download left "downloading" by a dead process was in
        // fact interrupted: the vault says so, on the record.
        Downloads.reconcileOnStart(this)
        backfillFavorites()
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        showShell()
        showHome()
        handler.post(ticker)
    }

    /**
     * One-time rescue of pre-v5 likes: v4.1 stored favorites as bare
     * `fav_<id>` booleans with no track metadata. Where the track
     * can be found again (recents, downloads, local files), lift it
     * into the Liked Songs store so it shows up in the list.
     */
    private fun backfillFavorites() {
        val prefs = getPreferences(MODE_PRIVATE)
        val legacyIds = prefs.all.keys
            .filter { it.startsWith("fav_") && prefs.getBoolean(it, false) }
            .map { it.removePrefix("fav_") }
        if (legacyIds.isEmpty()) return
        val pool = HashMap<String, Track>()
        recentTracks.forEach { pool[it.id] = it }
        Downloads.entries(this).forEach { pool[it.track.id] = it.track }
        LocalTracks.all(this).forEach { pool[it.id] = it }
        legacyIds.forEach { id ->
            pool[id]?.let { t -> if (!Favorites.contains(this, t)) Favorites.add(this, t) }
        }
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
        // Fill is near-opaque on purpose: the bars float over a
        // full-screen ScrollView, and at glass alpha (~60%) list rows
        // scrolling underneath stayed visible THROUGH the bars (v4
        // on-device bug). The stroke highlight keeps the glass read.
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBg(16f, Color.argb(243, 38, 38, 40), Color.argb(31, 255, 255, 255))
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
            contentDescription = "Play"
            setOnClickListener { togglePlayback() }
        }
        mini.addView(miniPlayButton, LinearLayout.LayoutParams(dp(38), dp(38)))
        miniNextButton = icon(R.drawable.ic_next, Color.WHITE).apply {
            contentDescription = "Next track"
            setOnClickListener { playNext() }
        }
        mini.addView(miniNextButton, LinearLayout.LayoutParams(dp(38), dp(38)))
        visualizerView = visualizer().apply {
            // State comes from updateVisualizerState() (setting AND
            // actual playback); starting hidden avoids showing "playing"
            // bars before anything has ever played.
            visibility = View.GONE
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
            background = glassBg(999f, Color.argb(243, 30, 30, 32), Color.argb(36, 255, 255, 255))
            setPadding(dp(8), 0, dp(8), 0)
            elevation = dp(12).toFloat()
        }
        val tabs = listOf(
            Triple("Home", R.drawable.ic_home, Runnable { showHome() }),
            Triple("Search", R.drawable.ic_search, Runnable { showSearch() }),
            Triple("Library", R.drawable.ic_library, Runnable { showLibrary() }),
            Triple("Downloads", R.drawable.ic_download, Runnable { showDownloads() }),
            Triple("Settings", R.drawable.ic_settings, Runnable { showSettings() })
        )
        tabs.forEach { (name, iconRes, action) ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = name
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
        buildLyricsOverlay(root)
        buildMenuOverlay(root)
        setContentView(root)
        setActiveTab("Home")
        // Re-fit on EVERY bar layout, not one posted runnable: the v4
        // post{} could fire before the bars were measured (height 0,
        // guard kept the stale default), and later height changes
        // (compact mini-player) were only covered if a post happened
        // to land after layout. A layout listener cannot miss.
        bottomBar.viewTreeObserver.addOnGlobalLayoutListener { fitContentPadding() }
        bottomBar.post { fitContentPadding() }
    }

    private var lastFitPadding = -1

    /**
     * Sizes the scroll content's bottom inset from the MEASURED floating
     * bars (mini player + tab bar) plus breathing room, so the last rows
     * can always scroll fully clear of both bars on any device. (v3 used
     * a fixed guess and rows ended up hidden underneath.) Guarded
     * against re-applying the same value, which would loop layouts.
     */
    private fun fitContentPadding() {
        if (!::bottomBar.isInitialized || !::content.isInitialized) return
        val target = bottomBar.height + dp(28)
        if (bottomBar.height > 0 && target != lastFitPadding) {
            lastFitPadding = target
            content.setPadding(dp(22), dp(10), dp(22), target)
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
        val mode = sourceMode()
        executor.execute {
            val audius = fetch("/tracks/trending?limit=25")
            var charts: YtmCharts? = null
            var chartsErr: String? = null
            // Audius mode fires no YouTube calls at all.
            if (mode != "audius") {
                try {
                    charts = YtmApi.charts()
                } catch (t: Throwable) {
                    chartsErr = shortErr(t)
                }
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                content.removeView(loading)
                var any = false
                if (audius != null && audius.isNotEmpty()) {
                    tracks.clear()
                    tracks.addAll(audius)
                }
                // Featured prefers YouTube Music (the primary source);
                // Audius headlines only when charts are unavailable.
                val featured = if (mode == "audius") {
            audius?.firstOrNull() ?: charts?.songs?.firstOrNull()
        } else {
            charts?.songs?.firstOrNull() ?: audius?.firstOrNull()
        }
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
                // Made for You: an on-device mix — YouTube Music radio
                // seeded by his most-played YouTube track, labeled as
                // generated here. No seed (nothing played yet, or
                // Audius mode) → the section simply doesn't exist.
                if (mode != "audius") {
                    val seed = PlayHistory.topTracks(this@MainActivity, 20)
                        .firstOrNull { it.first.source == SOURCE_YOUTUBE }?.first
                    if (seed != null) {
                        content.addView(sectionTitle("Made for You"))
                        val mixBox = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                        }
                        mixBox.addView(statusLine("Generating from your top plays…"))
                        content.addView(mixBox)
                        any = true
                        executor.execute {
                            var mix: List<Track>? = null
                            var mixErr: String? = null
                            try {
                                mix = YtmApi.radio(seed.id)
                            } catch (t: Throwable) {
                                mixErr = shortErr(t)
                            }
                            runOnUiThread {
                                if (gen != generation) return@runOnUiThread
                                mixBox.removeAllViews()
                                val m = mix
                                if (m == null || m.isEmpty()) {
                                    mixBox.addView(
                                        statusLine("Mix unavailable: ${mixErr ?: "empty radio"}")
                                    )
                                } else {
                                    mixBox.addView(
                                        statusLine(
                                            "Generated on this device · radio from “${seed.title}” by ${seed.artist}"
                                        )
                                    )
                                    addRows(
                                        m.take(15), mixBox,
                                        withHairlines = true, queueContext = m
                                    )
                                }
                            }
                        }
                    }
                }
                // In YouTube Music mode the Audius section is the
                // fallback: it appears only when the charts failed
                // or came back empty. Audius/Both modes always show it.
                val showAudiusSection = mode != "youtube" ||
                    charts == null || charts.songs.isEmpty()
                if (showAudiusSection && audius != null && audius.size > 1) {
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
        ArtworkLoader.load(largeArt(track), img, track.artworkSmall)
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
            resultsBox.addView(sectionTitle("Results for \"$q\""))
            resultsBox.addView(label("Searching catalogs…", 14f, white50))
            val mode = sourceMode()
            executor.execute {
                val audius = fetch("/tracks/search?query=${URLEncoder.encode(q, "UTF-8")}&limit=25")
                // Every YouTube call is separately guarded and its
                // failure is RENDERED below the results (v3 swallowed
                // the exception and YouTube quietly never appeared).
                var ytSongs: List<Track> = emptyList()
                var ytNote: String? = null
                var ytErr: String? = null
                // Audius mode fires NO YouTube calls at all.
                if (mode != "audius") try {
                    val r = YoutubeSource.searchSongs(q)
                    ytSongs = r.tracks
                    ytNote = r.note
                } catch (t: Throwable) {
                    ytErr = shortErr(t)
                }
                var ytArtists: List<YtmArtistRef> = emptyList()
                var ytArtistsErr: String? = null
                if (mode != "audius") try {
                    ytArtists = YtmApi.searchArtists(q)
                } catch (t: Throwable) {
                    ytArtistsErr = shortErr(t)
                }
                var ytAlbums: List<YtmAlbumRef> = emptyList()
                var ytAlbumsErr: String? = null
                if (mode != "audius") try {
                    ytAlbums = YtmApi.searchAlbums(q)
                } catch (t: Throwable) {
                    ytAlbumsErr = shortErr(t)
                }
                // One merged song list, YouTube Music FIRST (it is
                // the primary source now), Audius after — both fully
                // present and playable, and next/previous playback
                // flows across sources.
                // Composition follows the source mode: YouTube
                // Music leads; Audius-only in Audius mode; in
                // YouTube mode Audius appears only as the fallback
                // when YouTube songs failed or came back empty.
                val found = mutableListOf<Track>()
                when (mode) {
                    "audius" -> audius?.let { found.addAll(it) }
                    "youtube" -> {
                        if (ytSongs.isNotEmpty()) found.addAll(ytSongs)
                        else audius?.let { found.addAll(it) }
                    }
                    else -> {
                        found.addAll(ytSongs)
                        audius?.let { found.addAll(it) }
                    }
                }
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    resultsBox.removeAllViews()
                    resultsBox.addView(sectionTitle("Results for \"$q\""))
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
                    if (mode == "youtube" && ytSongs.isEmpty() && !audius.isNullOrEmpty()) {
                        // Search-level fallback, stated on screen:
                        // YouTube failed or came back empty, so
                        // Audius is carrying this search.
                        resultsBox.addView(statusLine("YouTube songs unavailable — showing Audius results instead."))
                    }
                    if (mode == "audius") {
                        resultsBox.addView(statusLine("Source: Audius only — YouTube Music is off."))
                    }
                    if (ytNote != null) {
                        resultsBox.addView(statusLine("YouTube: $ytNote"))
                    }
                    if (mode != "audius" && errors.isEmpty() && ytNote == null) {
                        resultsBox.addView(
                            statusLine(
                                "YouTube Music: ${ytSongs.size} songs · ${ytArtists.size} artists · ${ytAlbums.size} albums"
                            )
                        )
                    }
                }
            }
        }

        // The search field is the FIRST thing on the screen, above
        // the source selector, and impossible to miss: full width,
        // 48dp tall, white text with a visible cursor, and a clear
        // (✕) button while it holds text. v5's field sat UNDER the
        // segmented control at 38dp with no clear button, and the
        // activity declared no soft-input mode, so typed text could
        // end up hidden behind the keyboard — a tester searched and
        // never saw her own query (2026-11-07). The manifest now
        // sets adjustResize; submitting keeps the query in the field
        // and echoes it over the results ("Results for …").
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(38, 255, 255, 255))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), 0, dp(6), 0)
        }
        val fLp = LinearLayout.LayoutParams(-1, dp(48))
        fLp.topMargin = dp(14)
        field.layoutParams = fLp
        field.addView(icon(R.drawable.ic_search, white50), LinearLayout.LayoutParams(dp(18), dp(18)))
        val clearBtn = label("✕", 17f, white55).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
            contentDescription = "Clear search"
            setOnClickListener {
                input.text?.clear()
                resultsBox.removeAllViews()
                resultsBox.visibility = View.GONE
                browseBox.visibility = View.VISIBLE
                input.requestFocus()
            }
        }
        input = EditText(this).apply {
            hint = "Artists, Songs, Lyrics and More"
            setTextColor(Color.WHITE)
            setHintTextColor(white50)
            setTextSize(16.5f)
            setSingleLine(true)
            background = null
            isCursorVisible = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8), 0, 0, 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runSearch(text.toString())
                    // Results get the screen; the query stays in the
                    // field above them and is echoed over the results.
                    val imm = getSystemService(INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                    clearFocus()
                    true
                } else false
            }
        }
        field.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        field.addView(clearBtn, LinearLayout.LayoutParams(dp(38), -1))

        // Source selector: the same three states as Settings →
        // Music source, two-way synced through the one preference.
        // Glass pill track, accent fill on the active segment.
        val segRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glassBg(999f, Color.argb(28, 255, 255, 255), Color.argb(20, 255, 255, 255))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val segLp = LinearLayout.LayoutParams(-1, dp(40))
        segLp.topMargin = dp(12)
        segRow.layoutParams = segLp
        val segViews = ArrayList<Pair<String, TextView>>()
        fun renderSegs() {
            val active = sourceMode()
            segViews.forEach { (mode, tv) ->
                val selected = mode == active
                tv.background = if (selected) {
                    GradientDrawable().apply {
                        setColor(accent)
                        cornerRadius = dp(999).toFloat()
                    }
                } else null
                tv.setTextColor(if (selected) Color.WHITE else white55)
            }
        }
        listOf(
            "youtube" to "YouTube Music",
            "audius" to "Audius",
            "both" to "Both"
        ).forEach { (mode, name) ->
            val tv = label(name, 13f, white55, mediumTf).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener {
                    if (sourceMode() != mode) {
                        setSourceMode(mode)
                        renderSegs()
                        val q = input.text?.toString().orEmpty()
                        if (q.isNotBlank()) runSearch(q)
                    }
                }
            }
            segRow.addView(tv, LinearLayout.LayoutParams(0, -1, 1f))
            segViews += mode to tv
        }
        renderSegs()
        content.addView(field)
        content.addView(segRow)

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
                    // The field shows what was actually searched.
                    input.setText(term)
                    runSearch(term)
                }, cardLp)
            }
            browseBox.addView(rowLayout)
        }
        // Honest label: the four cards above run a SEARCH for their
        // term. Real catalog browsing lives in Moods & Genres below.
        browseBox.addView(statusLine("These four cards run a search."))
        browseBox.addView(sectionTitle("Moods & Genres"))
        val moodsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        browseBox.addView(moodsBox)
        if (sourceMode() == "audius") {
            moodsBox.addView(
                label("Moods live on YouTube Music — switch the source above to browse them.", 14.5f, white50)
            )
        } else {
            moodsBox.addView(label("Loading moods…", 14.5f, white50))
            executor.execute {
                var cats: List<YtmMoodCategory>? = null
                var err: String? = null
                try {
                    cats = YtmApi.moodCategories()
                } catch (t: Throwable) {
                    err = shortErr(t)
                }
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    moodsBox.removeAllViews()
                    val list = cats
                    if (list == null || list.isEmpty()) {
                        moodsBox.addView(errorLine("Moods unavailable: ${err ?: "empty response"}"))
                    } else {
                        var lastGroup: String? = null
                        var chipRow: LinearLayout? = null
                        var inRow = 0
                        list.forEach { cat ->
                            if (cat.group != lastGroup) {
                                lastGroup = cat.group
                                chipRow = null
                                inRow = 0
                                if (cat.group.isNotEmpty()) {
                                    moodsBox.addView(label(cat.group, 15f, Color.WHITE, boldTf).apply {
                                        val lp = LinearLayout.LayoutParams(-1, -2)
                                        lp.topMargin = dp(16)
                                        lp.bottomMargin = dp(8)
                                        layoutParams = lp
                                    })
                                }
                            }
                            if (chipRow == null || inRow >= 2) {
                                chipRow = LinearLayout(this).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    setPadding(0, 0, 0, dp(10))
                                }
                                moodsBox.addView(chipRow)
                                inRow = 0
                            }
                            val chip = label(cat.name, 14f, Color.WHITE, mediumTf).apply {
                                gravity = Gravity.CENTER
                                background = glassBg(
                                    999f, Color.argb(28, 255, 255, 255),
                                    Color.argb(20, 255, 255, 255)
                                )
                                setPadding(dp(10), dp(10), dp(10), dp(10))
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                isClickable = true
                                setOnClickListener { showMoodPlaylists(cat) }
                            }
                            val cLp = LinearLayout.LayoutParams(0, -2, 1f)
                            if (inRow == 0) cLp.marginEnd = dp(10)
                            chipRow.addView(chip, cLp)
                            inRow++
                        }
                    }
                }
            }
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

    /** Small-caps group header, the reference apps' settings shape. */
    private fun settingsGroupHeader(text: String): TextView {
        val tv = label(text.uppercase(), 12.5f, white50, boldTf).apply { letterSpacing = 0.12f }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(26)
        lp.bottomMargin = dp(4)
        tv.layoutParams = lp
        return tv
    }

    /** Tappable settings row: title + optional subtitle + chevron. */
    private fun settingsRow(
        title: String,
        subtitle: String?,
        titleColor: Int = Color.WHITE,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(11))
            rippleRect()
            isClickable = true
            setOnClickListener { onClick() }
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(title, 15.5f, titleColor))
        if (subtitle != null) text.addView(label(subtitle, 13f, white50))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("›", 22f, white50))
        return row
    }

    private fun fmtBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** Crossfade length picker: a real 0–12s slider (0 = off, the
     *  default). Writes the same "crossfade_seconds" pref the player
     *  reads on every transition, so changes apply to the next track
     *  change with no restart. */
    private fun showCrossfadeSheet() {
        val current = crossfadeSec()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val value = label(
            if (current == 0) "Off" else "$current seconds", 16f, Color.WHITE, mediumTf
        )
        box.addView(value)
        val seek = SeekBar(this).apply {
            max = 12
            progress = current
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = if (progress == 0) "Off" else "$progress seconds"
                    getPreferences(MODE_PRIVATE).edit()
                        .putInt("crossfade_seconds", progress).apply()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val sLp = LinearLayout.LayoutParams(-1, -2)
        sLp.topMargin = dp(10)
        box.addView(seek, sLp)
        box.addView(
            label("Applies when one track flows into the next. Repeat One never fades.", 13f, white50)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Crossfade")
            .setView(box)
            .setPositiveButton("Done") { _, _ -> showSettings() }
            .show()
    }

    private fun setStreamQuality(q: String) {
        getPreferences(MODE_PRIVATE).edit().putString("stream_quality", q).apply()
        val name = when (q) {
            "data" -> "Data saver"
            "high" -> "High"
            else -> "Normal"
        }
        Toast.makeText(this, "Streaming quality: $name", Toast.LENGTH_SHORT).show()
        showSettings()
    }

    /**
     * Opens the device's system audio-effects panel (equalizer) for
     * the player's audio session. Some devices ship no panel at all;
     * that case gets an honest message, not a dead row.
     */
    private fun openEqualizer() {
        val sessionId = try {
            player?.audioSessionId
        } catch (_: Exception) {
            null
        } ?: 0
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No system equalizer available on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLicenses() {
        generation++
        beginScreen("Licenses", showAvatar = false)
        fun block(title: String, body: String, first: Boolean) {
            val h = label(title, 17f, Color.WHITE, boldTf)
            val hLp = LinearLayout.LayoutParams(-1, -2)
            if (!first) hLp.topMargin = dp(24)
            h.layoutParams = hLp
            content.addView(h)
            content.addView(label(body, 14f, white55))
        }
        block(
            "NewPipe Extractor",
            "Copyright (C) 2015-2026 the NewPipe team and contributors.\n" +
                "Licensed under the GNU General Public License v3.0 (GPL-3.0). " +
                "This app uses it to resolve YouTube streams on-device; no part of it is a server.\n" +
                "Source: github.com/TeamNewPipe/NewPipeExtractor\n" +
                "Full license text: gnu.org/licenses/gpl-3.0",
            first = true
        )
        block(
            "Rhino (Mozilla)",
            "JavaScript engine bundled with NewPipe Extractor.\n" +
                "Licensed under the Mozilla Public License 2.0 (MPL-2.0).\n" +
                "Source: github.com/mozilla/rhino",
            first = false
        )
        block(
            "Trademarks",
            "YouTube and YouTube Music are trademarks of Google LLC. " +
                "Audius is a trademark of Audius, Inc. arzxsh is not affiliated with or endorsed by either.",
            first = false
        )
    }

    /**
     * Full settings surface, grouped like the reference apps
     * (Playback / Audio / Downloads & storage / Appearance / About).
     * Every row works; rows whose behavior cannot be implemented for
     * real (volume normalization — no per-track loudness metadata
     * exists from either source) are omitted, not faked.
     */
    private fun showSettings() {
        generation++
        beginScreen("Settings", showAvatar = false)

        content.addView(settingsGroupHeader("Playback"))
        content.addView(
            settingsRow(
                "Music source",
                sourceModeName(sourceMode()) + " · search, home and playback follow this"
            ) {
                showMenu(
                    "Music source",
                    listOf(
                        "YouTube Music" to { setSourceMode("youtube"); showSettings() },
                        "Audius" to { setSourceMode("audius"); showSettings() },
                        "Both" to { setSourceMode("both"); showSettings() }
                    )
                )
            }
        )
        val qualityName = when (streamQuality()) {
            "data" -> "Data saver"
            "high" -> "High"
            else -> "Normal"
        }
        val qualitySub = if (dataSaverOn()) {
            "Data saver mode is on — the lowest stream quality is used"
        } else {
            "$qualityName · steers YouTube stream selection"
        }
        content.addView(settingsRow("Streaming quality", qualitySub) {
            showMenu(
                "Streaming quality", listOf(
                    "Data saver" to { setStreamQuality("data") },
                    "Normal" to { setStreamQuality("normal") },
                    "High" to { setStreamQuality("high") }
                )
            )
        })
        content.addView(setting("Data saver", false) { showSettings() })
        content.addView(setting("Autoplay", true))
        val cfSec = crossfadeSec()
        content.addView(
            settingsRow(
                "Crossfade",
                if (cfSec == 0) "Off · tracks hard-cut, as before" else "$cfSec seconds between tracks"
            ) { showCrossfadeSheet() }
        )

        content.addView(settingsGroupHeader("Audio"))
        content.addView(settingsRow("Equalizer", "Opens the system audio effects panel") {
            openEqualizer()
        })

        content.addView(settingsGroupHeader("Downloads & storage"))
        val dlCount = Downloads.entries(this).size
        val dlBytes = Downloads.storageBytes(this)
        content.addView(settingsRow("Downloads", "$dlCount tracks · ${fmtBytes(dlBytes)} on this device") {
            setActiveTab("Downloads")
            showDownloads()
        })
        if (dlCount > 0) {
            content.addView(settingsRow("Remove all downloads", "Frees ${fmtBytes(dlBytes)}", accent) {
                showMenu(
                    "Remove all downloaded tracks?", listOf(
                        "Remove all" to {
                            Downloads.removeAll(this@MainActivity)
                            Toast.makeText(this@MainActivity, "Downloads removed", Toast.LENGTH_SHORT).show()
                            showSettings()
                        },
                        "Cancel" to {}
                    )
                )
            })
        }
        content.addView(settingsRow("Artwork cache", "${fmtBytes(ArtworkLoader.cacheBytes())} in memory") {
            ArtworkLoader.clearCache()
            Toast.makeText(this@MainActivity, "Artwork cache cleared", Toast.LENGTH_SHORT).show()
            showSettings()
        })
        content.addView(settingsRow("Lyrics cache", "${fmtBytes(Lyrics.cacheBytes(this))} on this device") {
            Lyrics.clearCache(this@MainActivity)
            Toast.makeText(this@MainActivity, "Lyrics cache cleared", Toast.LENGTH_SHORT).show()
            showSettings()
        })

        content.addView(settingsGroupHeader("Appearance & behavior"))
        content.addView(setting("Visualizer", true))
        content.addView(setting("Compact mini-player", false))
        content.addView(setting("Reduce motion", false))

        content.addView(settingsGroupHeader("About"))
        // Read from the build, never hardcoded (v4 shipped a stale
        // literal "Version 0.2.0" long after the app had moved on).
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        } ?: "?"
        content.addView(label("Version $ver", 15f, Color.WHITE))
        content.addView(label("arzxsh by Arafat Arzu", 15f, white50))
        content.addView(label("Sources · YouTube Music · Audius · On-device files", 15f, white50))
        content.addView(label("Lyrics · LRCLIB / lyrics.ovh when available", 15f, white50))
        content.addView(label("No DRM bypass, ever.", 15f, white50))
        content.addView(settingsRow("Open-source licenses", null) { showLicenses() })
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

    private fun addTrackRow(
        track: Track,
        target: LinearLayout,
        queueContext: List<Track>? = null,
        menuOverride: (() -> Unit)? = null
    ) {
        val openMenu = { if (menuOverride != null) menuOverride() else showTrackMenu(track) }
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
                openMenu()
                true
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 9)
        }
        ArtworkLoader.load(track.artworkSmall ?: track.artworkLarge, img, null, dp(104))
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
        // No source badges in rows (product decision, 2026-11-06):
        // Track.source is still tracked internally for playback
        // resolution, radio and downloads, just never displayed.
        if (Downloads.isDownloaded(this, track)) {
            val doneIcon = icon(R.drawable.ic_downloaded, accent)
            val dLp = LinearLayout.LayoutParams(dp(19), dp(19))
            dLp.marginStart = dp(8)
            row.addView(doneIcon, dLp)
        }
        val isCurrent = track.id == current?.id && track.source == current?.source
        if (isCurrent && isPlayingSafe()) {
            // Playing indicator only while actually playing — the same
            // single source of truth as the mini player.
            row.addView(visualizer())
        } else if (downloading.contains(Downloads.key(track))) {
            // In-flight download: a spinner stands in for the ⋯ menu
            // (the menu stays reachable by long-press). Static at
            // render time; the Downloads tab carries live progress.
            val spin = ProgressBar(this).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(accent)
            }
            row.addView(spin, LinearLayout.LayoutParams(dp(22), dp(22)))
        } else {
            val moreIcon = icon(R.drawable.ic_more, Color.argb(115, 255, 255, 255)).apply {
                setOnClickListener { openMenu() }
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

    // ------------------------------------------------- moods & remote playlists

    /** Playlists inside one mood/genre category (real browse via
     *  FEmusic_moods_and_genres_category, not a search). */
    private fun showMoodPlaylists(cat: YtmMoodCategory) {
        val gen = ++generation
        beginScreen(cat.name, showAvatar = false)
        val loading = label("Loading playlists…", 16f, white50).apply {
            setPadding(0, dp(20), 0, 0)
        }
        content.addView(loading)
        executor.execute {
            var list: List<YtmPlaylistRef>? = null
            var err: String? = null
            try {
                list = YtmApi.moodPlaylists(cat)
            } catch (t: Throwable) {
                err = shortErr(t)
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                content.removeView(loading)
                val l = list
                if (l == null) {
                    content.addView(errorLine("YouTube unavailable: $err"))
                    return@runOnUiThread
                }
                l.forEachIndexed { i, ref ->
                    if (i > 0) content.addView(hairline())
                    content.addView(ytmPlaylistRow(ref))
                }
            }
        }
    }

    private fun ytmPlaylistRow(ref: YtmPlaylistRef): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            rippleRect()
            setOnClickListener { showYtmPlaylist(ref) }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 9)
        }
        ArtworkLoader.load(ref.artworkSmall ?: ref.artworkLarge, img)
        row.addView(img, LinearLayout.LayoutParams(dp(52), dp(52)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        text.addView(label(ref.title, 16f, Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (ref.subtitle.isNotEmpty()) {
            text.addView(label(ref.subtitle, 14.5f, white50).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /** A remote (YouTube Music) playlist page: artwork header,
     *  Play + Shuffle, full playable track list — same shape as the
     *  album page. */
    private fun showYtmPlaylist(ref: YtmPlaylistRef) {
        val gen = ++generation
        visibleScreen = "YtmPlaylist"
        content.removeAllViews()
        content.addView(label("Loading playlist…", 16f, white50).apply {
            setPadding(0, dp(28), 0, 0)
        })
        executor.execute {
            var list: List<Track>? = null
            var err: String? = null
            try {
                list = YtmApi.browsePlaylistTracks(ref.id)
            } catch (t: Throwable) {
                err = shortErr(t)
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                content.removeAllViews()
                val tracks = list
                if (tracks == null) {
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
                ArtworkLoader.load(ref.artworkLarge ?: ref.artworkSmall, art)
                val artLp = LinearLayout.LayoutParams(dp(200), dp(200))
                artLp.topMargin = dp(14)
                head.addView(art, artLp)
                head.addView(label(ref.title, 20f, Color.WHITE, boldTf).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    lp.topMargin = dp(16)
                    layoutParams = lp
                })
                if (ref.subtitle.isNotEmpty()) {
                    head.addView(label(ref.subtitle, 13.5f, white50).apply {
                        gravity = Gravity.CENTER
                    })
                }
                head.addView(label("${tracks.size} tracks", 13f, white50).apply {
                    gravity = Gravity.CENTER
                })
                content.addView(head)
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
                        setQueue(tracks)
                        refreshTransportStates()
                        play(tracks[playOrder.firstOrNull() ?: 0])
                    }
                }
                actions.addView(shuffleBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
                val playAll = pillButton("Play", filled = true) {
                    setQueue(tracks)
                    play(tracks.first())
                }
                val playLp = LinearLayout.LayoutParams(dp(132), dp(40))
                playLp.setMargins(dp(14), 0, dp(14), 0)
                actions.addView(playAll, playLp)
                content.addView(actions)
                addRows(tracks, withHairlines = true, queueContext = tracks)
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
            contentDescription = "Favorite"
            setOnClickListener { toggleFavorite() }
        }
        meta.addView(npHeart, LinearLayout.LayoutParams(dp(38), dp(38)))
        inner.addView(meta)

        // Playback failure surface: when a track cannot play, the real
        // reason (resolver outcomes, player error codes) is printed
        // here and stays on screen, instead of only flashing a generic
        // toast (v4's Dai Dai screenshot: toast, 0:00, no diagnosis).
        npError = label("", 13f, accent).apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        inner.addView(npError, LinearLayout.LayoutParams(-1, -2))

        npSeek = styledSeek(R.drawable.seek_thumb).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = player?.durationMs ?: 0L
                        if (dur > 0) {
                            npElapsed.text = fmt(progress)
                            npRemaining.text = "-" + fmt((dur - progress).coerceAtLeast(0L).toInt())
                        }
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seeking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seeking = false
                    player?.seekTo((sb?.progress ?: 0).toLong())
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
        npShuffleBtn = icon(R.drawable.ic_shuffle, white55).apply {
            contentDescription = "Shuffle"
            setOnClickListener { toggleShuffle() }
        }
        npShuffleDot = dotView()
        val shuffleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        shuffleBox.addView(npShuffleBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        val shDotLp = LinearLayout.LayoutParams(dp(4), dp(4))
        shDotLp.topMargin = dp(3)
        shuffleBox.addView(npShuffleDot, shDotLp)
        val prevBtn = icon(R.drawable.ic_skip_prev, Color.WHITE).apply {
            contentDescription = "Previous track"
            setOnClickListener { playPrevious() }
        }
        npPlayButton = icon(R.drawable.ic_play, Color.WHITE).apply {
            contentDescription = "Play"
            setOnClickListener { togglePlayback() }
        }
        val nextBtn = icon(R.drawable.ic_skip_next, Color.WHITE).apply {
            contentDescription = "Next track"
            setOnClickListener { playNext() }
        }
        npRepeatBtn = icon(R.drawable.ic_repeat, white55).apply {
            contentDescription = "Repeat"
            setOnClickListener { toggleRepeat() }
        }
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
            contentDescription = "Lyrics"
            setOnClickListener { showLyrics() }
        }
        val queueBtn = icon(R.drawable.ic_queue, white75).apply {
            contentDescription = "Queue"
            setOnClickListener { showQueue() }
        }
        npDownloadBtn = icon(R.drawable.ic_download, white75).apply {
            contentDescription = "Download"
            setOnClickListener { downloadCurrent() }
        }
        npDownloadProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(accent)
        }
        // Sleep gets its own recognizable glyph (crescent moon) instead
        // of living only inside the overflow menu.
        val sleepBtn = icon(R.drawable.ic_sleep, white75).apply {
            contentDescription = "Sleep timer"
            setOnClickListener { showSleepMenu() }
        }
        val shareBtn = icon(R.drawable.ic_share, white75).apply {
            contentDescription = "Share"
            setOnClickListener { shareTrack(current) }
        }
        val moreBtn = icon(R.drawable.ic_more, white75).apply {
            contentDescription = "More options"
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
        bottomRow.addView(sleepBtn, LinearLayout.LayoutParams(0, dp(32), 1f))
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
        if (reduceMotion()) {
            nowPlaying.translationY = 0f
            return
        }
        // Slide up from the mini player instead of a hard cut; the
        // transform is composited, so it stays smooth on mid-range GPUs.
        val h = nowPlaying.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        nowPlaying.translationY = h.toFloat()
        nowPlaying.animate().translationY(0f).setDuration(300)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hideNowPlaying() {
        if (reduceMotion()) {
            nowPlaying.visibility = View.GONE
            nowPlaying.translationY = 0f
            return
        }
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
        // Jank pass: the 500ms ticker touches ONLY the seek bar and
        // the two time labels, and only when their rendered values
        // actually changed — no layout passes, no row re-binding.
        val dur = p.durationMs
        if (dur > 0) {
            val pos = p.positionMs.coerceIn(0L, dur)
            val durInt = dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (npSeek.max != durInt) npSeek.max = durInt
            val posInt = pos.toInt()
            if (npSeek.progress != posInt) npSeek.progress = posInt
            val elapsed = fmt(posInt)
            if (elapsed != lastNpElapsed) {
                lastNpElapsed = elapsed
                npElapsed.text = elapsed
            }
            val remaining = "-" + fmt((dur - pos).coerceAtLeast(0L).toInt())
            if (remaining != lastNpRemaining) {
                lastNpRemaining = remaining
                npRemaining.text = remaining
            }
        }
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * Favorite state has two generations: the v5 Liked Songs store
     * (full tracks) and the v4.1 `fav_<id>` boolean. A track counts
     * as liked when EITHER says so; toggling writes both, so the
     * heart, the store and old state can never disagree.
     */
    private fun isFavorite(t: Track): Boolean =
        Favorites.contains(this, t) ||
            getPreferences(MODE_PRIVATE).getBoolean("fav_${t.id}", false)

    private fun toggleFavorite() {
        val t = current ?: return
        val prefs = getPreferences(MODE_PRIVATE)
        val now = !isFavorite(t)
        if (now) {
            Favorites.add(this, t)
        } else {
            Favorites.remove(this, t)
        }
        prefs.edit().putBoolean("fav_${t.id}", now).apply()
        updateHeart()
    }

    private fun updateHeart() {
        val t = current ?: return
        val fav = isFavorite(t)
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
        // Stats: every play() is a play event (replays included);
        // bank any accumulated listening time first.
        flushListened()
        PlayHistory.record(this, track)
        clearPlaybackError()
        refreshPlaybackUi()
        val token = ++playToken
        // The outgoing player must never fire completion/error into
        // the NEW track's session (it would advance the queue or
        // print a stale error). Retire it up front (every callback
        // checks the flag); what happens to the instance itself is
        // decided per path below. An outgoing player that is actually
        // audible becomes the crossfade source, so the fade can start
        // from the track the user is hearing even after a background
        // resolution — ladder retries and the Audius fallback
        // re-enter play() and must not lose it.
        player?.let { old ->
            old.retired = true
            if (old.isPlaying) fadeSource = old
        }
        // Local files play from their content URI, offline, always.
        if (track.source == SOURCE_LOCAL) {
            startPlaybackUri(android.net.Uri.parse(track.id))
            return
        }
        // A downloaded copy always wins: instant start, works offline.
        val local = Downloads.localFile(this, track)
        if (local != null) {
            startPlayback(local.absolutePath)
            return
        }
        if (track.source == SOURCE_YOUTUBE) {
            // The direct stream URL is resolved on-device by the
            // retry ladder (see beginYtResolution): the known-good
            // cached resolution first, then the Innertube contexts in
            // last-good-first order, then NewPipe — and a stream the
            // PLAYER rejects advances to the next context instead of
            // killing the track. v5 resolved exactly once and handed
            // the URL to a bare-UA MediaPlayer; one rejection
            // (what=-38) was final. Failures still PRINT every
            // stage's outcome on Now Playing — no generic silence.
            // With crossfade on, the old track keeps playing through
            // the resolution and is faded out when the new one starts
            // — releasing here would make every YouTube transition a
            // hard cut with a silent gap.
            if (crossfadeSec() <= 0) {
                releaseActivePlayer()
                refreshPlaybackUi()
            }
            beginYtResolution(track, token)
        } else {
            startPlayback("https://api.audius.co/v1/tracks/${track.id}/stream?app_name=arzxsh")
        }
    }

    /** Releases the active player (if any) and forgets it as a
     *  crossfade source when it was the source. */
    private fun releaseActivePlayer() {
        val p = player ?: return
        if (fadeSource === p) fadeSource = null
        p.release()
        player = null
    }

    // ------------------------------------------------------------ YouTube retry ladder (v5.1)

    /**
     * Starts stream resolution for a YouTube track as a staged
     * ladder. Stage order: the session's known-good cached
     * resolution when one exists, then the Innertube contexts in
     * last-good-first order, then NewPipe. Each stage either produces
     * a stream the player accepts, fails resolution (its report is
     * kept), or is REJECTED by the player — which also advances the
     * ladder (see onPlayerFailed). Only when every stage is spent do
     * the Audius fallback and the printed error happen, carrying
     * every stage's outcome. Resolution runs on [playerExecutor],
     * never queued behind a download.
     */
    private fun beginYtResolution(track: Track, token: Int) {
        ytTrack = track
        ytQuality = effectiveQuality()
        ytReports.clear()
        ytCached = StreamResolver.cachedGood(track.id, ytQuality)
        ytStageLabels = if (ytCached?.url != null) {
            listOf("CACHE") + StreamResolver.youtubeStageLabels()
        } else {
            StreamResolver.youtubeStageLabels()
        }
        ytStage = -1
        advanceYtLadder(token)
    }

    private fun advanceYtLadder(token: Int) {
        val track = ytTrack ?: return
        ytStage++
        if (ytStage >= ytStageLabels.size) {
            // Ladder spent: the Audius fallback (standing second
            // source, YouTube Music and Both modes) gets the full
            // report; in Audius-only mode the printed report stands.
            val report = ytReports.joinToString(" · ")
            if (sourceMode() != "audius") {
                tryAudiusFallback(track, report, token)
            } else {
                showPlaybackError("Couldn't play this track — $report")
                refreshPlaybackUi()
                Toast.makeText(this, "This track could not be played.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val label = ytStageLabels[ytStage]
        if (label == "CACHE") {
            val cached = ytCached
            val cachedUrl = cached?.url
            if (cached != null && cachedUrl != null) {
                startPlayback(cachedUrl, cached.userAgent, cached)
            } else {
                advanceYtLadder(token)
            }
            return
        }
        playerExecutor.execute {
            val resolved = StreamResolver.resolveYoutubeStage(track.id, ytQuality, label)
            runOnUiThread {
                if (token != playToken) return@runOnUiThread
                if (resolved.url == null) {
                    ytReports += resolved.report
                    advanceYtLadder(token)
                } else {
                    startPlayback(resolved.url, resolved.userAgent, resolved)
                }
            }
        }
    }

    /** Lowercase alphanumeric form for cross-source song matching. */
    private fun normalizeForMatch(s: String): String {
        var x = s.lowercase()
        x = x.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        x = x.replace(
            Regex("\\b(official|video|audio|lyrics|lyric|hd|mv|music|topic|vevo)\\b"),
            " "
        )
        x = x.replace(Regex("[^a-z0-9 ]"), " ")
        return x.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Conservative same-song test for the Audius fallback: only an
     * obvious match qualifies — an exact normalized title, or a
     * strongly containing title, in both cases backed by artist
     * evidence (in the candidate's artist field or its title). A loose
     * match is worse than an honest error, so near-misses lose.
     */
    private fun audiusMatchFor(yt: Track, candidates: List<Track>): Track? {
        val ytTitle = normalizeForMatch(yt.title)
        val ytArtist = normalizeForMatch(yt.artist)
        if (ytTitle.isEmpty()) return null
        var best: Track? = null
        var bestScore = -1
        for (c in candidates) {
            val ct = normalizeForMatch(c.title)
            if (ct.isEmpty()) continue
            val ratio = minOf(ct.length, ytTitle.length).toDouble() /
                maxOf(ct.length, ytTitle.length).coerceAtLeast(1)
            val titleScore = when {
                ct == ytTitle -> 3
                (ct.contains(ytTitle) || ytTitle.contains(ct)) && ratio >= 0.7 -> 2
                else -> 0
            }
            if (titleScore == 0) continue
            val ca = normalizeForMatch(c.artist)
            val artistHit = ytArtist.isNotEmpty() &&
                ((ca.isNotEmpty() && (ca.contains(ytArtist) || ytArtist.contains(ca))) ||
                    ct.contains(ytArtist))
            val plausible = artistHit || (titleScore == 3 && ytArtist.isEmpty())
            if (!plausible) continue
            val score = titleScore * 10 + if (artistHit) 5 else 0
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    /**
     * When a YouTube track fails BOTH stream resolvers, search Audius
     * for the same song; on a plausible match, swap it into the queue
     * slot and play it, telling the user plainly. No match → the
     * printed resolver report stands as the diagnosis.
     */
    private fun tryAudiusFallback(ytTrack: Track, report: String, token: Int) {
        playerExecutor.execute {
            var match: Track? = null
            try {
                val q = URLEncoder.encode(
                    listOf(ytTrack.title, ytTrack.artist).filter { it.isNotBlank() }.joinToString(" "),
                    "UTF-8"
                )
                val candidates = fetch("/tracks/search?query=$q&limit=10") ?: emptyList()
                match = audiusMatchFor(ytTrack, candidates)
            } catch (_: Throwable) {
                match = null
            }
            val found = match
            runOnUiThread {
                if (token != playToken) return@runOnUiThread
                if (found == null) {
                    showPlaybackError("Couldn't play this track — $report")
                    refreshPlaybackUi()
                    Toast.makeText(this@MainActivity, "This track could not be played.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val idx = queue.indexOfFirst { it.id == ytTrack.id && it.source == ytTrack.source }
                if (idx >= 0) {
                    val q2 = queue.toMutableList()
                    q2[idx] = found
                    queue = q2
                }
                pendingNpNotice = "YouTube failed — playing the Audius version."
                Toast.makeText(
                    this@MainActivity,
                    "YouTube failed — playing the Audius version.",
                    Toast.LENGTH_LONG
                ).show()
                play(found)
            }
        }
    }

    /** Prints a playback failure on Now Playing and keeps it there. */
    private fun showPlaybackError(text: String) {
        if (!::npError.isInitialized) return
        npError.text = text
        npError.visibility = View.VISIBLE
    }

    private fun clearPlaybackError() {
        if (!::npError.isInitialized) return
        npError.text = ""
        npError.visibility = View.GONE
    }

    /** Crossfade length from Settings, in seconds (0 = off, the
     *  default — with 0 every transition behaves exactly as v4.1). */
    private fun crossfadeSec(): Int =
        getPreferences(MODE_PRIVATE).getInt("crossfade_seconds", 0).coerceIn(0, 12)

    /** Shared player setup for every source: [stream] is a direct
     *  audio URL or a downloaded file's path. [userAgent] is the UA of
     *  the client context that produced a YouTube URL (googlevideo
     *  binds URLs to it); [resolved] marks a YouTube ladder start,
     *  whose player errors advance the ladder instead of failing the
     *  track outright. */
    private fun startPlayback(
        stream: String,
        userAgent: String? = null,
        resolved: StreamResolver.Resolved? = null
    ) = startPlaybackInternal(stream, null, userAgent, resolved)

    /** Player setup for a content:// data source (local files). */
    private fun startPlaybackUri(uri: android.net.Uri) =
        startPlaybackInternal(null, uri, null, null)

    /**
     * Builds one ExoPlayer for the new stream and makes it [player].
     * Its HTTP data source sends [userAgent] — the actual v5.1
     * playback fix. v5's MediaPlayer could not set a UA at all, so
     * googlevideo saw Stagefright's default and rejected the
     * resolved URL (what=-38) even though resolution had succeeded.
     *
     * Crossfade keeps v5's semantics on the new engine: when
     * crossfade is on, the new track is a DIFFERENT track, and the
     * previous track is still audible ([fadeSource]), the new player
     * starts silent and [crossfadeTick] swaps the volumes over the
     * configured seconds; any failure falls back to the hard cut.
     * Repeat-one restarts never fade into themselves.
     */
    private fun startPlaybackInternal(
        stream: String?,
        uri: android.net.Uri?,
        userAgent: String?,
        resolved: StreamResolver.Resolved?
    ) {
        val old = player
        val forTrack = current
        val sessionToken = playToken
        val fadeMs = crossfadeSec() * 1000
        val fade = fadeSource
        val sameTrack = forTrack != null && activePlayerTrack != null &&
            forTrack.id == activePlayerTrack?.id && forTrack.source == activePlayerTrack?.source
        val canFade = fadeMs > 0 && fade != null && !sameTrack && fade.isPlaying
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: "arzxsh (Android)")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
            )
            .build()
        val holder = EnginePlayer(exo)
        player = holder
        activePlayerTrack = forTrack
        exo.setAudioAttributes(
            Media3AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            false
        )
        val item = when {
            uri != null -> MediaItem.fromUri(uri)
            stream != null && stream.startsWith("/") ->
                MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(stream)))
            stream != null -> MediaItem.fromUri(stream)
            else -> null
        }
        if (item == null) {
            showPlaybackError("Player error: no stream to play")
            refreshPlaybackUi()
            return
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (holder.retired || player !== holder) return
                when (state) {
                    Player.STATE_READY -> if (!holder.readyFired) {
                        holder.readyFired = true
                        onPlayerReady(holder, old, forTrack, canFade, fadeMs, resolved)
                    }
                    Player.STATE_ENDED -> handleCompletion()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (holder.retired || player !== holder) return
                onPlayerFailed(holder, forTrack, resolved, sessionToken, error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (holder.retired || player !== holder) return
                // ExoPlayer starts asynchronously (MediaPlayer's
                // start() looked instant), so the transport icons
                // follow the REAL playing state from here.
                refreshPlaybackUi()
            }
        }
        holder.listener = listener
        exo.addListener(listener)
        if (canFade) holder.setVolume(0f)
        try {
            exo.setMediaItem(item)
            exo.prepare()
            exo.play()
        } catch (e: Exception) {
            showPlaybackError("Player error: ${shortErr(e)}")
            refreshPlaybackUi()
        }
    }

    /** The new player reached READY: the v5 "prepared" moment. A
     *  YouTube stream that actually starts is remembered as
     *  known-good (replays skip resolution); the crossfade partner is
     *  consumed exactly once — faded out, or released for the hard
     *  cut — and any other superseded player is released. */
    private fun onPlayerReady(
        holder: EnginePlayer,
        old: EnginePlayer?,
        forTrack: Track?,
        canFade: Boolean,
        fadeMs: Int,
        resolved: StreamResolver.Resolved?
    ) {
        if (resolved != null && forTrack != null) {
            StreamResolver.rememberGood(forTrack.id, ytQuality, resolved)
        }
        val notice = pendingNpNotice
        if (notice != null) {
            pendingNpNotice = null
            showPlaybackError(notice)
        } else {
            clearPlaybackError()
        }
        val partner = fadeSource
        fadeSource = null
        if (old != null && old !== holder && old !== partner) {
            old.release()
        }
        var faded = false
        if (canFade && partner != null && partner !== holder) {
            try {
                beginCrossfade(partner, fadeMs)
                faded = true
            } catch (_: Exception) {
                faded = false
            }
        }
        if (!faded) {
            if (partner != null && partner !== holder) partner.release()
            holder.setVolume(1f)
        }
        refreshPlaybackUi()
    }

    /**
     * The player rejected (or lost) its stream. For a YouTube ladder
     * start this is NOT the end: the attempt's outcome joins the
     * report, the session cache entry is evicted, and the ladder
     * advances to the next client context — the fix for v5's "one
     * resolution, one rejection, dead track". Any other source
     * prints the failure on Now Playing exactly like v5 did, now
     * with ExoPlayer's error code name instead of MediaPlayer codes.
     */
    private fun onPlayerFailed(
        holder: EnginePlayer,
        forTrack: Track?,
        resolved: StreamResolver.Resolved?,
        sessionToken: Int,
        error: PlaybackException
    ) {
        if (player === holder) player = null
        holder.release()
        if (resolved != null && forTrack != null && sessionToken == playToken &&
            ytTrack?.id == forTrack.id && ytTrack?.source == forTrack.source
        ) {
            ytReports += "${resolved.clientLabel ?: "stream"}: player rejected (${error.errorCodeName})"
            StreamResolver.forgetGood(forTrack.id, ytQuality)
            advanceYtLadder(sessionToken)
            return
        }
        val src = when (forTrack?.source) {
            SOURCE_YOUTUBE -> "YouTube stream"
            SOURCE_AUDIUS -> "Audius stream"
            SOURCE_LOCAL -> "local file"
            else -> "stream"
        }
        showPlaybackError(
            "Player error playing $src (${error.errorCodeName}). The resolved URL was rejected."
        )
        refreshPlaybackUi()
        Toast.makeText(this, "This track could not be played.", Toast.LENGTH_SHORT).show()
    }

    private fun beginCrossfade(old: EnginePlayer, fadeMs: Int) {
        // Retire any previous outgoing player first.
        outPlayer?.let { prev ->
            if (prev !== old) prev.release()
        }
        handler.removeCallbacks(crossfadeTick)
        outPlayer = old
        fadeStep = 0
        fadeTotalSteps = (fadeMs / 100).coerceAtLeast(1)
        handler.postDelayed(crossfadeTick, 100)
    }

    /** Ends a running crossfade immediately: outgoing player
     *  released, active player restored to full volume. */
    private fun finishCrossfadeNow() {
        handler.removeCallbacks(crossfadeTick)
        outPlayer?.release()
        outPlayer = null
        player?.setVolume(1f)
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
        // "End of queue" sleep timer: the queue ran out (no next
        // track, no repeat-all wrap), so stop here instead of
        // letting Autoplay extend the session forever.
        if (sleepAfterQueue) {
            sleepAfterQueue = false
            fadeOutAndPause()
            updateSleepLabel()
            return
        }
        // End of the queue: Autoplay continues with more like this
        // (radio for YouTube, trending for Audius) when enabled.
        if (autoplayOn()) {
            continueAutoplay()
            return
        }
        refreshPlaybackUi()
    }

    /**
     * Autoplay continuation at the end of a queue. YouTube queues grow
     * a radio around the last track; Audius queues continue with
     * trending tracks not already in the queue. Failures are announced
     * and playback simply stops — nothing is faked.
     */
    private fun continueAutoplay() {
        val t = current ?: return
        val token = playToken
        playerExecutor.execute {
            var more: List<Track> = emptyList()
            var err: String? = null
            try {
                more = if (t.source == SOURCE_YOUTUBE) {
                    YtmApi.radio(t.id)
                } else {
                    fetch("/tracks/trending?limit=25")
                        ?: throw RuntimeException("trending unavailable")
                }
            } catch (e: Throwable) {
                err = shortErr(e)
            }
            runOnUiThread {
                if (token != playToken) return@runOnUiThread
                val existing = queue.map { "${it.source}_${it.id}" }.toHashSet()
                val fresh = more.filterNot { "${it.source}_${it.id}" in existing }
                if (err != null) {
                    Toast.makeText(this@MainActivity, "Autoplay unavailable: $err", Toast.LENGTH_LONG).show()
                    refreshPlaybackUi()
                    return@runOnUiThread
                }
                if (fresh.isEmpty()) {
                    refreshPlaybackUi()
                    return@runOnUiThread
                }
                setQueue(queue + fresh)
                play(fresh.first())
            }
        }
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
        if (p != null && p.positionMs > 3000) {
            p.seekTo(0)
            return
        }
        if (queue.isEmpty() || queueIndex < 0) return
        val pos = currentOrderPos()
        if (pos > 0) {
            play(queue[playOrder[pos - 1]])
        } else {
            p?.seekTo(0)
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
        playerExecutor.execute {
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
        // A tap mid-crossfade settles the fade instantly, so pause /
        // resume always acts on ONE player at full volume.
        if (outPlayer != null) finishCrossfadeNow()
        val active = player ?: return
        if (active.isPlaying) {
            active.pause()
        } else {
            // ExoPlayer play() at the ENDED state is a no-op (unlike
            // MediaPlayer's start(), which restarted): rewind first.
            if (active.isEnded) active.seekTo(0)
            active.play()
        }
        // Small scale pop on the glyph swap (spec §13.4), transform-only.
        if (!reduceMotion() && ::npPlayButton.isInitialized && nowPlaying.visibility == View.VISIBLE) {
            npPlayButton.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70)
                .withEndAction {
                    npPlayButton.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                .start()
        }
        refreshPlaybackUi()
    }

    private fun isPlayingSafe(): Boolean = player?.isPlaying == true

    private fun refreshPlaybackUi() {
        val t = current
        val playing = isPlayingSafe()
        miniPlayButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        miniPlayButton.contentDescription = if (playing) "Pause" else "Play"
        if (::npPlayButton.isInitialized) {
            npPlayButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            npPlayButton.contentDescription = if (playing) "Pause" else "Play"
        }
        updateVisualizerState()
        refreshTransportStates()
        refreshDownloadButton()
        // Apple signature detail (spec §13.3): the artwork breathes —
        // slightly smaller while paused, full size while playing.
        // Skipped entirely under Reduce motion.
        if (::npArtwork.isInitialized && playing != artWasPlaying) {
            artWasPlaying = playing
            if (!reduceMotion()) {
                val s = if (playing) 1f else 0.92f
                npArtwork.animate().scaleX(s).scaleY(s).setDuration(260)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
        if (t == null) return
        miniTitle.text = t.title
        miniArtist.text = t.artist
        // Small first, large as the fallback: a failed small thumbnail
        // must not leave the mini player artwork blank (v4 bug).
        ArtworkLoader.load(t.artworkSmall, miniArtwork, t.artworkLarge, dp(84))
        if (::npTitle.isInitialized) {
            npTitle.text = t.title
            npArtist.text = t.artist
            val big = largeArt(t)
            ArtworkLoader.load(big, npArtwork, t.artworkSmall, resources.displayMetrics.widthPixels)
            ArtworkLoader.loadBlurred(big, npBackdrop)
            updateHeart()
            npSeek.progress = 0
            npElapsed.text = "0:00"
            lastNpElapsed = "0:00"
            npRemaining.text = "-" + fmt(t.duration * 1000)
            lastNpRemaining = npRemaining.text.toString()
        }
    }

    /**
     * The mini player's equalizer has ONE source of truth: it shows
     * only when the Visualizer setting is on AND audio is actually
     * playing. (v4 drove it from the setting alone, so the bars
     * "played" next to a pause-state play button.)
     */
    private fun updateVisualizerState() {
        if (!::visualizerView.isInitialized) return
        val on = getPreferences(MODE_PRIVATE).getBoolean("setting_Visualizer", true)
        visualizerView.visibility = if (on && isPlayingSafe()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ user settings (single read points)

    /**
     * Active music source: "youtube" (primary, Audius only as the
     * automatic fallback), "audius" (Audius only — no YouTube calls
     * at all), or "both" (merged, YouTube first). Set from Settings
     * or the Search screen's segmented control; both stay in sync
     * because both read/write this one preference.
     */
    private fun sourceMode(): String =
        getPreferences(MODE_PRIVATE).getString("source_mode", "youtube") ?: "youtube"

    private fun setSourceMode(mode: String) {
        getPreferences(MODE_PRIVATE).edit().putString("source_mode", mode).apply()
    }

    private fun sourceModeName(mode: String): String = when (mode) {
        "audius" -> "Audius"
        "both" -> "Both"
        else -> "YouTube Music"
    }

    private fun streamQuality(): String =
        getPreferences(MODE_PRIVATE).getString("stream_quality", "normal") ?: "normal"

    private fun dataSaverOn(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("setting_Data saver", false)

    /** The quality the resolver actually uses: Data saver wins. */
    private fun effectiveQuality(): String = if (dataSaverOn()) "data" else streamQuality()

    private fun autoplayOn(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("setting_Autoplay", true)

    private fun reduceMotion(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("setting_Reduce motion", false)

    /** Artwork URL for large surfaces; Data saver downgrades to the
     *  small rendition instead of prefetching full-size art. */
    private fun largeArt(t: Track): String? =
        if (dataSaverOn()) t.artworkSmall ?: t.artworkLarge else t.artworkLarge ?: t.artworkSmall

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
        if (reduceMotion()) {
            menuOverlay.alpha = 1f
            menuPanel.translationY = 0f
            return
        }
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
        items += "Add to Playlist" to { showAddToPlaylist(t) }
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
            sleepAfterQueue -> "Sleep timer · ends at the end of the queue"
            else -> "Sleep Timer"
        }
        val items = mutableListOf<Pair<String, () -> Unit>>()
        for (m in listOf(5, 10, 15, 30, 45, 60)) {
            items += (if (m == 60) "1 hour" else "$m minutes") to { setSleepTimer(m) }
        }
        items += "Custom…" to { showCustomSleepDialog() }
        items += "End of track" to { setSleepEndOfTrack() }
        items += "End of queue" to { setSleepEndOfQueue() }
        if (sleepDeadline > now || sleepAfterTrack || sleepAfterQueue) {
            items += "Turn off timer" to { setSleepTimer(0) }
        }
        showMenu(title, items)
    }

    private fun setSleepEndOfQueue() {
        handler.removeCallbacks(sleepFire)
        sleepDeadline = 0L
        sleepAfterTrack = false
        sleepAfterQueue = true
        updateSleepLabel()
        Toast.makeText(this, "Your sleep timer is set.", Toast.LENGTH_SHORT).show()
    }

    /** Custom sleep duration via a plain numeric input dialog. */
    private fun showCustomSleepDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Minutes"
            setTextColor(Color.WHITE)
            setHintTextColor(white50)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Sleep timer — custom")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val mins = input.text?.toString()?.trim()?.toIntOrNull()
                if (mins != null && mins > 0) {
                    setSleepTimer(mins)
                } else {
                    Toast.makeText(this, "Enter a number of minutes", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepFire)
        sleepAfterTrack = false
        sleepAfterQueue = false
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
        sleepAfterQueue = false
        sleepAfterTrack = true
        updateSleepLabel()
        Toast.makeText(this, "Your sleep timer is set.", Toast.LENGTH_SHORT).show()
    }

    /** Sleep-timer expiry fades the volume out over ~2s, then pauses
     *  (spec §6), instead of hard-cutting. A crossfade's outgoing
     *  player is silenced and released too — sleep wins over fade. */
    private fun fadeOutAndPause() {
        val p = player ?: return
        val out = outPlayer
        if (out != null) {
            handler.removeCallbacks(crossfadeTick)
            out.release()
            outPlayer = null
            p.setVolume(1f)
        }
        if (!p.isPlaying) return
        var step = 0
        val fade = object : Runnable {
            override fun run() {
                step++
                val v = (1f - step / 20f).coerceAtLeast(0f)
                p.setVolume(v)
                if (step < 20) {
                    handler.postDelayed(this, 100)
                } else {
                    p.pause()
                    p.setVolume(1f)
                    refreshPlaybackUi()
                }
            }
        }
        handler.post(fade)
    }

    /** The honest remaining-time label under the Now Playing controls. */
    private fun updateSleepLabel() {
        if (!::npSleepLabel.isInitialized) return
        // Runs from the 500ms ticker: only touch the view when the
        // rendered string actually changes (jank pass).
        val text = when {
            sleepAfterTrack -> "Sleep timer · ends after this track"
            sleepAfterQueue -> "Sleep timer · ends at the end of the queue"
            sleepDeadline > 0 && sleepDeadline - System.currentTimeMillis() > 0 ->
                "Sleep timer · " + fmt((sleepDeadline - System.currentTimeMillis()).toInt())
            else -> ""
        }
        if (text == lastSleepLabel) return
        lastSleepLabel = text
        if (text.isEmpty()) {
            npSleepLabel.visibility = View.GONE
        } else {
            npSleepLabel.text = text
            npSleepLabel.visibility = View.VISIBLE
        }
    }

    /** Context menu for any track row (⋯ button or long-press), on
     *  every screen that lists songs. */
    private fun showTrackMenu(track: Track) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Play Next" to { playNextInsert(track) }
        items += "Add to Queue" to { appendToQueue(track) }
        items += "Add to Playlist" to { showAddToPlaylist(track) }
        if (Downloads.isDownloaded(this, track)) {
            items += "Remove Download" to {
                Downloads.remove(this, track)
                Toast.makeText(this, "Download removed", Toast.LENGTH_SHORT).show()
                refreshPlaybackUi()
            }
        } else if (track.source != SOURCE_LOCAL) {
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

    // ------------------------------------------------------------ playlists UI

    /** Playlist picker: every playlist plus "New Playlist…". */
    private fun showAddToPlaylist(track: Track) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        Playlists.all(this).forEach { p ->
            items += p.name to {
                if (Playlists.addTrack(this, p.id, track)) {
                    Toast.makeText(this, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Already in ${p.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        items += "New Playlist…" to {
            showNameDialog("New Playlist", "") { name ->
                val p = Playlists.create(this, name)
                Playlists.addTrack(this, p.id, track)
                Toast.makeText(this, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
            }
        }
        showMenu("Add to Playlist", items)
    }

    /** One-text-field dialog (playlist create/rename). */
    private fun showNameDialog(title: String, initial: String, onSubmit: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            hint = "Playlist name"
            setTextColor(Color.WHITE)
            setHintTextColor(white50)
            setSingleLine(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            if (initial.isNotEmpty()) setSelection(initial.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) onSubmit(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLibrary() {
        visibleScreen = "Library"
        generation++
        beginScreen("Library", showAvatar = false)

        content.addView(sectionTitle("Playlists"))
        val playlists = Playlists.all(this)
        if (playlists.isEmpty()) {
            content.addView(label("No playlists yet. Use a track's ⋯ menu to start one.", 14.5f, white50))
        } else {
            playlists.forEachIndexed { i, p ->
                if (i > 0) content.addView(hairline())
                content.addView(playlistRow(p))
            }
        }
        content.addView(
            settingsRow("New Playlist", null) {
                showNameDialog("New Playlist", "") { name ->
                    Playlists.create(this, name)
                    showLibrary()
                }
            }
        )

        content.addView(sectionTitle("Your Music"))
        val likedCount = Favorites.liked(this).size
        content.addView(settingsRow("Liked Songs", "$likedCount liked") { showLiked() })
        val histCount = PlayHistory.totalPlays(this)
        content.addView(settingsRow("Listening History", "$histCount plays recorded") { showHistory() })
        content.addView(settingsRow("Your Listening", "Top tracks, artists and time") { showStats() })

        content.addView(sectionTitle("On This Device"))
        val locals = LocalTracks.all(this)
        if (locals.isNotEmpty()) {
            val list = locals
            content.addView(sectionTitle("${list.size} imported"))
            addRows(list, withHairlines = true, queueContext = list)
        }
        content.addView(
            settingsRow("Import Music Files", "Links to files — nothing is copied") {
                launchImport()
            }
        )
        content.addView(
            label(
                "MP3, AAC/M4A, FLAC, WAV, OGG and other formats this device can decode. " +
                    "If a file is moved or deleted, it stops working here too.",
                13f, white50
            )
        )
    }

    // ------------------------------------------------------------ local file import

    /**
     * System document picker (multi-select audio). Files are LINKED,
     * never copied: the persisted read permission keeps the content
     * URI playable across restarts, and deleting the file in a file
     * manager removes it here too — the Library copy says so.
     */
    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, reqImport)
        } catch (_: Exception) {
            Toast.makeText(this, "No file picker available on this device", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqImport || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<android.net.Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return
        Toast.makeText(this, "Importing ${uris.size} file(s)…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val imported = ArrayList<Track>()
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Some providers grant non-persistable access; the
                    // track still imports for this session.
                }
                extractLocalTrack(uri)?.let { imported.add(it) }
            }
            val added = if (imported.isNotEmpty()) LocalTracks.addAll(this@MainActivity, imported) else 0
            runOnUiThread {
                when {
                    added > 0 -> Toast.makeText(
                        this@MainActivity, "Imported $added file(s)", Toast.LENGTH_SHORT
                    ).show()
                    imported.isNotEmpty() -> Toast.makeText(
                        this@MainActivity, "Already imported", Toast.LENGTH_SHORT
                    ).show()
                    else -> Toast.makeText(
                        this@MainActivity, "Couldn't read those files", Toast.LENGTH_LONG
                    ).show()
                }
                if (visibleScreen == "Library") showLibrary()
            }
        }
    }

    /** Reads title/artist/duration + embedded artwork via
     *  MediaMetadataRetriever; artwork is written once into
     *  filesDir/local_art and referenced as a file:// URL (the
     *  ArtworkLoader decodes file: URLs from disk). */
    private fun extractLocalTrack(uri: android.net.Uri): Track? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, uri)
            var title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim().orEmpty()
            var artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim().orEmpty()
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (title.isEmpty()) {
                title = displayName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                    ?: "Unknown title"
            }
            if (artist.isEmpty()) artist = "Unknown artist"
            var artPath: String? = null
            val pic = mmr.embeddedPicture
            if (pic != null) {
                try {
                    val dir = java.io.File(filesDir, "local_art").apply { mkdirs() }
                    val f = java.io.File(
                        dir, Integer.toHexString(uri.toString().hashCode()) + ".jpg"
                    )
                    if (!f.exists()) f.writeBytes(pic)
                    artPath = android.net.Uri.fromFile(f).toString()
                } catch (_: Exception) {
                    artPath = null
                }
            }
            Track(
                uri.toString(), title, artist, (durMs / 1000).toInt(),
                artPath, artPath, SOURCE_LOCAL, null
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun displayName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun playlistRow(p: Playlist): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            rippleRect()
            setOnClickListener { showPlaylist(p.id) }
            setOnLongClickListener {
                showPlaylistMenu(p)
                true
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 9)
        }
        val first = p.tracks.firstOrNull()
        ArtworkLoader.load(first?.artworkSmall ?: first?.artworkLarge, img)
        row.addView(img, LinearLayout.LayoutParams(dp(52), dp(52)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        text.addView(label(p.name, 16f, Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val totalSec = p.tracks.sumOf { it.duration }
        val sub = buildString {
            append("${p.tracks.size} tracks")
            if (totalSec > 0) append(" · ").append(fmt(totalSec * 1000))
        }
        text.addView(label(sub, 14.5f, white50))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val moreIcon = icon(R.drawable.ic_more, Color.argb(115, 255, 255, 255)).apply {
            setOnClickListener { showPlaylistMenu(p) }
        }
        row.addView(moreIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
        return row
    }

    private fun showPlaylistMenu(p: Playlist) {
        showMenu(
            p.name, listOf(
                "Play" to {
                    if (p.tracks.isNotEmpty()) {
                        setQueue(p.tracks)
                        play(p.tracks.first())
                    }
                },
                "Rename" to {
                    showNameDialog("Rename Playlist", p.name) { name ->
                        Playlists.rename(this, p.id, name)
                        if (visibleScreen == "Library") showLibrary()
                    }
                },
                "Delete Playlist" to {
                    showMenu(
                        "Delete “${p.name}”?", listOf(
                            "Delete" to {
                                Playlists.delete(this, p.id)
                                Toast.makeText(this, "Playlist deleted", Toast.LENGTH_SHORT).show()
                                showLibrary()
                            },
                            "Cancel" to {}
                        )
                    )
                }
            )
        )
    }

    private fun showPlaylist(playlistId: String) {
        visibleScreen = "Playlist"
        generation++
        val p = Playlists.get(this, playlistId) ?: run {
            showLibrary()
            return
        }
        beginScreen(p.name, showAvatar = false)
        val first = p.tracks.firstOrNull()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 12)
            elevation = dp(18).toFloat()
        }
        ArtworkLoader.load(first?.artworkLarge ?: first?.artworkSmall, art)
        val artLp = LinearLayout.LayoutParams(dp(180), dp(180))
        artLp.topMargin = dp(14)
        head.addView(art, artLp)
        val totalSec = p.tracks.sumOf { it.duration }
        head.addView(
            label(
                buildString {
                    append("${p.tracks.size} tracks")
                    if (totalSec > 0) append(" · ").append(fmt(totalSec * 1000))
                },
                13.5f, white50
            ).apply {
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.topMargin = dp(12)
                layoutParams = lp
            }
        )
        content.addView(head)
        if (p.tracks.isNotEmpty()) {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val aLp = LinearLayout.LayoutParams(-1, -2)
            aLp.topMargin = dp(16)
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
            val menuBtn = icon(R.drawable.ic_more, Color.WHITE).apply {
                background = glassBg(999f, Color.argb(28, 255, 255, 255), Color.argb(20, 255, 255, 255))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { showPlaylistMenu(p) }
            }
            actions.addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
            content.addView(actions)
            // Rows with playlist-specific menus: reorder happens
            // through explicit Move Up / Move Down items (the list
            // UI has no drag framework; numbered moves are exact).
            p.tracks.forEachIndexed { i, track ->
                if (i > 0) content.addView(hairline())
                addTrackRow(track, content, p.tracks) { showPlaylistTrackMenu(p.id, track, i) }
            }
        } else {
            content.addView(label("This playlist is empty.", 15f, white50).apply {
                setPadding(0, dp(18), 0, 0)
            })
            content.addView(label("Add tracks from any song's ⋯ menu → Add to Playlist.", 14f, white50))
        }
    }

    private fun showPlaylistTrackMenu(playlistId: String, track: Track, index: Int) {
        val p = Playlists.get(this, playlistId) ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Play Next" to { playNextInsert(track) }
        items += "Add to Queue" to { appendToQueue(track) }
        if (index > 0) {
            items += "Move Up" to {
                Playlists.moveTrack(this, playlistId, index, index - 1)
                showPlaylist(playlistId)
            }
        }
        if (index < p.tracks.size - 1) {
            items += "Move Down" to {
                Playlists.moveTrack(this, playlistId, index, index + 1)
                showPlaylist(playlistId)
            }
        }
        if (Downloads.isDownloaded(this, track)) {
            items += "Remove Download" to {
                Downloads.remove(this, track)
                Toast.makeText(this, "Download removed", Toast.LENGTH_SHORT).show()
                refreshPlaybackUi()
            }
        } else if (track.source != SOURCE_LOCAL) {
            items += "Download" to { startDownload(track) }
        }
        items += "Remove from Playlist" to {
            Playlists.removeTrackAt(this, playlistId, index)
            Toast.makeText(this, "Removed from ${p.name}", Toast.LENGTH_SHORT).show()
            showPlaylist(playlistId)
        }
        items += "Share" to { shareTrack(track) }
        val header = if (track.artist.isNotEmpty()) "${track.title}\n${track.artist}" else track.title
        showMenu(header, items)
    }

    private fun showLiked() {
        visibleScreen = "Liked"
        generation++
        beginScreen("Liked Songs", showAvatar = false)
        val liked = Favorites.liked(this)
        if (liked.isEmpty()) {
            content.addView(sectionTitle("Nothing liked yet"))
            content.addView(label("Tap the heart on Now Playing and the song lands here.", 15f, white50))
        } else {
            content.addView(sectionTitle("${liked.size} liked"))
            addRows(liked, withHairlines = true, queueContext = liked)
        }
    }

    private fun showHistory() {
        visibleScreen = "History"
        generation++
        beginScreen("Listening History", showAvatar = false)
        val events = PlayHistory.events(this)
        if (events.isEmpty()) {
            content.addView(sectionTitle("No plays yet"))
            content.addView(label("Everything you play is recorded here, on this device only.", 15f, white50))
        } else {
            content.addView(sectionTitle("${events.size} plays"))
            val list = events.map { it.first }
            addRows(list, withHairlines = true, queueContext = list)
        }
    }

    private fun showStats() {
        visibleScreen = "Stats"
        generation++
        beginScreen("Your Listening", showAvatar = false)
        val total = PlayHistory.totalPlays(this)
        val listened = PlayHistory.listenedMs(this)
        content.addView(sectionTitle("Overview"))
        content.addView(label("Total plays · $total", 16f, Color.WHITE))
        val mins = listened / 60_000
        val timeText = when {
            mins >= 60 -> "${mins / 60} h ${mins % 60} min"
            mins > 0 -> "$mins min"
            else -> "under a minute"
        }
        content.addView(label("Time listened · about $timeText", 16f, Color.WHITE))
        content.addView(
            label(
                "Time is measured from real playback on this device and rounded — treat it as an estimate.",
                12.5f, white50
            )
        )
        val topTracks = PlayHistory.topTracks(this, 10)
        if (topTracks.isNotEmpty()) {
            content.addView(sectionTitle("Top Tracks"))
            val ctx = topTracks.map { it.first }
            topTracks.forEachIndexed { i, (t, count) ->
                if (i > 0) content.addView(hairline())
                addTrackRow(t, content, ctx)
                // Play count appended as a trailing label row is
                // overkill; the count rides in the stats line below
                // via a dedicated compact row instead.
            }
            content.addView(statusLine(topTracks.joinToString(" · ") { "${it.first.title} ×${it.second}" }))
        }
        val topArtists = PlayHistory.topArtists(this, 10)
        if (topArtists.isNotEmpty()) {
            content.addView(sectionTitle("Top Artists"))
            topArtists.forEach { (name, count, rep) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                    if (rep?.artistId != null) {
                        rippleRect()
                        setOnClickListener { showArtist(rep.artistId, name, rep.artworkLarge) }
                    }
                }
                val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                text.addView(label(name, 16f, Color.WHITE, mediumTf))
                text.addView(label("$count plays", 14f, white50))
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                content.addView(row)
            }
        }
        if (topTracks.isEmpty() && topArtists.isEmpty()) {
            content.addView(label("Play some music and your patterns show up here.", 15f, white50))
        }
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
        if (track.source == SOURCE_LOCAL) {
            Toast.makeText(this, "Local files are already on this device", Toast.LENGTH_SHORT).show()
            return
        }
        if (Downloads.isDownloaded(this, track)) {
            Toast.makeText(this, "Already downloaded", Toast.LENGTH_SHORT).show()
            return
        }
        val key = Downloads.key(track)
        if (!downloading.add(key)) return
        dlStopModes.remove(key)
        // The executor is single-threaded: a second start waits its
        // turn and is honestly labeled Queued until it really runs.
        Downloads.setStatus(
            this, track,
            if (downloading.size > 1) Downloads.ST_QUEUED else Downloads.ST_DOWNLOADING, 0
        )
        refreshDownloadButton()
        if (visibleScreen == "Downloads") showDownloads()
        Toast.makeText(this, "Downloading ${track.title}…", Toast.LENGTH_SHORT).show()
        val quality = effectiveQuality()
        executor.execute {
            var lastPct = 0
            var err: String? = null
            var stopped = false
            Downloads.setStatus(this@MainActivity, track, Downloads.ST_DOWNLOADING, 0)
            try {
                Downloads.downloadSync(
                    this@MainActivity, track, quality,
                    shouldContinue = { !dlStopModes.containsKey(key) },
                    onProgress = { pct ->
                        lastPct = pct
                        if (pct % 10 == 0) {
                            Downloads.setStatus(
                                this@MainActivity, track, Downloads.ST_DOWNLOADING, pct
                            )
                        }
                        runOnUiThread {
                            dlProgressViews[key]?.let { (bar, tv) ->
                                bar.isIndeterminate = pct <= 0
                                if (pct > 0) bar.progress = pct
                                tv.text = "Downloading · $pct%"
                            }
                        }
                    }
                )
            } catch (e: Downloads.StoppedException) {
                stopped = true
            } catch (e: Throwable) {
                err = shortErr(e)
            }
            runOnUiThread {
                downloading.remove(key)
                val mode = dlStopModes.remove(key)
                when {
                    stopped && mode == "pause" -> {
                        // Pause keeps the state row (and its percent);
                        // the partial file is gone — resume restarts
                        // from zero, and the row says so.
                        Downloads.setStatus(this@MainActivity, track, Downloads.ST_PAUSED, lastPct)
                        Toast.makeText(this@MainActivity, "Paused: ${track.title}", Toast.LENGTH_SHORT).show()
                    }
                    stopped -> {
                        Downloads.clearStatus(this@MainActivity, key)
                        Toast.makeText(this@MainActivity, "Download removed", Toast.LENGTH_SHORT).show()
                    }
                    err != null -> {
                        Downloads.setStatus(this@MainActivity, track, Downloads.ST_FAILED, lastPct, err)
                        Toast.makeText(this@MainActivity, "Download failed: $err", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        Toast.makeText(this@MainActivity, "Downloaded: ${track.title}", Toast.LENGTH_SHORT).show()
                    }
                }
                refreshPlaybackUi()
                if (visibleScreen == "Downloads") showDownloads()
            }
        }
    }

    /** Pause: an in-flight transfer stops at the next chunk check;
     *  a queued one is simply re-labeled. */
    private fun pauseDownload(track: Track) {
        val key = Downloads.key(track)
        if (downloading.contains(key)) {
            dlStopModes[key] = "pause"
        } else {
            Downloads.setStatus(
                this, track, Downloads.ST_PAUSED,
                Downloads.statusOf(this, track)?.progress ?: 0
            )
            if (visibleScreen == "Downloads") showDownloads()
        }
    }

    /** Remove: an in-flight transfer aborts and its state row is
     *  dropped on completion; an idle row is dropped immediately. */
    private fun cancelDownload(track: Track) {
        val key = Downloads.key(track)
        if (downloading.contains(key)) {
            dlStopModes[key] = "remove"
        } else {
            Downloads.clearStatus(this, key)
            if (visibleScreen == "Downloads") showDownloads()
        }
    }

    private fun showDownloads() {
        generation++
        beginScreen("Downloads", showAvatar = false)
        dlProgressViews.clear()
        val entries = Downloads.entries(this)
        val pending = Downloads.pendingRows(this)
        if (entries.isEmpty() && pending.isEmpty()) {
            content.addView(sectionTitle("No downloads yet"))
            content.addView(
                label(
                    "Tracks you download from Now Playing or a track's ⋯ menu live here and play offline.",
                    15f, white50
                )
            )
            return
        }
        content.addView(
            statusLine(
                "${entries.size} downloaded · ${fmtBytes(Downloads.storageBytes(this))} on this device"
            )
        )
        if (pending.isNotEmpty()) {
            content.addView(sectionTitle("Not finished"))
            pending.forEachIndexed { i, (track, st) ->
                if (i > 0) content.addView(hairline())
                content.addView(downloadStateRow(track, st))
            }
        }
        if (entries.isNotEmpty()) {
            val list = entries.map { it.track }
            content.addView(sectionTitle("${list.size} downloaded"))
            addRows(list, withHairlines = true, queueContext = list)
        }
    }

    /** One vault row for a track that is NOT fully downloaded yet:
     *  state, live progress while downloading, and the actions that
     *  state honestly supports (Pause/Resume/Retry/Remove). */
    private fun downloadStateRow(track: Track, st: Downloads.Status): LinearLayout {
        val key = Downloads.key(track)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            rippleRect()
            setOnClickListener { play(track) }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundedClip(this, 9)
        }
        ArtworkLoader.load(track.artworkSmall ?: track.artworkLarge, img, null, dp(104))
        row.addView(img, LinearLayout.LayoutParams(dp(52), dp(52)))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        text.addView(label(track.title, 16f, Color.WHITE, mediumTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val statusText = when (st.state) {
            Downloads.ST_QUEUED -> "Queued"
            Downloads.ST_DOWNLOADING -> "Downloading · ${st.progress}%"
            Downloads.ST_PAUSED -> "Paused · ${st.progress}% · resumes from the start"
            Downloads.ST_FAILED -> "Failed · ${st.error ?: "unknown error"}"
            else -> st.state
        }
        val statusTv = label(statusText, 13.5f, if (st.state == Downloads.ST_FAILED) accent else white50).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        text.addView(statusTv)
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        if (st.state == Downloads.ST_DOWNLOADING || st.state == Downloads.ST_PAUSED) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = st.progress
                isIndeterminate = st.state == Downloads.ST_DOWNLOADING && st.progress <= 0
                progressTintList = ColorStateList.valueOf(accent)
            }
            val bLp = LinearLayout.LayoutParams(-1, dp(3))
            bLp.setMargins(dp(65), dp(2), dp(8), dp(2))
            box.addView(bar, bLp)
            if (st.state == Downloads.ST_DOWNLOADING) {
                dlProgressViews[key] = bar to statusTv
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(65), dp(2), 0, dp(6))
        }
        fun action(name: String, run: () -> Unit) {
            val tv = label(name, 14f, accent, mediumTf).apply {
                setPadding(0, dp(4), dp(22), dp(4))
                isClickable = true
                setOnClickListener { run() }
            }
            actions.addView(tv)
        }
        when (st.state) {
            Downloads.ST_DOWNLOADING -> {
                action("Pause") { pauseDownload(track) }
                action("Remove") { cancelDownload(track) }
            }
            Downloads.ST_QUEUED -> action("Remove") { cancelDownload(track) }
            Downloads.ST_PAUSED -> {
                action("Resume") { startDownload(track) }
                action("Remove") { cancelDownload(track) }
            }
            Downloads.ST_FAILED -> {
                action("Retry") { startDownload(track) }
                action("Remove") { cancelDownload(track) }
            }
        }
        box.addView(actions)
        return box
    }

    // ------------------------------------------------------------ lyrics overlay

    private fun buildLyricsOverlay(root: FrameLayout) {
        lyricsOverlay = FrameLayout(this).apply {
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
        lyricsTitle = label("", 21f, Color.WHITE, boldTf).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        head.addView(lyricsTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val done = label("Done", 16f, accent, mediumTf).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { hideLyrics() }
        }
        head.addView(done)
        inner.addView(head)
        lyricsStatus = label("", 13f, white50)
        val stLp = LinearLayout.LayoutParams(-1, -2)
        stLp.topMargin = dp(4)
        inner.addView(lyricsStatus, stLp)
        lyricsScroll = ScrollView(this)
        lyricsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(40))
        }
        lyricsScroll.addView(lyricsList)
        val sLp = LinearLayout.LayoutParams(-1, 0, 1f)
        sLp.topMargin = dp(10)
        inner.addView(lyricsScroll, sLp)
        lyricsOverlay.addView(inner, FrameLayout.LayoutParams(-1, -1))
        root.addView(lyricsOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun hideLyrics() {
        if (::lyricsOverlay.isInitialized) lyricsOverlay.visibility = View.GONE
    }

    /**
     * Opens the lyrics overlay for the current track. Synced (LRC)
     * lyrics highlight line by line from the ticker and tap-to-seek;
     * plain lyrics are labeled "Not synchronized" — timing is never
     * invented. A miss says "Lyrics unavailable", plainly.
     */
    private fun showLyrics() {
        val t = current ?: run {
            Toast.makeText(this, "Nothing playing", Toast.LENGTH_SHORT).show()
            return
        }
        if (!::lyricsOverlay.isInitialized) return
        lyricsOverlay.visibility = View.VISIBLE
        lyricsOverlay.alpha = 0f
        lyricsOverlay.animate().alpha(1f).setDuration(180).start()
        lyricsTitle.text = t.title
        val key = Downloads.key(t)
        if (key == lyricsKey && (lyricsLineViews.isNotEmpty() || lyricsLoading)) {
            // Already showing (or already fetching) this track.
            return
        }
        lyricsKey = key
        lyricsSyncedLines = emptyList()
        lyricsLineViews = emptyList()
        lyricsActiveLine = -2
        lyricsList.removeAllViews()
        lyricsStatus.text = if (t.artist.isNotEmpty()) "${t.artist} · Loading lyrics…" else "Loading lyrics…"
        lyricsLoading = true
        executor.execute {
            val data = try {
                Lyrics.fetch(this@MainActivity, t)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (lyricsKey != key) return@runOnUiThread
                lyricsLoading = false
                renderLyrics(data)
            }
        }
    }

    private fun renderLyrics(data: LyricsData?) {
        lyricsList.removeAllViews()
        lyricsSyncedLines = emptyList()
        lyricsLineViews = emptyList()
        lyricsActiveLine = -2
        when {
            data == null -> {
                lyricsStatus.text = "Lyrics unavailable for this track."
                lyricsList.addView(label("No lyrics provider had this song.", 15f, white50))
            }
            data.instrumental -> {
                lyricsStatus.text = "Instrumental · via ${data.source}"
                lyricsList.addView(label("This track is instrumental.", 15f, white50))
            }
            data.synced != null -> {
                lyricsStatus.text = "Synced · ${data.source} · tap a line to jump"
                val lines = data.synced
                lyricsSyncedLines = lines
                val views = ArrayList<TextView>(lines.size)
                lines.forEach { line ->
                    val tv = label(line.text.ifEmpty { "♪" }, 17.5f, white50).apply {
                        setPadding(0, dp(7), 0, dp(7))
                        isClickable = true
                        setOnClickListener {
                            player?.seekTo(line.timeMs)
                            updateLyricsHighlight()
                        }
                    }
                    views.add(tv)
                    lyricsList.addView(tv)
                }
                lyricsLineViews = views
                updateLyricsHighlight()
            }
            else -> {
                lyricsStatus.text = "Not synchronized · plain lyrics via ${data.source}"
                data.plainLines.forEach { line ->
                    lyricsList.addView(label(line.ifEmpty { " " }, 16.5f, white75).apply {
                        setPadding(0, dp(3), 0, dp(3))
                    })
                }
            }
        }
    }

    /** Ticker hook (500ms): moves the synced-lyrics highlight to the
     *  line the playback position has reached. Cheap no-op whenever
     *  the overlay is closed or the lyrics are plain. */
    private fun updateLyricsHighlight() {
        if (!::lyricsOverlay.isInitialized || lyricsOverlay.visibility != View.VISIBLE) return
        if (lyricsSyncedLines.isEmpty() || lyricsLineViews.isEmpty()) return
        val pos = player?.positionMs ?: return
        val idx = lyricsSyncedLines.indexOfLast { it.timeMs <= pos }
        if (idx == lyricsActiveLine) return
        lyricsLineViews.getOrNull(lyricsActiveLine)?.let {
            it.setTextColor(white50)
            it.typeface = regularTf
        }
        lyricsActiveLine = idx
        val view = lyricsLineViews.getOrNull(idx) ?: return
        view.setTextColor(Color.WHITE)
        view.typeface = boldTf
        val y = (view.top - dp(140)).coerceAtLeast(0)
        if (reduceMotion()) lyricsScroll.scrollTo(0, y) else lyricsScroll.smoothScrollTo(0, y)
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
                manual.forEach { qi -> queueList.addView(queueRow(queue[qi], isCurrent = false, queuePos = qi)) }
            }
            if (rest.isNotEmpty()) {
                queueList.addView(sectionTitle("Next Up"))
                rest.forEach { qi -> queueList.addView(queueRow(queue[qi], isCurrent = false, queuePos = qi)) }
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

    /** Removes one non-current track from the queue, remapping the
     *  play order and the manual-queue set through the same index
     *  shift clearManualQueue uses. The current track is never
     *  removable here (it has no ✕). */
    private fun removeFromQueue(qi: Int) {
        if (qi !in queue.indices || qi == queueIndex) return
        val kept = queue.indices.filter { it != qi }
        val oldToNew = kept.mapIndexed { newIdx, oldIdx -> oldIdx to newIdx }.toMap()
        playOrder = playOrder.filter { it != qi }.map { oldToNew.getValue(it) }
        val newManual = manualIndices.filter { it != qi }.map { oldToNew.getValue(it) }
        manualIndices.clear()
        manualIndices.addAll(newManual)
        queue = kept.map { queue[it] }
        if (qi < queueIndex) queueIndex -= 1
        showQueue()
    }

    private fun queueRow(track: Track, isCurrent: Boolean, queuePos: Int = -1): LinearLayout {
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
        if (isCurrent && isPlayingSafe()) row.addView(visualizer())
        if (!isCurrent && queuePos >= 0) {
            val remove = label("✕", 15f, white50).apply {
                setPadding(dp(10), dp(6), dp(4), dp(6))
                isClickable = true
                contentDescription = "Remove from queue"
                setOnClickListener { removeFromQueue(queuePos) }
            }
            row.addView(remove)
        }
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

    private fun setting(name: String, default: Boolean, onChange: ((Boolean) -> Unit)? = null): Switch {
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
                    updateVisualizerState()
                }
                if (name == "Compact mini-player") {
                    val lp = mini.layoutParams as LinearLayout.LayoutParams
                    lp.height = dp(if (checked) 48 else 58)
                    mini.layoutParams = lp
                    // The layout listener re-fits the content inset.
                }
                onChange?.invoke(checked)
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
        visibleScreen = title
        content.removeAllViews()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The avatar must never be cut by the row's bounds (below).
            clipChildren = false
            if (showAvatar) {
                // Root cause of the clipped avatar (v4 screenshots):
                // the row's height was driven by the TITLE's text
                // metrics — at smaller font scales the 32sp title
                // measures ~31dp, less than the avatar's 34dp + margin
                // footprint — and the row clipped the circle's bottom.
                // The row now guarantees room for the full circle.
                minimumHeight = dp(44)
            }
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
            // No top margin (vertical centering handles placement);
            // the end side keeps the content's standard 22dp inset via
            // the content padding, aligned with every other row.
            val aLp = LinearLayout.LayoutParams(dp(34), dp(34))
            aLp.marginStart = dp(10)
            head.addView(avatar, aLp)
        }
        content.addView(head)
        // Cheap 150ms fade so tab switches don't hard-cut.
        if (reduceMotion()) {
            content.alpha = 1f
        } else {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(150).start()
        }
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
        flushListened()
        handler.removeCallbacks(crossfadeTick)
        outPlayer?.release()
        player?.release()
        executor.shutdownNow()
        playerExecutor.shutdownNow()
        super.onDestroy()
    }
}
