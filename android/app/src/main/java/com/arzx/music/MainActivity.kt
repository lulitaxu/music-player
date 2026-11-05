package com.arzx.music

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
    val duration: Int
)

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val tracks = mutableListOf<Track>()
    private var player: MediaPlayer? = null
    private var current: Track? = null
    private lateinit var content: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniButton: Button
    private val orange = Color.rgb(255, 92, 61)
    private val background = Color.rgb(10, 10, 12)
    private val secondary = Color.rgb(154, 154, 163)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showShell()
        showHome()
    }

    private fun showShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 8)
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val mini = LinearLayout(this).apply {
            setPadding(16, 10, 16, 10)
            setBackgroundColor(Color.rgb(23, 23, 27))
            gravity = Gravity.CENTER_VERTICAL
        }
        miniTitle = label("Nothing playing", 15, Color.WHITE)
        mini.addView(miniTitle, LinearLayout.LayoutParams(0, -2, 1f))
        miniButton = Button(this).apply {
            text = "Play"
            setOnClickListener { togglePlayback() }
        }
        mini.addView(miniButton)
        root.addView(mini)

        val nav = LinearLayout(this).apply {
            setBackgroundColor(Color.rgb(23, 23, 27))
            gravity = Gravity.CENTER
        }
        listOf("Home", "Search", "Library", "Downloads", "Settings").forEach { name ->
            nav.addView(Button(this).apply {
                text = name
                setOnClickListener { when (name) {
                    "Home" -> showHome()
                    "Search" -> showSearch()
                    "Library" -> showEmpty("Library", "Your library is empty", "Imported files and playlists will appear here.")
                    "Downloads" -> showEmpty("Downloads", "No downloads yet", "Only authorized provider downloads will appear here.")
                    else -> showSettings()
                } }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        setContentView(root)
    }

    private fun showHome() {
        clear("arzxsh")
        val loading = label("Loading trending…", 16, secondary)
        content.addView(loading)
        executor.execute {
            val result = fetch("/tracks/trending?limit=25")
            runOnUiThread {
                content.removeView(loading)
                if (result == null || result.isEmpty()) {
                    content.addView(label("No trending tracks", 18, Color.WHITE))
                    content.addView(label("Playable tracks are unavailable right now.", 14, secondary))
                } else {
                    tracks.clear()
                    tracks.addAll(result)
                    result.forEach { addTrackRow(it) }
                }
            }
        }
    }

    private fun showSearch() {
        clear("Search")
        val input = EditText(this).apply {
            hint = "Songs or artists"
            setTextColor(Color.WHITE)
            setHintTextColor(secondary)
            singleLine = true
        }
        val search = Button(this).apply { text = "Search" }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        search.setOnClickListener {
            val query = input.text.toString().trim()
            if (query.isEmpty()) return@setOnClickListener
            results.removeAllViews()
            results.addView(label("Searching catalogs…", 14, secondary))
            executor.execute {
                val found = fetch("/tracks/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=25")
                runOnUiThread {
                    results.removeAllViews()
                    if (found == null) {
                        results.addView(label("Playable results are unavailable right now.", 14, secondary))
                    } else if (found.isEmpty()) {
                        results.addView(label("No results", 18, Color.WHITE))
                    } else {
                        found.forEach { track ->
                            addTrackRow(track, results)
                        }
                    }
                }
            }
        }
        content.addView(input)
        content.addView(search)
        content.addView(results)
    }

    private fun showSettings() {
        clear("Settings")
        content.addView(label("About", 19, Color.WHITE))
        content.addView(label("Version 0.2.0", 15, secondary))
        content.addView(label("Streams via Audius. Metadata via MusicBrainz.", 15, secondary))
        content.addView(label("Lyrics: Not available yet", 15, secondary))
        content.addView(label("No DRM bypass, ever.", 15, secondary))
    }

    private fun showEmpty(title: String, heading: String, message: String) {
        clear(title)
        content.addView(label(heading, 19, Color.WHITE))
        content.addView(label(message, 15, secondary))
    }

    private fun addTrackRow(track: Track, target: LinearLayout = content) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(track.title, 16, Color.WHITE))
        text.addView(label("${track.artist} · Playable", 13, orange))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Play"
            setOnClickListener { play(track) }
        })
        target.addView(row)
    }

    private fun play(track: Track) {
        current = track
        miniTitle.text = "${track.title} · ${track.artist}"
        miniButton.text = "Pause"
        val stream = "https://api.audius.co/v1/tracks/${track.id}/stream?app_name=arzxsh"
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(stream)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { miniButton.text = "Play" }
            setOnErrorListener { _, _, _ ->
                miniButton.text = "Play"
                Toast.makeText(this@MainActivity, "This track could not be played.", Toast.LENGTH_SHORT).show()
                true
            }
            prepareAsync()
        }
    }

    private fun togglePlayback() {
        val active = player ?: return
        if (active.isPlaying) {
            active.pause()
            miniButton.text = "Play"
        } else {
            active.start()
            miniButton.text = "Pause"
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
                Track(item.getString("id"), item.getString("title"), item.getJSONObject("user").getString("name"), item.optInt("duration"))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun clear(title: String) {
        content.removeAllViews()
        content.addView(label(title, 28, Color.WHITE))
    }

    private fun label(text: String, size: Int, color: Int) = TextView(this).apply {
        this.text = text
        setTextSize(size.toFloat())
        setTextColor(color)
        setPadding(0, 4, 0, 4)
    }

    override fun onDestroy() {
        player?.release()
        executor.shutdownNow()
        super.onDestroy()
    }
}
