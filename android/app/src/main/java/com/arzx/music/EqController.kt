package com.arzx.music

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import kotlin.math.roundToInt

/**
 * Built-in equalizer (0.6.8): a 5-band [Equalizer] plus [BassBoost]
 * riding the CURRENT player's audio session.
 *
 * Why a controller: the player is not a singleton. Every track
 * builds a fresh ExoPlayer with its own audio session, crossfade
 * runs two at once, and a new activity instance can claim the
 * process from a stale one. The activity therefore never wires
 * effects by hand — it calls [sync] with the current player's
 * session id from its one state funnel (refreshPlaybackUi), and
 * the controller re-attaches whenever the id changes: old effects
 * released first, new ones created on the live session, stored
 * settings reapplied. A dead session can never be double-attached
 * or leaked across a swap, and a failed attach (session died
 * mid-swap, effect unsupported) simply retries on the next funnel
 * pass instead of crashing.
 *
 * Settings persist as FRACTIONS of the device's band range, not
 * raw millibels: level ranges and band counts vary by device, and
 * a fraction means the same musical thing everywhere. The device
 * profile (band count, centre frequencies, level range) is read
 * from the first live effect and cached, so the UI can render
 * honestly before anything has played — with the standard 5-band
 * table as the pre-first-attach fallback.
 *
 * Main thread only, like everything it touches. Every audiofx
 * call is exception-shielded: effects attached to a session whose
 * player just died throw RuntimeException on some devices, and an
 * EQ must never be the thing that breaks playback.
 */
