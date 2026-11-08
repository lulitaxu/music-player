package com.arzx.music

import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap

/**
 * 0.6.9 motion foundation: ONE spring system for the whole app.
 *
 * Before 0.6.9 every animated surface carried its own fixed
 * duration + interpolator (260ms artwork scale, 230/250/300ms
 * player slides, 70/140ms play-button pop, 150/180ms overlay
 * fades), so nothing moved like anything else. The feel targets
 * come from the Apple Music / Spotify motion language Arz
 * approved: larger elements move on slower, heavier springs
 * (the Now Playing container: dampingRatio 0.95, stiffness 350,
 * the numbers a real open-source player ships for the same
 * morph), small controls answer on stiff, fast springs, and
 * artwork "breathes" (1.0 playing, 0.92 paused).
 *
 * Everything here is transform/alpha only (composited, no layout
 * passes), springs are created lazily per (view, property) and
 * reused across retargets, and every entry point takes an
 * [instant] flag: when motion is off (the app's Reduce Motion
 * setting OR the system animator duration scale at 0) callers
 * pass instant = true and the value is set directly with any
 * running spring cancelled — a spring must never outlive its
 * "off" state.
 *
 * UI thread only: the registry is a plain WeakHashMap keyed by
 * view, so destroyed views take their springs with them; the
 * activity additionally cancels springs explicitly in onDestroy
 * and on track change where a stale target would be wrong.
 */
object Springs {

    // Keyed by the property OBJECT (every property used is a
    // DynamicAnimation singleton): dynamicanimation 1.0.0 does
    // not expose FloatPropertyCompat.name to Kotlin.
    private val registry =
        WeakHashMap<View, HashMap<FloatPropertyCompat<View>, SpringAnimation>>()

    private fun getOrCreate(
        view: View,
        property: FloatPropertyCompat<View>,
        target: Float,
        dampingRatio: Float,
        stiffness: Float
    ): SpringAnimation {
        val byProperty = registry.getOrPut(view) { HashMap() }
        byProperty[property]?.let { return it }
        val anim = SpringAnimation(view, property).apply {
            spring = SpringForce(target).apply {
                this.dampingRatio = dampingRatio
                this.stiffness = stiffness
            }
        }
        byProperty[property] = anim
        return anim
    }

    /** Springs [view]'s [property] to [target]; [instant] sets the
     *  value directly (motion off) after cancelling any spring. */
    fun animate(
        view: View,
        property: FloatPropertyCompat<View>,
        target: Float,
        dampingRatio: Float,
        stiffness: Float,
        instant: Boolean
    ) {
        if (instant) {
            cancelProperty(view, property)
            property.setValue(view, target)
            return
        }
        val anim = getOrCreate(view, property, target, dampingRatio, stiffness)
        anim.spring?.let {
            it.finalPosition = target
            it.dampingRatio = dampingRatio
            it.stiffness = stiffness
        }
        if (!anim.isRunning) anim.start()
    }

    /** Uniform scale spring (artwork breathing, press feedback). */
    fun scale(view: View, target: Float, dampingRatio: Float, stiffness: Float, instant: Boolean) {
        animate(view, DynamicAnimation.SCALE_X, target, dampingRatio, stiffness, instant)
        animate(view, DynamicAnimation.SCALE_Y, target, dampingRatio, stiffness, instant)
    }

    fun translationY(view: View, target: Float, dampingRatio: Float, stiffness: Float, instant: Boolean) =
        animate(view, DynamicAnimation.TRANSLATION_Y, target, dampingRatio, stiffness, instant)

    fun alpha(view: View, target: Float, dampingRatio: Float, stiffness: Float, instant: Boolean) =
        animate(view, DynamicAnimation.ALPHA, target, dampingRatio, stiffness, instant)

    /** Cancels ONE property's spring on [view], leaving the value
     *  wherever it currently sits. */
    fun cancelProperty(view: View, property: FloatPropertyCompat<View>) {
        val byProperty = registry[view] ?: return
        byProperty.remove(property)?.cancel()
        if (byProperty.isEmpty()) registry.remove(view)
    }

    /** Cancels every spring on [view] (track change, teardown). */
    fun cancel(view: View) {
        registry.remove(view)?.values?.forEach { it.cancel() }
    }

    /** A free-floating spring-driven float (the Now Playing morph
     *  progress): SpringAnimation needs an object + property, and
     *  a panel position is not a View property. */
    class Value(var value: Float = 0f)

    val VALUE_PROPERTY: FloatPropertyCompat<Value> =
        object : FloatPropertyCompat<Value>("springValue") {
            override fun getValue(obj: Value): Float = obj.value
            override fun setValue(obj: Value, value: Float) {
                obj.value = value
            }
        }

    /** Builds a spring over a [Value]; the caller owns start /
     *  retarget / listeners. */
    fun valueSpring(
        holder: Value,
        target: Float,
        dampingRatio: Float,
        stiffness: Float
    ): SpringAnimation = SpringAnimation(holder, VALUE_PROPERTY).apply {
        spring = SpringForce(target).apply {
            this.dampingRatio = dampingRatio
            this.stiffness = stiffness
        }
    }
}

/**
 * The one shared press treatment (0.6.9): finger down springs the
 * view to 0.92, release springs it back to 1.0. Primary controls
 * (play/pause) get the stiffer, faster spring; secondary buttons
 * the softer one — buttons must answer faster than big surfaces
 * move. The listener NEVER consumes the event, so the view's
 * click listener (and any parent gesture handling) is untouched;
 * views that already own a touch listener (the mini player
 * container's swipe handler, the seek bar) are never given this.
 *
 * [off] is consulted per event: with motion off the scale is
 * simply pinned to 1.0 and no spring runs.
 */
object MotionPress {

    private const val DOWN_SCALE = 0.92f
    private const val PRIMARY_DAMPING = 0.55f
    private const val PRIMARY_STIFFNESS = 1500f
    private const val SECONDARY_DAMPING = 0.70f
    private const val SECONDARY_STIFFNESS = 900f

    /** Attachment markers (0.6.13 audit fix): attach() REPLACES
     *  the view's touch listener, so a second attach on the same
     *  view silently discarded the first press treatment (and
     *  any other listener the view owned). A debug guard now
     *  makes the double-attach loud instead of silent. A
     *  registry marker rather than View.setTag: several views
     *  here already use their tag (the artwork loader's load
     *  key), and clobbering it would break image delivery.
     *  UI-thread confined, like the Springs registry. */
    private val attached = WeakHashMap<View, Boolean>()

    fun attach(view: View, primary: Boolean, off: () -> Boolean) {
        if (attached.containsKey(view)) {
            android.util.Log.w(
                "MotionPress",
                "attach() called twice on the same view — second call ignored"
            )
            return
        }
        attached[view] = true
        val damping = if (primary) PRIMARY_DAMPING else SECONDARY_DAMPING
        val stiffness = if (primary) PRIMARY_STIFFNESS else SECONDARY_STIFFNESS
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    Springs.scale(v, DOWN_SCALE, damping, stiffness, instant = off())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    Springs.scale(v, 1f, damping, stiffness, instant = off())
            }
            false
        }
    }
}
