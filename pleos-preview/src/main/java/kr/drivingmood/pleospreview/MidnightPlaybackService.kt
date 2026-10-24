package kr.drivingmood.pleospreview

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/** User-started local music; no boot receiver, streaming, automatic resume or sticky restart. */
class MidnightPlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(MidnightMusicPlayer.State) -> Unit>()
    private val binder = LocalBinder()
    private var engine: MidnightPlaybackEngine? = null
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager
    private var state = MidnightMusicPlayer.State()
    private var foregroundService = false
    private var notificationShown = false
    private var pendingUserPlay = false
    private var stopping = false
    private var destroyed = false
    private var metadataState: MidnightMusicPlayer.State? = null

    inner class LocalBinder : Binder() {
        fun addListener(listener: (MidnightMusicPlayer.State) -> Unit) { listeners += listener; listener(state) }
        fun removeListener(listener: (MidnightMusicPlayer.State) -> Unit) { listeners -= listener }
        fun prepare() = ensurePrepared()
        fun pause() = pausePlayback()
        fun seekTo(positionMs: Long) { engine?.seekTo(positionMs) }
        fun stopAndRelease() = stopPlayback()
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "음악 재생", NotificationManager.IMPORTANCE_LOW).apply {
            description = "로컬 음악 재생 제어"
            setSound(null, null)
        })
        session = MediaSession(this, "DrivingMood-local-music")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = requestUserPlay()
            override fun onPause() = pausePlayback()
            override fun onStop() = stopPlayback()
            override fun onSeekTo(pos: Long) { engine?.seekTo(pos) }
        }, main)
        session.setSessionActivity(openAppIntent())
        syncSession()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> startPlayback()
            ACTION_PAUSE -> if (promoteForeground()) pausePlayback()
            ACTION_STOP -> { promoteForeground(); stopPlayback() }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun ensurePrepared() {
        if (destroyed) return
        val current = engine
        if (current != null && (state.prepared || state.error == null)) return
        stopping = true
        current?.close()
        stopping = false
        engine = MidnightPlaybackEngine(this, ::onEngineState).also {
            // This is a service permission to play after explicit focus acquisition, not Activity visibility.
            it.setForeground(true)
        }
        engine?.prepare()
    }

    private fun requestUserPlay() {
        // MediaSession play is an explicit user media control, not automatic focus/lifecycle recovery.
        try { startForegroundService(Intent(this, MidnightPlaybackService::class.java).setAction(ACTION_PLAY)) }
        catch (_: Exception) { publishError("음악 서비스를 시작하지 못했습니다. 앱에서 다시 재생해 주세요.") }
    }

    private fun promoteForeground(): Boolean {
        if (destroyed) return false
        if (foregroundService) return true
        try {
            session.isActive = true
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION_ID, notification)
            foregroundService = true
            notificationShown = true
        } catch (_: Exception) {
            publishError("백그라운드 음악 재생을 시작하지 못했습니다.")
            stopSelf()
            return false
        }
        return true
    }

    private fun startPlayback() {
        if (!promoteForeground()) return
        // Set before prepare: only this explicit user action can play when preparation completes.
        pendingUserPlay = true
        ensurePrepared()
        if (state.prepared) {
            pendingUserPlay = false
            engine?.play()
        }
    }

    private fun pausePlayback() {
        pendingUserPlay = false
        engine?.pause()
        demoteForeground()
    }

    private fun onEngineState(next: MidnightMusicPlayer.State) {
        if (destroyed || stopping) return
        val previous = state
        state = next
        syncSession()
        listeners.toList().forEach { it(next) }
        val playAfterPrepare = pendingUserPlay && next.prepared
        if (next.error != null) pendingUserPlay = false
        if (notificationShown && (previous.playing != next.playing || previous.prepared != next.prepared ||
                previous.error != next.error || previous.art !== next.art || previous.title != next.title || previous.artist != next.artist)) {
            updateMediaNotification()
        }
        if (playAfterPrepare) {
            pendingUserPlay = false
            engine?.play()
        } else if (!next.playing && !pendingUserPlay) {
            // Paused/completed/failed playback can keep media controls, but does not keep an idle FGS.
            demoteForeground()
        }
    }

    // Only the MediaStyle notification containing this MediaSession token is posted here.
    // Media-session notifications are exempt from POST_NOTIFICATIONS; do not reuse for general alerts.
    // https://developer.android.com/develop/ui/views/notifications/notification-permission#media-sessions
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun updateMediaNotification() {
        notifications.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun demoteForeground() {
        if (foregroundService) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foregroundService = false
        }
    }

    private fun stopPlayback() {
        if (destroyed) return
        stopping = true
        pendingUserPlay = false
        engine?.close()
        engine = null
        state = MidnightMusicPlayer.State(stopped = true)
        session.isActive = false
        syncSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundService = false
        notificationShown = false
        notifications.cancel(NOTIFICATION_ID)
        listeners.toList().forEach { it(state) }
        stopping = false
        stopSelf()
    }

    private fun publishError(message: String) {
        pendingUserPlay = false
        state = state.copy(error = message)
        syncSession()
        listeners.toList().forEach { it(state) }
    }

    private fun syncSession() {
        val previous = metadataState
        if (previous == null || previous.title != state.title || previous.artist != state.artist ||
            previous.durationMs != state.durationMs || previous.art !== state.art) {
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, state.art).build())
            metadataState = state
        }
        val status = when {
            state.error != null && !state.prepared -> PlaybackState.STATE_ERROR
            state.playing -> PlaybackState.STATE_PLAYING
            state.prepared -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
            .setState(status, state.positionMs, if (state.playing) 1f else 0f, SystemClock.elapsedRealtime())
            .setErrorMessage(state.error).build())
    }

    private fun buildNotification(): Notification {
        val action = if (state.playing) ACTION_PAUSE else ACTION_PLAY
        val toggle = PendingIntent.getForegroundService(this, 1, Intent(this, MidnightPlaybackService::class.java).setAction(action), PENDING_FLAGS)
        val stop = PendingIntent.getForegroundService(this, 2, Intent(this, MidnightPlaybackService::class.java).setAction(ACTION_STOP), PENDING_FLAGS)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.title).setContentText(state.error ?: state.artist)
            .setLargeIcon(state.art).setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT).setOngoing(state.playing)
            .addAction(Notification.Action.Builder(if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.playing) "일시정지" else "재생", toggle).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "음악 종료", stop).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, PreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PENDING_FLAGS)

    override fun onDestroy() {
        destroyed = true
        pendingUserPlay = false
        engine?.close()
        engine = null
        listeners.clear()
        session.isActive = false
        session.release()
        metadataState = null
        notifications.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "kr.drivingmood.pleospreview.music.PLAY"
        const val ACTION_PAUSE = "kr.drivingmood.pleospreview.music.PAUSE"
        const val ACTION_STOP = "kr.drivingmood.pleospreview.music.STOP"
        private const val CHANNEL_ID = "midnight_music"
        private const val NOTIFICATION_ID = 4101
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
