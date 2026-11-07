package com.arzx.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local library stores for v5: playlists, favorites (Liked Songs),
 * play history / listening stats, and imported on-device files.
 *
 * Everything lives in one SharedPreferences file ("arzxsh_library")
 * as JSON, the same persistence style the rest of the app already
 * uses (recents, downloads index). All functions are cheap index
 * reads or small writes; call them from any thread, but prefer the
 * background executor for the bigger lists.
 */

private const val LIB_PREFS = "arzxsh_library"

fun trackToJson(t: Track): JSONObject = JSONObject().apply {
    put("id", t.id)
    put("title", t.title)
    put("artist", t.artist)
    put("duration", t.duration)
    put("source", t.source)
    put("artistId", t.artistId ?: JSONObject.NULL)
    put("artworkSmall", t.artworkSmall ?: JSONObject.NULL)
    put("artworkLarge", t.artworkLarge ?: JSONObject.NULL)
}

fun trackFromJson(o: JSONObject): Track? = try {
    Track(
        o.getString("id"), o.getString("title"), o.optString("artist", ""),
        o.optInt("duration"),
        o.optString("artworkSmall", null)?.takeIf { it.isNotEmpty() },
        o.optString("artworkLarge", null)?.takeIf { it.isNotEmpty() },
        o.optString("source", SOURCE_AUDIUS),
        o.optString("artistId", null)?.takeIf { it.isNotEmpty() }
    )
} catch (_: Exception) {
    null
}

fun trackKey(t: Track): String = "${t.source}_${t.id}"

// ------------------------------------------------------------ playlists

data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val tracks: List<Track>,
)

object Playlists {
    private const val KEY = "playlists"

    /** Parsed-once in-memory cache (0.6.3, audit S2): [all] used to
     *  re-parse the ENTIRE playlists JSON on every call, and it is
     *  called from navigation fingerprints and playback paths.
     *  Writes replace the cache, so it can never go stale. */
    @Volatile
    private var cached: List<Playlist>? = null

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Playlist> {
        cached?.let { return it }
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        val parsed = try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val ta = o.optJSONArray("tracks") ?: JSONArray()
                Playlist(
                    o.getString("id"),
                    o.optString("name", "Playlist"),
                    o.optLong("createdAt"),
                    List(ta.length()) { j -> trackFromJson(ta.getJSONObject(j)) }
                        .filterNotNull(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        cached = parsed
        return parsed
    }

    fun get(ctx: Context, id: String): Playlist? = all(ctx).firstOrNull { it.id == id }

    private fun write(ctx: Context, list: List<Playlist>) {
        cached = list
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("createdAt", p.createdAt)
                val ta = JSONArray()
                p.tracks.forEach { ta.put(trackToJson(it)) }
                put("tracks", ta)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun update(ctx: Context, id: String, change: (Playlist) -> Playlist) {
        write(ctx, all(ctx).map { if (it.id == id) change(it) else it })
    }

    fun create(ctx: Context, name: String): Playlist {
        val p = Playlist(
            id = "pl_" + System.currentTimeMillis().toString(36) +
                "_" + (0..9999).random().toString(36),
            name = name.trim().ifEmpty { "Untitled Playlist" },
            createdAt = System.currentTimeMillis(),
            tracks = emptyList()
        )
        write(ctx, all(ctx) + p)
        return p
    }

    fun rename(ctx: Context, id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        update(ctx, id) { it.copy(name = n) }
    }

    fun delete(ctx: Context, id: String) {
        write(ctx, all(ctx).filter { it.id != id })
    }

    /** Appends the track unless the playlist already holds it. */
    fun addTrack(ctx: Context, id: String, track: Track): Boolean {
        var added = false
        update(ctx, id) { p ->
            if (p.tracks.any { trackKey(it) == trackKey(track) }) {
                p
            } else {
                added = true
                p.copy(tracks = p.tracks + track)
            }
        }
        return added
    }

    fun removeTrackAt(ctx: Context, id: String, index: Int) {
        update(ctx, id) { p ->
            if (index in p.tracks.indices) {
                p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != index })
            } else p
        }
    }

    /** Moves the track at [from] to [to] (explicit reorder control). */
    fun moveTrack(ctx: Context, id: String, from: Int, to: Int) {
        update(ctx, id) { p ->
            if (from !in p.tracks.indices || to !in p.tracks.indices || from == to) {
                p
            } else {
                val m = p.tracks.toMutableList()
                val item = m.removeAt(from)
                m.add(to, item)
                p.copy(tracks = m)
            }
        }
    }

}

// ------------------------------------------------------------ favorites

/**
 * Liked Songs store. v4.1 kept favorites as bare `fav_<id>` booleans
 * in the activity preferences — enough for a heart, not enough for a
 * list. This store keeps the full track. MainActivity bridges the
 * two: the heart reads both, toggling writes both, and a one-time
 * backfill (from recents / downloads / local files) rescues pre-v5
 * likes into this store.
 */
object Favorites {
    private const val KEY = "favorites"

