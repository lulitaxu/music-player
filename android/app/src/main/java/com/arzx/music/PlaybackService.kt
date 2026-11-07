package com.arzx.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi

/**
 * The shared player-state bridge (0.6.1, background playback).
 *
 * The engine stays exactly where it has always lived — inside
 * MainActivity. This bridge is the ONLY coupling between the
 * activity and [PlaybackService]: the activity registers its
 * transport controls once (the very same functions the mini
 * player calls) and pushes immutable state snapshots from its
 * single UI-state funnel (refreshPlaybackUi) whenever the track
 * or the playing state changes. The service renders those
 * snapshots; it never owns, creates or releases a player.
 *
 * Everything is event-driven: no polling loops anywhere.
 */
object PlaybackBridge {

    class Controls(
        val toggle: () -> Unit,
        val next: () -> Unit,
        val prev: () -> Unit,
        val seekTo: (Long) -> Unit
    )

    data class State(
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val isPlaying: Boolean,
        val durationMs: Long,
        val positionMs: Long,
        val hasNext: Boolean,
        val hasPrev: Boolean,
        val trackKey: String
    )

    @Volatile
    var controls: Controls? = null

    @Volatile
    var state: State? = null

    /** Set by the service while it runs; invoked (on the pushing
     *  thread — always the UI thread in practice) per snapshot. */
    @Volatile
    var listener: (() -> Unit)? = null

    fun push(s: State) {
        state = s
        listener?.invoke()
    }
}

/**
 * Foreground playback service (0.6.1): renders the media
 * notification (MediaStyle: artwork, title, artist, prev /
 * play-pause / next) and hosts the media session (lock-screen
 * controls + headset/Bluetooth button routing) for the activity's
 * player. Its real job is keep-alive: a media-playback foreground
 * service is what stops Android from killing the process mid-song
 * when the app is backgrounded — before this, playback lived and
 * died with the activity's process priority.
 *
 * Pure framework APIs (android.media.session + Notification) —
 * no new dependencies. The service OBSERVES state through
 * [PlaybackBridge]; all transport actions call back into the
 * activity's own control functions.
 */
@UnstableApi
class PlaybackService : Service() {

    private lateinit var session: MediaSession

    /** Set in onDestroy: late artwork fetches and bridge pushes
     *  racing the teardown must not render into a dead service. */
    @Volatile
    private var destroyed = false

    /** Row-size artwork for the notification, adopted from the
     *  shared loader only when the track changes. */
    @Volatile
    private var artBitmap: Bitmap? = null
    @Volatile
    private var artForKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "Now playing controls"
                        setShowBadge(false)
                    }
            )
        }
        session = MediaSession(this, "arzxsh-playback")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (PlaybackBridge.state?.isPlaying != true) PlaybackBridge.controls?.toggle?.invoke()
            }

            override fun onPause() {
                if (PlaybackBridge.state?.isPlaying == true) PlaybackBridge.controls?.toggle?.invoke()
            }

            override fun onSkipToNext() {
                PlaybackBridge.controls?.next?.invoke()
            }

            override fun onSkipToPrevious() {
                PlaybackBridge.controls?.prev?.invoke()
            }

            override fun onSeekTo(pos: Long) {
                PlaybackBridge.controls?.seekTo?.invoke(pos)
            }
        })
        session.isActive = true
        PlaybackBridge.listener = { render() }
        render()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> PlaybackBridge.controls?.toggle?.invoke()
            ACTION_NEXT -> PlaybackBridge.controls?.next?.invoke()
            ACTION_PREV -> PlaybackBridge.controls?.prev?.invoke()
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // (Re)assert the foreground state with the latest snapshot:
        // startForegroundService demands this within ~5 seconds.
        render()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        PlaybackBridge.listener = null
        try {
            session.isActive = false
            session.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** Rebuilds the session state/metadata and the notification
     *  from the latest bridge snapshot. Runs on state pushes and
     *  service starts only — never on a timer. */
    private fun render() {
        if (destroyed) return
        val s = PlaybackBridge.state
        if (s != null) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs.coerceAtLeast(0L))
                    .apply {
                        artBitmap?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
                    }
                    .build()
            )
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (s.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        s.positionMs.coerceAtLeast(0L),
                        if (s.isPlaying) 1.0f else 0.0f,
                        SystemClock.elapsedRealtime()
                    )
                    .build()
            )
            fetchArtIfNeeded(s)
        }
        startForeground(NOTIF_ID, buildNotification(s))
    }

    private fun buildNotification(s: PlaybackBridge.State?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playing = s?.isPlaying == true
        // minSdk is 26, so the channel constructor is unconditional.
        val b = Notification.Builder(this, CHANNEL_ID)
        b.setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(s?.title ?: "arzxsh")
            .setContentText(s?.artist ?: "")
            .setContentIntent(openIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setShowWhen(false)
        artBitmap?.let { b.setLargeIcon(it) }
        b.setStyle(
            Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )
        b.addAction(R.drawable.ic_skip_prev, "Previous", actionIntent(ACTION_PREV, 1))
        b.addAction(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            if (playing) "Pause" else "Play",
            actionIntent(ACTION_TOGGLE, 2)
        )
        b.addAction(R.drawable.ic_skip_next, "Next", actionIntent(ACTION_NEXT, 3))
        return b.build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Artwork for a NEW track only, read through the SHARED UI
     *  loader (0.6.3, audit S3): this used to be a private 6th
     *  fetch of the same image — worse, a FULL-resolution decode
     *  scaled down afterwards. The loader's caches serve the
     *  256px bucket the UI already decoded (or decode it once on
     *  the hero thread, bytes shared with every other bucket). */
    private fun fetchArtIfNeeded(s: PlaybackBridge.State) {
        val url = s.artworkUrl
        if (url == null) {
            if (artForKey != s.trackKey) {
                artForKey = s.trackKey
                artBitmap = null
            }
            return
        }
        if (artForKey == s.trackKey) return
        artForKey = s.trackKey
        artBitmap = null
        ArtworkLoader.bitmapFor(url, 256) { bmp ->
            // Only adopt the art if it still belongs to the
            // current track (fast skips outrun fetches) and
            // the service is still alive.
            if (bmp != null && !destroyed && artForKey == s.trackKey) {
                artBitmap = bmp
                render()
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "arzxsh-playback"
        const val NOTIF_ID = 4711
        const val ACTION_TOGGLE = "com.arzx.music.TOGGLE"
        const val ACTION_NEXT = "com.arzx.music.NEXT"
        const val ACTION_PREV = "com.arzx.music.PREV"
        const val ACTION_STOP = "com.arzx.music.STOP"

        /** Starts (or re-asserts) the foreground service. Called
         *  when a track starts. A start refused by the system
         *  (background-start limits) degrades to in-app playback
         *  only — the music is never blocked on the notification. */
        fun start(ctx: Context) {
            val i = Intent(ctx, PlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // Playback continues without the service.
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, PlaybackService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
