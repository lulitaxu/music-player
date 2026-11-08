package com.arzx.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.media3.common.util.UnstableApi

/**
 * Home-screen widget (0.6.8): track title, artist, cover art and
 * prev / play-pause / next, reflecting real playback state.
 *
 * The data flow mirrors the media notification exactly, because
 * it is the same state: MainActivity's one funnel pushes
 * snapshots to [PlaybackBridge] and, in the same breath, to
 * [ArzxshWidget.syncFromState]; the conclusive stop clears both.
 * The provider's own entry point (widget placed, launcher
 * restart) renders from the bridge snapshot when the process is
 * warm and from an honest placeholder when it is not. A provider
 * update NEVER touches the network or decodes art: artwork comes
 * only from the shared loader's 256px bucket (the notification's
 * own decode), fetched on the activity-driven sync path.
 *
 * Transport buttons send the very same service actions the
 * notification uses ([PlaybackService] ACTION_TOGGLE / NEXT /
 * PREV), as immutable PendingIntents addressed to this app's own
 * components only. With no live activity owning playback, the
 * service ends itself (its documented backstop) — the widget can
 * never mint a zombie player. The body opens MainActivity.
 */
@UnstableApi
class ArzxshWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // The base class's onReceive has already filtered the
        // broadcast down to APPWIDGET_UPDATE and the widget
        // lifecycle actions — nothing else can reach this method,
        // and a re-render is the only thing it can cause.
        ArzxshWidget.renderAll(context)
    }
}

@UnstableApi
object ArzxshWidget {

    /** Last snapshot handed over by the activity's funnel. */
    @Volatile
    private var lastState: PlaybackBridge.State? = null

    /** Art for the current track, from the shared loader's 256px
     *  bucket. Never a full-size decode, never fetched from a
     *  provider entry point. */
    @Volatile
    private var artBitmap: Bitmap? = null

    @Volatile
    private var artForKey: String? = null

    /** Signature of the last pushed render. The funnel can fire
     *  several snapshots for one user action; identical renders
     *  are dropped here instead of IPC-spamming the launcher
     *  (update storms during rapid skips coalesce to real state
     *  changes only). */
    @Volatile
    private var lastSignature: String? = null

    /**
     * Funnel hook: called with every snapshot the activity pushes
     * to the bridge, and with null from the conclusive stop —
     * exactly the bridge's own lifecycle, mirrored.
     */
    fun syncFromState(context: Context, state: PlaybackBridge.State?) {
        val appCtx = context.applicationContext
        lastState = state
        if (state == null) {
            artBitmap = null
            artForKey = null
        } else if (artForKey != state.trackKey) {
            artForKey = state.trackKey
            artBitmap = null
            val url = state.artworkUrl
            if (url != null) {
                ArtworkLoader.bitmapFor(url, 256) { bmp ->
                    // Adopt only if it still belongs to the
                    // current track (fast skips outrun fetches).
                    if (bmp != null && artForKey == state.trackKey) {
                        artBitmap = bmp
                        // Art arrival is a render-worthy change
                        // even though the text signature is the
                        // same; force the next render through.
                        lastSignature = null
                        renderAll(appCtx)
                    }
                }
            }
        }
        renderAll(appCtx)
    }

    /** Renders every placed widget from the best state available:
     *  the funnel snapshot, else the bridge (warm process), else
     *  the placeholder. */
    fun renderAll(context: Context) {
        val appCtx = context.applicationContext
        val manager = AppWidgetManager.getInstance(appCtx)
        val ids = try {
            manager.getAppWidgetIds(ComponentName(appCtx, ArzxshWidgetProvider::class.java))
        } catch (_: Exception) {
            return
        }
        if (ids.isEmpty()) return
        val s = lastState ?: PlaybackBridge.state
        val signature = if (s == null) {
            "idle"
        } else {
            s.trackKey + "|" + s.isPlaying + "|" + (if (artBitmap != null) "art" else "noart")
        }
        if (signature == lastSignature) return
        lastSignature = signature
        val views = buildViews(appCtx, s)
        for (id in ids) {
            try {
                manager.updateAppWidget(id, views)
            } catch (_: Exception) {
                // A single dead widget id must not fail the rest.
            }
        }
    }

    /** Rounds the artwork's corners for the widget (0.6.13):
     *  RemoteViews cannot clip an ImageView, so the bitmap is
     *  pre-rounded on a canvas — the widget's art used to sit in
     *  the rounded card with square corners poking out of it.
     *  Runs only on state-change renders, never per frame. */
    private fun roundedArt(context: Context, src: Bitmap): Bitmap {
        val radius = 14f * context.resources.displayMetrics.density
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val path = android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
                radius, radius, android.graphics.Path.Direction.CW
            )
        }
        canvas.clipPath(path)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun buildViews(context: Context, s: PlaybackBridge.State?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_arzxsh)
        views.setTextViewText(R.id.widget_title, s?.title ?: "arzxsh")
        views.setTextViewText(R.id.widget_artist, s?.artist ?: "Tap to open")
        val art = artBitmap
        if (s != null && art != null) {
            views.setImageViewBitmap(R.id.widget_art, roundedArt(context, art))
        } else {
            views.setImageViewResource(R.id.widget_art, R.drawable.ic_logo)
        }
        views.setImageViewResource(
            R.id.widget_toggle,
            if (s?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setOnClickPendingIntent(R.id.widget_root, openIntent(context))
        views.setOnClickPendingIntent(
            R.id.widget_toggle, serviceIntent(context, PlaybackService.ACTION_TOGGLE, 11)
        )
        views.setOnClickPendingIntent(
            R.id.widget_next, serviceIntent(context, PlaybackService.ACTION_NEXT, 12)
        )
        views.setOnClickPendingIntent(
            R.id.widget_prev, serviceIntent(context, PlaybackService.ACTION_PREV, 13)
        )
        return views
    }

    private fun openIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** The notification's own action mechanism, reused verbatim:
     *  an explicit intent to our own non-exported service. */
    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
