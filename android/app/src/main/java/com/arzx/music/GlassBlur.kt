package com.arzx.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.max

/**
 * Real backdrop blur for the floating chrome (0.6.9 glass rework).
 *
 * Why a snapshot: Android Views have NO backdrop-filter
 * primitive for a view inside a window. RenderEffect (API 31+)
 * blurs a view's OWN rendering, not what is behind it; window
 * blur-behind applies between windows, and the mini player /
 * tab bar live inside this app's single activity window.
 * The live alternative, a BlurView-style continuous capture,
 * re-renders the whole root on every frame the content scrolls
 * — exactly the per-frame cost the perf rules for the Helio
 * G95 exist to prevent, and its pre-31 path rides deprecated
 * RenderScript.
 *
 * So the chrome frosts over a SNAPSHOT: the shell's scroll
 * content is drawn once into a 96px-wide bitmap and box-blurred
 * (the same downscale + box-blur recipe ArtworkLoader already
 * ships for the Now Playing backdrop). The activity recaptures
 * it at discrete moments only — shell build, screen change,
 * scroll settling, track change — never per frame, so the cost
 * is one tiny draw + blur per settle, and between recaptures
 * the snapshot is a static bitmap: zero ongoing cost. Named
 * trade-off: while the page is actively scrolling, the frost
 * does not scroll with it; it refreshes ~250ms after the
 * scroll settles. Over a near-opaque tint at the bottom edge
 * of the screen this reads as frosted glass, not a picture.
 *
 * [GlassBlurDrawable] draws the slice of that bitmap that lies
 * behind the view (aligned by window coordinates, so the mini
 * player and the bottom bar share one bitmap seamlessly), then
 * the tint fill, then a hairline stroke. Bitmap and tint come
 * from providers, so a track change (new tint) or a recapture
 * (new bitmap) needs only an invalidate, never a rebuild.
 */
class GlassBlurDrawable(
    private val radiusPx: Float,
    private val strokeWidthPx: Float,
    private val bitmapProvider: () -> Bitmap?,
    private val tintProvider: () -> Int,
    private val strokeProvider: () -> Int
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val loc = IntArray(2)

    /** The drawable alpha, kept in a FIELD (0.6.13 audit fix):
     *  [draw] calls paint.reset() before every layer, which
     *  silently wiped the alpha setAlpha had just stored on the
     *  paint — setAlpha was a no-op and any fade of a glass
     *  surface snapped instead of fading. Re-applied after
     *  each reset in [draw]. */
    private var drawableAlpha = 255
    private var drawableColorFilter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bf = RectF(b)
        path.reset()
        if (radiusPx > 0f) {
            path.addRoundRect(bf, radiusPx, radiusPx, Path.Direction.CW)
        } else {
            path.addRect(bf, Path.Direction.CW)
        }
        val save = canvas.save()
        canvas.clipPath(path)
        val bmp = bitmapProvider()
        val view = callback as? View
        if (bmp != null && view != null && !bmp.isRecycled) {
            view.getLocationInWindow(loc)
            val root = view.rootView
            val sx = bmp.width.toFloat() / max(1, root.width)
            val sy = bmp.height.toFloat() / max(1, root.height)
            val src = Rect(
                ((loc[0] + b.left) * sx).toInt(),
                ((loc[1] + b.top) * sy).toInt(),
                ((loc[0] + b.right) * sx).toInt().coerceAtMost(bmp.width),
                ((loc[1] + b.bottom) * sy).toInt().coerceAtMost(bmp.height)
            )
            if (!src.isEmpty) {
                paint.reset()
                paint.isFilterBitmap = true
                paint.alpha = drawableAlpha
                paint.colorFilter = drawableColorFilter
                canvas.drawBitmap(bmp, src, bf, paint)
            }
        }
        // Tint fill: dark, artwork-tinted, translucent enough for
        // the blur to ghost through — the glass read comes from
        // this layering, not from a painted shine.
        paint.reset()
        paint.style = Paint.Style.FILL
        paint.color = tintProvider()
        paint.alpha = drawableAlpha
        paint.colorFilter = drawableColorFilter
        canvas.drawRect(bf, paint)
        canvas.restoreToCount(save)
        // Hairline rim, inset so it never bleeds outside the fill.
        val stroke = strokeProvider()
        if (strokeWidthPx > 0f && Color.alpha(stroke) > 0) {
            paint.reset()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidthPx
            paint.color = stroke
            paint.alpha = drawableAlpha
            paint.colorFilter = drawableColorFilter
            val inset = strokeWidthPx / 2f
            val sf = RectF(bf).apply { inset(inset, inset) }
            if (radiusPx > 0f) {
                canvas.drawRoundRect(sf, radiusPx, radiusPx, paint)
            } else {
                canvas.drawRect(sf, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable opacity contract")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Snapshot capture + blur for [GlassBlurDrawable]. */
object GlassCapture {

    /** Draws [source] into a 96px-wide bitmap and blurs it.
     *  Returns null when the source has not been laid out yet;
     *  the caller retries at the next discrete moment. */
    fun capture(source: View): Bitmap? {
        val raw = captureRaw(source) ?: return null
        return blur(raw)
    }

    /** UI-thread half of [capture] (0.6.10): ONLY the draw.
     *  Drawing a view hierarchy must happen on its own thread,
     *  but it is the short half of the work — the pixel loops in
     *  [blur] are the long half, and those used to run on the UI
     *  thread in the same call, right in the middle of tab
     *  switches and scroll settles, eating the frames (and the
     *  taps) that landed during them. Split so the caller can
     *  blur on a background pool and swap the result in later. */
    fun captureRaw(source: View): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null
        val w = 96
        val scale = w.toFloat() / source.width
        val h = max(1, (source.height * scale).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        source.draw(canvas)
        return bmp
    }

    /** Background-safe half of [capture] (0.6.10): the three
     *  box-blur passes over [raw]. Takes ownership of [raw]
     *  (recycled as an intermediate) and returns the blurred
     *  bitmap. Pure pixel work on an owned bitmap — no views,
     *  no shared state — so any thread may run it. */
    fun blur(raw: Bitmap): Bitmap {
        // The hard downscale does most of the softening (same
        // recipe as the Now Playing backdrop); three horizontal
        // box passes finish it.
        var bmp = raw
        repeat(3) {
            val next = boxBlur(bmp, 5)
            if (next !== bmp) bmp.recycle()
            bmp = next
        }
        return bmp
    }

    /** Horizontal box blur, the ArtworkLoader algorithm. */
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
