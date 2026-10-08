package com.tilixibiesi.service

import com.tilixibiesi.R
import com.tilixibiesi.bili.BiliLyric
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.ui.MainPagerActivity
import com.tilixibiesi.util.NotificationHelper
import com.tilixibiesi.util.VideoPlaybackController

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

class VideoPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "com.tilixibiesi.VIDEO_CHANNEL"
        const val NOTIFICATION_ID = 1002

        const val ACTION_SHOW = "com.tilixibiesi.VIDEO_SHOW"
        const val ACTION_TOGGLE = "com.tilixibiesi.VIDEO_TOGGLE"
        const val ACTION_PREV = "com.tilixibiesi.VIDEO_PREV"
        const val ACTION_NEXT = "com.tilixibiesi.VIDEO_NEXT"
        const val ACTION_STOP = "com.tilixibiesi.VIDEO_STOP"
        const val ACTION_SEEK = "com.tilixibiesi.VIDEO_SEEK"

        const val EXTRA_TITLE = "EXTRA_VIDEO_TITLE"
        const val EXTRA_SUBTITLE = "EXTRA_VIDEO_SUBTITLE"
        const val EXTRA_IS_PLAYING = "EXTRA_VIDEO_IS_PLAYING"
        const val EXTRA_SEEK_POSITION = "EXTRA_VIDEO_SEEK_POSITION"

        private const val PROGRESS_TICK_MS = 1000L

        @Volatile
        private var active = false

        @Volatile
        private var currentTitle: String = ""
        @Volatile
        private var currentSubtitle: String = ""

        @Volatile
        private var currentLyric: BiliLyric? = null

        fun attachLyric(context: Context, lyric: BiliLyric?) {
            currentLyric = lyric
            if (!active) return
            deliver(context, ACTION_SHOW, null, VideoPlaybackController.isPlaying())
        }

        fun show(context: Context, title: String, subtitle: String = "") {
            active = true
            currentTitle = title
            currentSubtitle = subtitle
            deliver(context, ACTION_SHOW, title, true, subtitle, asForeground = true)
            MusicPlayerService.pauseForVideoPlayback(context)
        }

        fun updateState(context: Context, playing: Boolean) {
            if (!active) return
            deliver(context, ACTION_SHOW, null, playing)
        }

        fun refreshProgressSetting(context: Context) {
            if (!active) return
            deliver(context, ACTION_SHOW, null, VideoPlaybackController.isPlaying())
        }

        fun stop(context: Context) {
            if (!active) return
            active = false
            currentTitle = ""
            deliver(context, ACTION_STOP)
        }

        private fun deliver(
            context: Context,
            action: String,
            title: String? = null,
            playing: Boolean? = null,
            subtitle: String? = null,
            asForeground: Boolean = false
        ) {
            val intent = Intent(context, VideoPlaybackService::class.java).apply {
                this.action = action
                if (title != null) putExtra(EXTRA_TITLE, title)
                if (playing != null) putExtra(EXTRA_IS_PLAYING, playing)
                if (subtitle != null) putExtra(EXTRA_SUBTITLE, subtitle)
            }
            runCatching {
                if (asForeground) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaSession: MediaSession? = null
    @Volatile
    private var currentPlaying = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
    }

    private fun ensureMediaSession() {
        if (mediaSession == null) initMediaSession()
    }

    private fun initMediaSession() {
        releaseMediaSession()
        val session = MediaSession(this, "VideoPlaybackService").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (!VideoPlaybackController.isAttached()) {
                        finishSelf()
                        return
                    }
                    VideoPlaybackController.resume()
                    syncStateSoon()
                    restartProgressTicker()
                }

                override fun onPause() {
                    VideoPlaybackController.pause()
                    syncStateSoon()
                }

                override fun onSeekTo(pos: Long) {
                    if (!VideoPlaybackController.isAttached()) {
                        finishSelf()
                        return
                    }
                    VideoPlaybackController.seekTo(pos.toInt())
                    syncStateSoon()
                }

                override fun onSkipToPrevious() {
                    VideoPlaybackController.switchPrevious()
                }

                override fun onSkipToNext() {
                    VideoPlaybackController.switchNext()
                }

                override fun onStop() {
                    finishSelf()
                }
            })

            setPlaybackState(buildPlaybackState(currentPlaying))
            isActive = true
        }
        mediaSession = session
    }

    private fun buildPlaybackState(
        playing: Boolean,
        positionMs: Long = 0,
        durationMs: Long = 0
    ): PlaybackState {
        val known = durationMs > 0 && isProgressEnabled()
        val builder = PlaybackState.Builder()
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                if (known) positionMs.coerceAtLeast(0) else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1.0f else 0f
            )
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_STOP or
                    if (isProgressEnabled()) PlaybackState.ACTION_SEEK_TO else 0
            )
        return builder.build()
    }

    private fun updateSession(
        title: String,
        subtitle: String,
        playing: Boolean,
        positionMs: Long = 0,
        durationMs: Long = 0
    ) {
        val session = mediaSession ?: return
        runCatching {
            val effectiveDuration = if (isProgressEnabled()) durationMs else 0L

            if (effectiveDuration > 0 && sessionLastDuration != effectiveDuration) {
                sessionLastDuration = effectiveDuration
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, effectiveDuration)
                        .build()
                )
                sessionLastTitle = title
                sessionLastSubtitle = subtitle
            } else if (sessionLastTitle != title || sessionLastSubtitle != subtitle ||
                sessionLastDuration != effectiveDuration
            ) {
                sessionLastTitle = title
                sessionLastSubtitle = subtitle
                sessionLastDuration = effectiveDuration
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                        .apply { if (effectiveDuration > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, effectiveDuration) }
                        .build()
                )
            }
            session.setPlaybackState(buildPlaybackState(playing, positionMs, durationMs))
        }
    }

    private fun isProgressEnabled(): Boolean = SpUtils.isVideoNotifyProgressEnabled(this)

    private fun notificationContentText(positionMs: Long, subtitle: String, playing: Boolean): String {
        val lyric = currentLyric
        if (lyric != null) {
            val text = lyric.textAt(positionMs)
            if (!text.isNullOrEmpty()) return text
        }
        return subtitle.takeIf { it.isNotEmpty() }
            ?: if (playing) LanguageUtils.getString(this, R.string.text_playing)
            else LanguageUtils.getString(this, R.string.text_paused)
    }

    private fun videoPositionMs(): Long = VideoPlaybackController.getPosition().toLong()

    private fun contentTextChanged(positionMs: Long, subtitle: String, playing: Boolean): Boolean {
        return notificationContentText(positionMs, subtitle, playing) != lastContentText
    }

    private var lastContentText: String? = null

    private fun releaseMediaSession() {
        stopProgressTicker()
        lastContentText = null
        mediaSession?.let { session ->
            runCatching {
                session.isActive = false
                session.release()
            }
        }
        mediaSession = null
        sessionLastTitle = null
        sessionLastSubtitle = null
        sessionLastDuration = 0L
    }

    private var sessionLastTitle: String? = null
    private var sessionLastSubtitle: String? = null
    private var sessionLastDuration: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: currentTitle
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: currentSubtitle
                currentTitle = title
                currentSubtitle = subtitle
                val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, VideoPlaybackController.isPlaying())
                currentPlaying = playing
                ensureMediaSession()
                updateSession(
                    title,
                    subtitle,
                    playing,
                    VideoPlaybackController.getPosition().toLong(),
                    VideoPlaybackController.getDuration().toLong()
                )
                startForegroundWithType(NOTIFICATION_ID, buildNotification(title, subtitle, playing))
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                } else {
                    restartProgressTicker()
                }
            }
            ACTION_TOGGLE -> {
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                    return START_STICKY
                }
                val wasPlaying = VideoPlaybackController.isPlaying()
                if (wasPlaying) {
                    VideoPlaybackController.pause()
                } else {
                    VideoPlaybackController.resume()
                    restartProgressTicker()
                }
                syncStateSoon()
            }
            ACTION_PREV -> {
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                    return START_STICKY
                }
                VideoPlaybackController.switchPrevious()
            }
            ACTION_NEXT -> {
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                    return START_STICKY
                }
                VideoPlaybackController.switchNext()
            }
            ACTION_SEEK -> {
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                    return START_STICKY
                }
                val pos = intent.getIntExtra(EXTRA_SEEK_POSITION, -1)
                if (pos >= 0) {
                    VideoPlaybackController.seekTo(pos)
                    syncStateSoon()
                }
            }
            ACTION_STOP -> finishSelf()
            else -> finishSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        active = false
        currentTitle = ""
        currentSubtitle = ""
        currentLyric = null
        releaseMediaSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun syncStateSoon() {
        mainHandler.postDelayed({
            if (!active || !VideoPlaybackController.isAttached()) {
                finishSelf()
                return@postDelayed
            }
            currentPlaying = VideoPlaybackController.isPlaying()
            updateSession(
                currentTitle,
                currentSubtitle,
                currentPlaying,
                VideoPlaybackController.getPosition().toLong(),
                VideoPlaybackController.getDuration().toLong()
            )
            pushNotification(currentTitle, currentSubtitle, currentPlaying)
        }, 200)
    }

    private fun restartProgressTicker() {
        stopProgressTicker()
        if (!isProgressEnabled() && currentLyric == null) return
        progressTicker = object : Runnable {
            override fun run() {
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                    return
                }
                val playing = VideoPlaybackController.isPlaying()
                currentPlaying = playing
                val pos = videoPositionMs()
                val dur = VideoPlaybackController.getDuration().toLong()
                if (isProgressEnabled()) {
                    updateSession(currentTitle, currentSubtitle, playing, pos, dur)
                }
                if (playing) {
                    if (contentTextChanged(pos, currentSubtitle, playing)) {
                        pushNotification(currentTitle, currentSubtitle, playing)
                    }
                    mainHandler.postDelayed(this, PROGRESS_TICK_MS)
                } else {
                    if (progressTicker === this) progressTicker = null
                    pushNotification(currentTitle, currentSubtitle, playing)
                }
            }
        }
        mainHandler.postDelayed(progressTicker!!, PROGRESS_TICK_MS)
    }

    private fun stopProgressTicker() {
        progressTicker?.let { mainHandler.removeCallbacks(it) }
        progressTicker = null
    }

    private var progressTicker: Runnable? = null

    private fun pushNotification(title: String, subtitle: String, playing: Boolean) {
        ensureMediaSession()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(title, subtitle, playing))
        }
    }

    private fun finishSelf() {
        active = false
        currentTitle = ""
        currentSubtitle = ""
        currentLyric = null
        releaseMediaSession()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun createNotificationChannel() {
        NotificationHelper.createMediaChannel(this, CHANNEL_ID, R.string.channel_video, R.string.channel_video_desc)
    }

    private fun buildNotification(title: String, subtitle: String, playing: Boolean): Notification {
        val contentIntent = Intent(this, MainPagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainPagerActivity.EXTRA_TARGET_PAGE, MainPagerActivity.PAGE_SEARCH)
        }
        val baseFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingContentIntent = PendingIntent.getActivity(this, 0, contentIntent, baseFlags)
        val toggleIntent = Intent(this, VideoPlaybackService::class.java).apply { action = ACTION_TOGGLE }
        val piToggle = PendingIntent.getService(this, 1, toggleIntent, baseFlags)
        val prevIntent = Intent(this, VideoPlaybackService::class.java).apply { action = ACTION_PREV }
        val piPrev = PendingIntent.getService(this, 2, prevIntent, baseFlags)
        val nextIntent = Intent(this, VideoPlaybackService::class.java).apply { action = ACTION_NEXT }
        val piNext = PendingIntent.getService(this, 3, nextIntent, baseFlags)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(
                notificationContentText(videoPositionMs(), subtitle, playing).also {
                    lastContentText = it
                }
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(pendingContentIntent)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        NotificationHelper.addAction(
            this, builder,
            android.R.drawable.ic_media_previous,
            LanguageUtils.getString(this, R.string.btn_prev_video_short),
            piPrev
        )
        val playPauseIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (playing) LanguageUtils.getString(this, R.string.btn_pause) else LanguageUtils.getString(this, R.string.btn_play)
        NotificationHelper.addAction(this, builder, playPauseIcon, playPauseLabel, piToggle)
        NotificationHelper.addAction(
            this, builder,
            android.R.drawable.ic_media_next,
            LanguageUtils.getString(this, R.string.btn_next_video_short),
            piNext
        )
        return builder.build()
    }

    private fun startForegroundWithType(id: Int, notification: Notification) {
        NotificationHelper.startForegroundWithMediaPlayback(this, id, notification)
    }
}
