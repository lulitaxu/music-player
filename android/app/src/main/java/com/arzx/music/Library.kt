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
    /** Last time this playlist was opened/played; drives the
     *  Library "Recents" sort. Falls back to [createdAt]. */
    val lastUsedAt: Long = createdAt
)

object Playlists {
    private const val KEY = "playlists"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Playlist> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
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
                    o.optLong("lastUsedAt", o.optLong("createdAt"))
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(ctx: Context, id: String): Playlist? = all(ctx).firstOrNull { it.id == id }

    private fun write(ctx: Context, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("createdAt", p.createdAt)
                put("lastUsedAt", p.lastUsedAt)
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

    /** Stamps the playlist as used now (Library Recents sort). */
    fun touch(ctx: Context, id: String) {
        update(ctx, id) { it.copy(lastUsedAt = System.currentTimeMillis()) }
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

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    /** Most recently liked first. */
    fun liked(ctx: Context): List<Track> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { trackFromJson(arr.getJSONObject(it)) }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun contains(ctx: Context, track: Track): Boolean =
        liked(ctx).any { trackKey(it) == trackKey(track) }

    private fun write(ctx: Context, list: List<Track>) {
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

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    fun record(ctx: Context, track: Track) {
        val events = events(ctx).toMutableList()
        events.add(0, track to System.currentTimeMillis())
        while (events.size > MAX_EVENTS) events.removeAt(events.size - 1)
        val arr = JSONArray()
        events.forEach { (t, ts) ->
            arr.put(JSONObject().apply {
                put("track", trackToJson(t))
                put("ts", ts)
            })
        }
        prefs(ctx).edit().putString(KEY_EVENTS, arr.toString()).apply()
    }

    /** Newest first. */
    fun events(ctx: Context): List<Pair<Track, Long>> {
        val raw = prefs(ctx).getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val t = trackFromJson(o.getJSONObject("track")) ?: return@List null
                t to o.optLong("ts")
            }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
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

    // ---- v6 taste signals: completions & early skips ----
    // A completion = the track reached its natural end. An early
    // skip = playback moved on before ~30% (wired in MainActivity's
    // play()/completion paths). Stored as event lists in the same
    // shape as play events so the taste profile can decay them by
    // age identically.

    private const val KEY_COMPLETIONS = "history_completions"
    private const val KEY_SKIPS = "history_skips"

    private fun recordInto(ctx: Context, key: String, track: Track) {
        val cur = readEvents(ctx, key).toMutableList()
        cur.add(0, track to System.currentTimeMillis())
        while (cur.size > MAX_EVENTS) cur.removeAt(cur.size - 1)
        val arr = JSONArray()
        cur.forEach { (t, ts) ->
            arr.put(JSONObject().apply {
                put("track", trackToJson(t))
                put("ts", ts)
            })
        }
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }

    private fun readEvents(ctx: Context, key: String): List<Pair<Track, Long>> {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val t = trackFromJson(o.getJSONObject("track")) ?: return@List null
                t to o.optLong("ts")
            }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordCompletion(ctx: Context, track: Track) = recordInto(ctx, KEY_COMPLETIONS, track)

    fun recordSkip(ctx: Context, track: Track) = recordInto(ctx, KEY_SKIPS, track)

    /** Newest first. */
    fun completions(ctx: Context): List<Pair<Track, Long>> = readEvents(ctx, KEY_COMPLETIONS)

    /** Newest first. */
    fun skips(ctx: Context): List<Pair<Track, Long>> = readEvents(ctx, KEY_SKIPS)

    fun completionCount(ctx: Context, track: Track): Int =
        completions(ctx).count { trackKey(it.first) == trackKey(track) }

    fun skipCount(ctx: Context, track: Track): Int =
        skips(ctx).count { trackKey(it.first) == trackKey(track) }
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

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Track> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { trackFromJson(arr.getJSONObject(it)) }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(ctx: Context, list: List<Track>) {
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

// ------------------------------------------------------------ saved remote collections (v6)

/**
 * A remote (YouTube Music) collection the user saved to the Library:
 * a playlist, an album, or an artist page. Only the reference is
 * stored — opening one refetches its contents from the catalog, so
 * a saved entry is a bookmark, not an offline copy. [payload] is the
 * JSON needed to rebuild the ref (playlist/album/artist fields).
 */
data class SavedCollection(
    val kind: String, // "playlist" | "album" | "artist"
    val refId: String,
    val title: String,
    val subtitle: String,
    val artworkSmall: String?,
    val artworkLarge: String?,
    val payload: String,
    val savedAt: Long,
    val lastUsedAt: Long = savedAt
) {
    /** Stable identity for pins/recency: "saved:<kind>:<refId>". */
    val key: String get() = "saved:$kind:$refId"
}

object SavedCollections {
    private const val KEY = "saved_collections"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<SavedCollection> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                SavedCollection(
                    o.getString("kind"),
                    o.getString("refId"),
                    o.optString("title", ""),
                    o.optString("subtitle", ""),
                    o.optString("artworkSmall", null)?.takeIf { it.isNotEmpty() },
                    o.optString("artworkLarge", null)?.takeIf { it.isNotEmpty() },
                    o.optString("payload", "{}"),
                    o.optLong("savedAt"),
                    o.optLong("lastUsedAt", o.optLong("savedAt"))
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(ctx: Context, list: List<SavedCollection>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("kind", s.kind)
                put("refId", s.refId)
                put("title", s.title)
                put("subtitle", s.subtitle)
                put("payload", s.payload)
                put("savedAt", s.savedAt)
                put("lastUsedAt", s.lastUsedAt)
                put("artworkSmall", s.artworkSmall ?: JSONObject.NULL)
                put("artworkLarge", s.artworkLarge ?: JSONObject.NULL)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun contains(ctx: Context, kind: String, refId: String): Boolean =
        all(ctx).any { it.kind == kind && it.refId == refId }

    fun save(ctx: Context, s: SavedCollection) {
        val cur = all(ctx).filterNot { it.kind == s.kind && it.refId == s.refId }
        write(ctx, listOf(s) + cur)
    }

    fun remove(ctx: Context, kind: String, refId: String) {
        write(ctx, all(ctx).filterNot { it.kind == kind && it.refId == refId })
    }

    fun touch(ctx: Context, kind: String, refId: String) {
        val now = System.currentTimeMillis()
        write(ctx, all(ctx).map {
            if (it.kind == kind && it.refId == refId) it.copy(lastUsedAt = now) else it
        })
    }
}

// ------------------------------------------------------------ library pins & recency (v6)

/**
 * Pinned Library entries (Spotify's pin in Your Library). Keys:
 * "user:<playlistId>", "liked", "downloaded", "saved:<kind>:<refId>".
 * Pinned entries sort first in the Library grid and carry the small
 * green pin glyph.
 */
object Pins {
    private const val KEY = "library_pins"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    private fun set(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()

    fun isPinned(ctx: Context, key: String): Boolean = key in set(ctx)

    fun setPinned(ctx: Context, key: String, pinned: Boolean) {
        val cur = set(ctx).toMutableSet()
        if (pinned) cur.add(key) else cur.remove(key)
        prefs(ctx).edit().putStringSet(KEY, cur).apply()
    }
}

/** Last-opened timestamps for the fixed Library tiles (Liked,
 *  Downloaded), whose recency isn't otherwise recorded. */
object TileRecency {
    private const val KEY = "tile_recency"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    fun touch(ctx: Context, key: String) {
        prefs(ctx).edit().putLong("$KEY:$key", System.currentTimeMillis()).apply()
    }

    fun get(ctx: Context, key: String): Long =
        prefs(ctx).getLong("$KEY:$key", 0L)
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

// ------------------------------------------------------------ taste profile (v6)

/**
 * The on-device taste profile: what the "Made for You" surfaces are
 * ranked from. Nothing here leaves the device — the profile is
 * computed from local stores and only ever used to seed ordinary
 * catalog requests (radio / artist pages) and to rank their results.
 *
 * Artist affinity = Σ over signals, each time-decayed with a 7-day
 * half-life so the last two weeks dominate:
 *   play +1 · completion +2 · like +4 (no decay) ·
 *   download +3 (no decay) · early skip −2
 * Likes/downloads are standing preferences, not moments, so they
 * don't decay.
 *
 * The computed profile is cached; it recomputes when a day has
 * passed or 20+ new plays landed (the Daily Mix refresh rule).
 */
object Taste {
    data class Profile(
        val artistAffinity: Map<String, Double>,
        /** Artists by affinity, highest first (positive scores only). */
        val topArtists: List<Pair<String, Double>>,
        val playsAtCompute: Int,
        val computedAt: Long
    )

    private const val KEY = "taste_profile"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    private fun decay(ts: Long): Double {
        val ageDays = (System.currentTimeMillis() - ts).coerceAtLeast(0L) / 86_400_000.0
        return Math.pow(0.5, ageDays / 7.0)
    }

    private fun artistOf(t: Track): String = t.artist.trim()

    fun compute(ctx: Context): Profile {
        val affinity = HashMap<String, Double>()
        fun bump(t: Track, w: Double) {
            val a = artistOf(t)
            if (a.isEmpty() || w == 0.0) return
            affinity[a] = (affinity[a] ?: 0.0) + w
        }
        for ((t, ts) in PlayHistory.events(ctx)) bump(t, 1.0 * decay(ts))
        for ((t, ts) in PlayHistory.completions(ctx)) bump(t, 2.0 * decay(ts))
        for ((t, ts) in PlayHistory.skips(ctx)) bump(t, -2.0 * decay(ts))
        for (t in Favorites.liked(ctx)) bump(t, 4.0)
        for (e in Downloads.entries(ctx)) bump(e.track, 3.0)
        val top = affinity.entries
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
            .map { it.key to it.value }
        val profile = Profile(affinity, top, PlayHistory.totalPlays(ctx), System.currentTimeMillis())
        // Persist (top 25 artists is plenty for ranking).
        try {
            val o = JSONObject()
            o.put("computedAt", profile.computedAt)
            o.put("playsAtCompute", profile.playsAtCompute)
            val arr = JSONArray()
            top.take(25).forEach { (name, score) ->
                arr.put(JSONArray().put(name).put(score))
            }
            o.put("artists", arr)
            prefs(ctx).edit().putString(KEY, o.toString()).apply()
        } catch (_: Exception) {
            // Cache failure must never break recommendations.
        }
        return profile
    }

    fun cached(ctx: Context): Profile? {
        val raw = prefs(ctx).getString(KEY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("artists") ?: JSONArray()
            val top = List(arr.length()) { i ->
                val pair = arr.getJSONArray(i)
                pair.getString(0) to pair.getDouble(1)
            }
            Profile(
                top.toMap(),
                top,
                o.optInt("playsAtCompute"),
                o.optLong("computedAt")
            )
        } catch (_: Exception) {
            null
        }
    }

    /** The profile to rank with right now: cached unless stale
     *  (a day old, or 20+ plays since it was computed). */
    fun fresh(ctx: Context): Profile {
        val c = cached(ctx) ?: return compute(ctx)
        val dayPassed = System.currentTimeMillis() - c.computedAt >= 86_400_000L
        val playsMoved = PlayHistory.totalPlays(ctx) - c.playsAtCompute >= 20
        return if (dayPassed || playsMoved) compute(ctx) else c
    }

    /** Affinity for one artist name (0 when unknown). */
    fun affinityFor(profile: Profile, artist: String): Double =
        profile.artistAffinity[artist.trim()] ?: 0.0
}
