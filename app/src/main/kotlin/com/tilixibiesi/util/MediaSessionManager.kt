package com.tilixibiesi.util

import android.content.Context
import com.tilixibiesi.data.SpUtils
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

interface MediaSessionCallback {
    fun onPlayRequested()
    fun onPauseRequested()
    fun onSkipToPreviousRequested()
    fun onSkipToNextRequested()
    fun onStopRequested()
    fun onSeekToRequested(pos: Long)
}

class MediaSessionManager {
    private var mediaSession: MediaSession? = null

    private var appContext: Context? = null

    private var lastDurationMs: Long = -1L
    private var lastTitle: String? = null

    fun init(context: Context, callback: MediaSessionCallback) {
        appContext = context.applicationContext
        mediaSession?.release()
        mediaSession = MediaSession(context, "MusicPlayerService").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    callback.onPlayRequested()
                }

                override fun onPause() {
                    callback.onPauseRequested()
                }

                override fun onSkipToPrevious() {
                    callback.onSkipToPreviousRequested()
                }

                override fun onSkipToNext() {
                    callback.onSkipToNextRequested()
                }

                override fun onStop() {
                    callback.onStopRequested()
                }

                override fun onSeekTo(pos: Long) {
                    callback.onSeekToRequested(pos)
                }
            })

            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                                PlaybackState.ACTION_PAUSE or
                                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                PlaybackState.ACTION_SKIP_TO_NEXT or
                                PlaybackState.ACTION_STOP or
                                if (isProgressEnabled()) PlaybackState.ACTION_SEEK_TO else 0
                    )
                    .build()
            )
            isActive = true
        }
    }

    private fun isProgressEnabled(): Boolean =
        appContext?.let { SpUtils.isVideoNotifyProgressEnabled(it) } ?: true

    fun setTrackMetadata(title: String, durationMs: Long, force: Boolean = false) {
        val session = mediaSession ?: return
        val normalized = if (durationMs > 0 && isProgressEnabled()) durationMs else 0L
        if (!force && title == lastTitle && normalized == lastDurationMs) return
        lastTitle = title
        lastDurationMs = normalized
        runCatching {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .apply { if (normalized > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, normalized) }
                    .build()
            )
        }
    }

    fun clearTrackMetadata() {
        lastTitle = null
        lastDurationMs = -1L
        runCatching { mediaSession?.setMetadata(null) }
    }

    fun updatePlaybackState(state: Int, position: Long = 0, speed: Float = 1.0f) {
        val progress = isProgressEnabled()
        val hasPosition = progress &&
            (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED)
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    state,
                    if (hasPosition) position.coerceAtLeast(0) else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    speed
                )
                .setActions(
                    PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_STOP or
                            if (progress) PlaybackState.ACTION_SEEK_TO else 0
                )
                .build()
        )
    }

    fun getSessionToken(): MediaSession.Token? {
        return mediaSession?.sessionToken
    }

    fun release() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }
}
