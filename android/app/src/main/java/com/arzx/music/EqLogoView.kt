package com.arzx.music

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * The Equalizer A brand mark, alive (0.6.4, WS-4/WS-5): the
 * exact bar geometry of drawable/ic_logo (240-unit viewport,
 * white bars, red stem), drawn on canvas so the columns can
 * breathe like the release site's logo — bars rising and
 * falling out of sync, each column on its own timing. Every
 * segment scales from its own bottom edge, so the baseline
 * bars stay planted and the gaps between stacked segments
 * open and close.
 *
 * Perf contract (Helio G95 + the 0.6.3 loop discipline): ONE
 * ValueAnimator tick drives ONLY this ~28dp view; bar heights
 * are computed from the clock in onDraw — no per-bar
 * animators, no layout passes, no allocations in the frame
 * path beyond one reused RectF. The animator starts on
 * attach and cancels on detach, so it sleeps whenever the
 * Home tab is off screen; while the app is backgrounded
 * ([motionAllowed] false) the tick early-returns and nothing
 * redraws. Reduce Motion means the animator never starts and
 * the static mark is drawn. While music plays
 * ([playingProvider]) the motion runs ~1.6× faster and a
 * little taller; idle, it stays gentle.
 */
class EqLogoView(context: Context) : View(context) {

    /** Motion master switch (background / Reduce Motion). */
    var motionAllowed: () -> Boolean = { true }

    /** True while audio is playing: livens the motion. */
    var playingProvider: () -> Boolean = { false }

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(252, 60, 68) // #FC3C44, the stem
    }

    private var animator: ValueAnimator? = null
    private val rect = RectF()

    /** One bar segment: center x, nominal top/bottom in the
     *  240-unit viewport, its column (timing group), and
     *  whether it is the red stem. Mirrors ic_logo.xml. */
    private data class Seg(
        val cx: Float,
        val top: Float,
        val bottom: Float,
        val col: Int,
        val red: Boolean
    )

    private val segs = listOf(
        Seg(43f, 84f, 186f, 0, false),
        Seg(73f, 70f, 112f, 1, false),
        Seg(73f, 144f, 186f, 1, false),
        Seg(103f, 66f, 112f, 2, false),
        Seg(103f, 142f, 186f, 2, false),
        Seg(133f, 68f, 110f, 3, false),
        Seg(133f, 144f, 186f, 3, false),
        Seg(163f, 78f, 186f, 4, false),
        Seg(197f, 48f, 186f, 5, true)
    )

    // Per-column timing: phase offset + angular speed (rad/s).
    // All different — the out-of-sync drift is the point.
    private val colPhase = floatArrayOf(0.0f, 1.7f, 3.4f, 5.1f, 2.6f, 4.3f)
    private val colSpeed = floatArrayOf(2.1f, 2.7f, 2.4f, 3.0f, 2.2f, 2.6f)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!motionAllowed()) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                // Backgrounded / reduced-motion: the tick costs
                // one lambda call and draws nothing.
                if (motionAllowed()) invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 240f
        if (scale <= 0f) return
        val dx = (width - 240f * scale) / 2f
        val dy = (height - 240f * scale) / 2f
        val moving = animator != null && motionAllowed()
        val playing = playingProvider()
        val speedMul = if (playing) 1.6f else 1.0f
        val amp = if (playing) 0.24f else 0.17f
        val t = SystemClock.uptimeMillis() / 1000f
        val halfBar = 11f * scale
        for (seg in segs) {
            val f = if (moving) {
                1f + amp * sin(t * colSpeed[seg.col] * speedMul + colPhase[seg.col])
            } else {
                1f
            }
            val h = (seg.bottom - seg.top) * f
            rect.set(
                dx + seg.cx * scale - halfBar,
                dy + (seg.bottom - h) * scale,
                dx + seg.cx * scale + halfBar,
                dy + seg.bottom * scale
            )
            canvas.drawRoundRect(rect, halfBar, halfBar, if (seg.red) redPaint else whitePaint)
        }
    }
}