    /** Parsed-once caches (0.6.3, audit S2): [contains] sits on the
     *  playback-UI path (every state change re-checked the heart by
     *  re-parsing the whole Liked list). The key set makes it O(1). */
    @Volatile
    private var cached: List<Track>? = null

    @Volatile
    private var cachedKeys: Set<String> = emptySet()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    /** Most recently liked first. */
    fun liked(ctx: Context): List<Track> {
        cached?.let { return it }
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        val parsed = try {
            val arr = JSONArray(raw)
            List(arr.length()) { trackFromJson(arr.getJSONObject(it)) }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
        cached = parsed
        cachedKeys = parsed.map { trackKey(it) }.toHashSet()
        return parsed
    }

    fun contains(ctx: Context, track: Track): Boolean {
        liked(ctx)
        return trackKey(track) in cachedKeys
    }

    private fun write(ctx: Context, list: List<Track>) {
        cached = list
        cachedKeys = list.map { trackKey(it) }.toHashSet()
        val arr = JSONArray()
        list.forEach { arr.put(trackToJson(it)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, track: Track) {
        val cur = liked(ctx).filter { trackKey(it) != trackKey(track) }
        write(ctx, listOf(track) + cur)
    }

    fun remove(ctx: Context, track: Track) {
        write(ctx, liked(ctx).filter { trackKey(it) != trackKey(track) })
    }
}

// ------------------------------------------------------------ history & stats

/**
 * Play history: one event per play() call (replays included), plus a
 * listened-time total accumulated from real player position time.
 * The listened total is an APPROXIMATION built from 500ms ticker
 * samples while audio is actually playing, and the stats screen
 * labels it as such.
 */
object PlayHistory {
    private const val KEY_EVENTS = "history_events"
    private const val KEY_LISTENED = "listened_ms_total"
    private const val MAX_EVENTS = 300

    /** Parsed-once event cache (0.6.3, audit S2/S10): [record] used
     *  to parse all ≤300 events, re-serialize them and write the
     *  whole string back ON THE MAIN THREAD on every play(). Now
     *  the in-memory list updates synchronously (so stats reads are
     *  always current) and the serialization + prefs write run on
     *  this single order-preserving thread. */
    @Volatile
    private var cachedEvents: MutableList<Pair<Track, Long>>? = null

    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    fun record(ctx: Context, track: Track) {
        val events = events(ctx).toMutableList()
        events.add(0, track to System.currentTimeMillis())
        while (events.size > MAX_EVENTS) events.removeAt(events.size - 1)
        cachedEvents = events
        // Snapshot for the persist thread: the live list keeps
        // mutating with later plays, and this write must carry the
        // state as of THIS record (single thread = write order is
        // record order, so the last write always wins correctly).
        val snapshot = ArrayList(events)
        val appCtx = ctx.applicationContext
        persistExecutor.execute {
            val arr = JSONArray()
            snapshot.forEach { (t, ts) ->
                arr.put(JSONObject().apply {
                    put("track", trackToJson(t))
                    put("ts", ts)
                })
            }
            prefs(appCtx).edit().putString(KEY_EVENTS, arr.toString()).apply()
        }
    }

    /** Newest first. */
    fun events(ctx: Context): List<Pair<Track, Long>> {
        cachedEvents?.let { return it }
        val raw = prefs(ctx).getString(KEY_EVENTS, null) ?: return emptyList()
        val parsed = try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val t = trackFromJson(o.getJSONObject("track")) ?: return@List null
                t to o.optLong("ts")
            }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
        val mutable = parsed.toMutableList()
        cachedEvents = mutable
        return mutable
    }

    fun totalPlays(ctx: Context): Int = events(ctx).size

    fun topTracks(ctx: Context, limit: Int = 10): List<Pair<Track, Int>> {
        val counts = LinkedHashMap<String, Pair<Track, Int>>()
        for ((t, _) in events(ctx)) {
            val k = trackKey(t)
            val cur = counts[k]
            counts[k] = t to ((cur?.second ?: 0) + 1)
        }
        return counts.values.sortedByDescending { it.second }.take(limit)
    }

    /** (artist name, plays, a track carrying an artistId when known). */
    fun topArtists(ctx: Context, limit: Int = 10): List<Triple<String, Int, Track?>> {
        val counts = LinkedHashMap<String, Int>()
        val rep = HashMap<String, Track>()
        for ((t, _) in events(ctx)) {
            val name = t.artist.trim()
            if (name.isEmpty()) continue
            counts[name] = (counts[name] ?: 0) + 1
            val cur = rep[name]
            if (cur == null || (cur.artistId == null && t.artistId != null)) rep[name] = t
        }
        return counts.entries.sortedByDescending { it.value }
            .take(limit)
            .map { Triple(it.key, it.value, rep[it.key]) }
    }

    fun addListened(ctx: Context, ms: Long) {
        if (ms <= 0) return
        val p = prefs(ctx)
        p.edit().putLong(KEY_LISTENED, p.getLong(KEY_LISTENED, 0L) + ms).apply()
    }

    fun listenedMs(ctx: Context): Long = prefs(ctx).getLong(KEY_LISTENED, 0L)

}

// ------------------------------------------------------------ local files

/**
 * Imported on-device files. A local track's id IS its content:// URI
 * string; playback goes through MediaPlayer's Uri data source, and
 * access survives restarts via the persisted read permission taken
 * at import time. The files themselves are never copied.
 */
object LocalTracks {
    private const val KEY = "local_tracks"

    /** Parsed-once cache (0.6.3, audit S2 pattern). */
    @Volatile
    private var cached: List<Track>? = null

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Track> {
        cached?.let { return it }
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        val parsed = try {
            val arr = JSONArray(raw)
            List(arr.length()) { trackFromJson(arr.getJSONObject(it)) }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
        cached = parsed
        return parsed
    }

    private fun write(ctx: Context, list: List<Track>) {
        cached = list
        val arr = JSONArray()
        list.forEach { arr.put(trackToJson(it)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Adds newly imported tracks, skipping URIs already imported. */
    fun addAll(ctx: Context, tracks: List<Track>): Int {
        val cur = all(ctx)
        val known = cur.map { it.id }.toHashSet()
        val fresh = tracks.filter { it.id !in known }
        if (fresh.isNotEmpty()) write(ctx, cur + fresh)
        return fresh.size
    }

    fun remove(ctx: Context, track: Track) {
        write(ctx, all(ctx).filter { it.id != track.id })
    }
}

// ------------------------------------------------------------ lyrics sync offsets (v6)

/**
 * Per-track lyrics sync offsets — the real-world cure for "lyrics
 * aren't synced": LRCLIB's timing belongs to the exact release it
 * was submitted against, and a different edit of the same song can
 * sit a second or two off. The offset shifts the LRC timestamps for
 * BOTH the highlight and tap-to-seek, so what you tap is where you
 * land. A global default (Settings → Playback) applies to tracks
 * with no per-track value.
 *
 * Sign convention (one place, applied everywhere): the effective
 * line time is `lineTimeMs - offsetMs`. A POSITIVE offset therefore
 * makes lines trigger EARLIER (use it when lyrics lag behind the
 * singing); negative delays them.
 */
object LyricOffsets {
    private const val KEY_PREFIX = "lyric_offset:"
    private const val KEY_GLOBAL = "lyric_offset_global"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    /** Key form: the same Downloads.key(track) the lyrics cache uses. */
    fun keyFor(track: Track): String = "${track.source}_${track.id}"

    fun globalMs(ctx: Context): Long = prefs(ctx).getLong(KEY_GLOBAL, 0L)

    fun setGlobalMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY_GLOBAL, ms.coerceIn(-5000L, 5000L)).apply()
    }

    fun trackMs(ctx: Context, track: Track): Long? {
        val p = prefs(ctx)
        val k = KEY_PREFIX + keyFor(track)
        return if (p.contains(k)) p.getLong(k, 0L) else null
    }

    fun setTrackMs(ctx: Context, track: Track, ms: Long) {
        prefs(ctx).edit()
            .putLong(KEY_PREFIX + keyFor(track), ms.coerceIn(-5000L, 5000L)).apply()
    }

    fun clearTrack(ctx: Context, track: Track) {
        prefs(ctx).edit().remove(KEY_PREFIX + keyFor(track)).apply()
    }

    /** Effective offset for a track: its own value, else the global. */
    fun effectiveMs(ctx: Context, track: Track): Long =
        trackMs(ctx, track) ?: globalMs(ctx)
}