class EqController(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var attachedSession = 0

    // ------------------------------------------------------------ state

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) {
            prefs.edit().putBoolean(KEY_ENABLED, v).apply()
            applyEnabled()
            applyBass()
        }

    val presetName: String
        get() = prefs.getString(KEY_PRESET, PRESET_FLAT) ?: PRESET_FLAT

    /** Bass boost strength, 0..1000 (the audiofx unit). */
    val bassStrength: Int
        get() = prefs.getInt(KEY_BASS, 0).coerceIn(0, 1000)

    fun bandFraction(band: Int): Float =
        prefs.getFloat(KEY_BAND_PREFIX + band, 0f).coerceIn(-1f, 1f)

    // ------------------------------------------------------------ profile

    /** Bands the UI should show: live count when attached, else
     *  the cached device profile, else the standard five. */
    fun uiBandCount(): Int {
        val live = try { equalizer?.numberOfBands?.toInt() } catch (_: Exception) { null }
        val n = live ?: prefs.getInt(KEY_PROFILE_BANDS, STANDARD_CENTERS_HZ.size)
        return n.coerceIn(1, MAX_BANDS)
    }

    /** Centre-frequency label for a band ("60 Hz", "3.6 kHz"):
     *  live from the effect when attached, else the cached
     *  profile, else the standard table. */
    fun centerLabel(band: Int): String {
        val liveHz = try {
            equalizer?.let { it.getCenterFreq(band.toShort()) / 1000 }
        } catch (_: Exception) {
            null
        }
        val hz = liveHz ?: prefs.getString(KEY_PROFILE_CENTERS, null)
            ?.split(",")?.getOrNull(band)?.toIntOrNull()
            ?: STANDARD_CENTERS_HZ.getOrElse(band) { STANDARD_CENTERS_HZ.last() }
        return if (hz < 1000) "$hz Hz" else {
            val khz = hz / 1000f
            (if (khz % 1f == 0f) "${khz.toInt()}" else "%.1f".format(khz)) + " kHz"
        }
    }

    /** dB label for a stored fraction ("+6.0 dB"), against the
     *  same scale [levelForBand] applies. */
    fun dbLabel(band: Int): String {
        val db = bandFraction(band) * scaleMb() / 100f
        return (if (db > 0) "+" else "") + "%.1f".format(db) + " dB"
    }

    /** Half-range in millibels: the symmetric scale fractions map
     *  onto (min of the two range ends, so a fraction can never
     *  push a level outside the device's real range). */
    private fun scaleMb(): Int {
        val range = try { equalizer?.bandLevelRange } catch (_: Exception) { null }
        val min = range?.getOrNull(0)?.toInt() ?: prefs.getInt(KEY_PROFILE_MIN, -1500)
        val max = range?.getOrNull(1)?.toInt() ?: prefs.getInt(KEY_PROFILE_MAX, 1500)
        return minOf(max, -min).coerceAtLeast(0)
    }

    private fun levelForBand(band: Int): Short {
        val range = try { equalizer?.bandLevelRange } catch (_: Exception) { null }
        val min = range?.getOrNull(0)?.toInt() ?: prefs.getInt(KEY_PROFILE_MIN, -1500)
        val max = range?.getOrNull(1)?.toInt() ?: prefs.getInt(KEY_PROFILE_MAX, 1500)
        return (bandFraction(band) * scaleMb()).roundToInt()
            .coerceIn(min, max).toShort()
    }

    // ------------------------------------------------------------ attach

    /** Follows the player: attach to [sessionId] if it is not the
     *  session already held. Cheap no-op on the common path (same
     *  live session), which is why the activity can call it from
     *  its state funnel on every pass. sessionId <= 0 means "no
     *  player yet" and is ignored — the next pass retries. */
    fun sync(sessionId: Int) {
        if (sessionId <= 0) return
        if (equalizer != null && attachedSession == sessionId) return
        releaseEffects()
        attachedSession = sessionId
        try {
            val eq = Equalizer(0, sessionId)
            equalizer = eq
            cacheProfile(eq)
            applyEnabled()
            applyBands()
        } catch (_: Exception) {
            equalizer = null
        }
        try {
            val bb = BassBoost(0, sessionId)
            bassBoost = bb
            applyBass()
        } catch (_: Exception) {
            bassBoost = null
        }
    }

    /** Releases both effects. The activity calls this in its
     *  teardown; a stale instance releasing its own effects can
     *  never touch a newer instance's (they are separate objects
     *  on, by then, separate sessions). */
    fun release() {
        releaseEffects()
        attachedSession = 0
    }

    private fun releaseEffects() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
    }

    private fun cacheProfile(eq: Equalizer) {
        try {
            val bands = eq.numberOfBands.toInt().coerceIn(1, MAX_BANDS)
            val centers = (0 until bands).joinToString(",") { (eq.getCenterFreq(it.toShort()) / 1000).toString() }
            val range = eq.bandLevelRange
            prefs.edit()
                .putInt(KEY_PROFILE_BANDS, bands)
                .putString(KEY_PROFILE_CENTERS, centers)
                .putInt(KEY_PROFILE_MIN, range[0].toInt())
                .putInt(KEY_PROFILE_MAX, range[1].toInt())
                .apply()
        } catch (_: Exception) {
            // Profile caching is a UI nicety; the live effect
            // above already has the real values for applying.
        }
    }

    // ------------------------------------------------------------ apply

    private fun applyEnabled() {
        val eq = equalizer ?: return
        try { eq.enabled = enabled } catch (_: Exception) {}
    }

    private fun applyBands() {
        val eq = equalizer ?: return
        for (band in 0 until uiBandCount()) {
            try { eq.setBandLevel(band.toShort(), levelForBand(band)) } catch (_: Exception) {}
        }
    }

    private fun applyBass() {
        val bb = bassBoost ?: return
        try {
            bb.setStrength(bassStrength.toShort())
            bb.enabled = enabled && bassStrength > 0
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ user changes

    /** Applies a named preset's band fractions. Bass boost is a
     *  separate control and is never touched by presets. */
    fun setPreset(name: String) {
        val gains = PRESETS[name] ?: return
        val e = prefs.edit()
        e.putString(KEY_PRESET, name)
        for (band in gains.indices) e.putFloat(KEY_BAND_PREFIX + band, gains[band])
        e.apply()
        applyBands()
    }

    /** One slider moved: store the fraction, mark the sound
     *  Custom, push the level to the live effect if attached. */
    fun setBandFraction(band: Int, fraction: Float) {
        prefs.edit()
            .putFloat(KEY_BAND_PREFIX + band, fraction.coerceIn(-1f, 1f))
            .putString(KEY_PRESET, PRESET_CUSTOM)
            .apply()
        val eq = equalizer ?: return
        try { eq.setBandLevel(band.toShort(), levelForBand(band)) } catch (_: Exception) {}
    }

    fun setBassStrength(strength: Int) {
        prefs.edit().putInt(KEY_BASS, strength.coerceIn(0, 1000)).apply()
        applyBass()
    }

    companion object {
        private const val PREFS = "arzxsh_eq"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET = "preset"
        private const val KEY_BASS = "bass"
        private const val KEY_BAND_PREFIX = "band_"
        private const val KEY_PROFILE_BANDS = "profile_bands"
        private const val KEY_PROFILE_CENTERS = "profile_centers"
        private const val KEY_PROFILE_MIN = "profile_min"
        private const val KEY_PROFILE_MAX = "profile_max"

        const val MAX_BANDS = 5
        const val PRESET_FLAT = "Flat"
        const val PRESET_CUSTOM = "Custom"

        /** The standard 5-band centre table (Hz), used only until
         *  the first live effect reports the device's real ones. */
        private val STANDARD_CENTERS_HZ = intArrayOf(60, 230, 910, 3600, 14000)

        /** Preset band fractions, lowest band first. */
        val PRESETS: Map<String, FloatArray> = linkedMapOf(
            PRESET_FLAT to floatArrayOf(0f, 0f, 0f, 0f, 0f),
            "Bass Boost" to floatArrayOf(0.85f, 0.55f, 0.2f, 0f, 0f),
            "Treble" to floatArrayOf(0f, 0f, 0.2f, 0.55f, 0.85f),
            "Vocal" to floatArrayOf(-0.2f, 0.15f, 0.6f, 0.5f, -0.2f)
        )
    }
}
