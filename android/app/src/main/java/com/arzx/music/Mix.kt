package com.arzx.music

import android.content.Context
import org.json.JSONObject

/**
 * Mix (0.6.19): per-playlist, per-pair track transitions, in the
 * spirit of Spotify's Premium Mix — an editor on the playlist
 * page, real overlap execution in the player, on-device BPM/key
 * analysis (see TrackAnalysis.kt) and a Smart Reorder.
 *
 * DATA MODEL. A transition belongs to an ORDERED PAIR of tracks
 * inside ONE playlist: the store key is
 * "<playlistId>" and inside it "<outKey>><inKey>", where a track
 * key is the app's usual "<source>_<id>" (see [trackKey]).
 * Pair-keying is deliberate: reordering the playlist can never
 * silently attach a transition to the wrong pair — a stored
 * transition applies exactly when those two tracks play back to
 * back (in that order) from that playlist, and every other
 * adjacency falls back to Auto (the global crossfade behavior).
 * Nothing here touches the playlist JSON itself, so deleting a
 * playlist's transitions is independent of the playlist store;
 * deleting the playlist orphans its (tiny) entry, pruned lazily
 * on read when the playlist no longer exists is unnecessary —
 * entries are a few hundred bytes and die with the prefs file.
 */

enum class MixStyle(val label: String) {
    FADE("Fade"),
    RISE("Rise"),
    BLEND("Blend")
}

/**
 * One custom transition. Absence of an entry IS Auto.
 *
 * [lengthSec] — how long the two tracks overlap.
 * [outSkipSec] — the outgoing track's tail is trimmed by this
 *   much: the overlap window ends this many seconds before the
 *   outgoing track's natural end (its out-point).
 * [inSkipSec] — the incoming track starts this many seconds in
 *   (its in-point; skips a quiet intro).
 */
data class MixTransition(
    val style: MixStyle,
    val lengthSec: Int,
    val outSkipSec: Int,
    val inSkipSec: Int
) {
    fun summary(): String =
        "${style.label} · ${lengthSec}s"
}

object MixStore {
    private const val PREFS = "arzxsh_mix"
    private const val PREFIX = "mix:"

    /** Parsed per-playlist maps, cached; writes replace. */
    private val cache = HashMap<String, Map<String, MixTransition>>()

    fun pairKey(out: Track, incoming: Track): String =
        trackKey(out) + ">" + trackKey(incoming)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context, playlistId: String): Map<String, MixTransition> {
        cache[playlistId]?.let { return it }
        val raw = prefs(ctx).getString(PREFIX + playlistId, null)
        val parsed = LinkedHashMap<String, MixTransition>()
        if (raw != null) {
            try {
                val o = JSONObject(raw)
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    try {
                        val v = o.getJSONObject(k)
                        val style = MixStyle.valueOf(v.getString("style"))
                        parsed[k] = MixTransition(
                            style,
                            v.optInt("len", 8).coerceIn(1, 16),
                            v.optInt("out", 0).coerceIn(0, 15),
                            v.optInt("in", 0).coerceIn(0, 15)
                        )
                    } catch (_: Exception) {
                        // Skip the bad pair; the rest stand.
                    }
                }
            } catch (_: Exception) {
                // Wholly unparseable: treat as no customs.
            }
        }
        cache[playlistId] = parsed
        return parsed
    }

    fun get(ctx: Context, playlistId: String, out: Track, incoming: Track): MixTransition? =
        all(ctx, playlistId)[pairKey(out, incoming)]

    @Synchronized
    fun set(ctx: Context, playlistId: String, out: Track, incoming: Track, t: MixTransition?) {
        val map = LinkedHashMap(all(ctx, playlistId))
        if (t == null) {
            map.remove(pairKey(out, incoming))
        } else {
            map[pairKey(out, incoming)] = t
        }
        cache[playlistId] = map
        val o = JSONObject()
        for ((k, v) in map) {
            o.put(k, JSONObject().apply {
                put("style", v.style.name)
                put("len", v.lengthSec)
                put("out", v.outSkipSec)
                put("in", v.inSkipSec)
            })
        }
        prefs(ctx).edit().putString(PREFIX + playlistId, o.toString()).apply()
    }
}

// ------------------------------------------------------------ musical key / Camelot

/** Pitch-class names (sharps), index = semitone above C. */
val KEY_NAMES = arrayOf(
    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
)

/**
 * Camelot code for a key: [root] is the pitch class (0 = C),
 * [minor] picks the A (minor) ring over the B (major) ring. The
 * standard DJ wheel: minor ring 8A = A minor, major ring 8B = C
 * major, numbers advance by perfect fifths.
 */
/** Fifths above C for each pitch class (C=0 … B=11):
 *  C G D A E B F# Db Ab Eb Bb F → 0 1 2 3 4 5 6 7 8 9 10 11. */
private val FIFTHS_ABOVE_C = intArrayOf(0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10, 5)

fun camelotCode(root: Int, minor: Boolean): String {
    val pc = ((root % 12) + 12) % 12
    return if (minor) {
        // A minor key shares its Camelot number with its
        // relative major (root + 3 semitones): A minor = C
        // major's number, 8, on the A ring.
        val relMajor = (pc + 3) % 12
        "${((FIFTHS_ABOVE_C[relMajor] + 7) % 12) + 1}A"
    } else {
        "${((FIFTHS_ABOVE_C[pc] + 7) % 12) + 1}B"
    }
}

/** Camelot compatibility cost between two codes: 0 = perfect
 *  (same code), 1 = DJ-compatible (relative major/minor — same
 *  number, other ring — or one step around the wheel, same
 *  ring), 3 = a clash. Unparseable codes cost 3. */
fun camelotCost(a: String, b: String): Int {
    if (a == b) return 0
    fun parse(s: String): Pair<Int, Char>? {
        val ring = s.lastOrNull() ?: return null
        val num = s.dropLast(1).toIntOrNull() ?: return null
        if (ring != 'A' && ring != 'B') return null
        return num to ring
    }
    val (na, ra) = parse(a) ?: return 3
    val (nb, rb) = parse(b) ?: return 3
    if (na == nb) return 1 // relative major/minor
    val step = minOf((na - nb + 12) % 12, (nb - na + 12) % 12)
    if (step == 1 && ra == rb) return 1
    return 3
}

// ------------------------------------------------------------ Smart Reorder

object SmartReorder {

    /**
     * Greedy smooth order for [tracks] given the analyses we
     * actually have ([known], keyed by [trackKey]):
     *
     *  - Tracks WITH analysis are sequenced greedily: start from
     *    the first track of the playlist that has data (the
     *    user's opener stays the opener when it can), then
     *    repeatedly append the unused analyzed track with the
     *    lowest cost against the last placed one — cost is the
     *    BPM gap (every 10 BPM ≈ 1 point, capped at 4) plus the
     *    Camelot cost (0 / 1 / 3). Ties keep the track that came
     *    earlier in the user's order.
     *  - Tracks WITHOUT analysis keep their relative order and
     *    go at the END: we know nothing about how they flow, and
     *    inventing a place for them would be faking the feature.
     *
     * Pure and deterministic, so the UI can preview the outcome
     * ("would change N positions") before applying.
     */
    fun reorder(tracks: List<Track>, known: Map<String, TrackAnalysis>): List<Track> {
        val analyzed = tracks.filter { trackKey(it) in known }
        val unknown = tracks.filter { trackKey(it) !in known }
        if (analyzed.size < 2) return tracks
        val remaining = analyzed.toMutableList()
        val start = remaining.first()
        val out = ArrayList<Track>(tracks.size)
        out += start
        remaining.remove(start)
        while (remaining.isNotEmpty()) {
            val last = known.getValue(trackKey(out.last()))
            var best: Track? = null
            var bestCost = Double.MAX_VALUE
            for (cand in remaining) {
                val a = known.getValue(trackKey(cand))
                val bpmCost = minOf(kotlin.math.abs(a.bpm - last.bpm) / 10.0, 4.0)
                val cost = bpmCost + camelotCost(last.camelot, a.camelot)
                if (cost < bestCost) {
                    bestCost = cost
                    best = cand
                }
            }
            val chosen = best ?: break
            out += chosen
            remaining.remove(chosen)
        }
        out += unknown
        return out
    }
}
