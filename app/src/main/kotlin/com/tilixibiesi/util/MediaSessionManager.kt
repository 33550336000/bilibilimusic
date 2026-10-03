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

    /** 用于读取「通知进度条」开关（与视频侧共用同一个设置项） */
    private var appContext: Context? = null

    /** 上一次写入 session 的时长；用于避免重复 setMetadata（会引发通知重绘/闪烁） */
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

            // 初始化为「未播放」：位置 UNKNOWN、动作集与开关保持一致
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

    /**
     * 「通知进度条」开关（与 [com.tilixibiesi.service.VideoPlaybackService] 共用同一项设置）。
     *
     * 关闭后：不写总时长、position 退化为 UNKNOWN、去掉 ACTION_SEEK_TO，
     * 使通知退回「标题 + 播放/暂停」而不残留一条不会动的僵尸进度条。
     */
    private fun isProgressEnabled(): Boolean =
        appContext?.let { SpUtils.isVideoNotifyProgressEnabled(it) } ?: true

    /**
     * 写入当前曲目的元数据（标题 + 总时长）。
     *
     * 总时长（[MediaMetadata.METADATA_KEY_DURATION]）是通知/锁屏进度条的关键：
     * 没有它，SystemUI 不知道满量程，即使 [updatePlaybackState] 带了真实 position
     * 也画不出进度条（更谈不上拖动）。
     *
     * 只在标题或时长变化时才真正写入：每次都 setMetadata 会让 SystemUI 反复重绘
     * 大视图，表现为通知闪烁。
     *
     * @param force 为 true 时跳过"无变化则跳过"的判断。开关切换后必须强制重写，
     *   否则「标题与时长都没变、只是开关变了」会被判为无变化而不生效。
     */
    fun setTrackMetadata(title: String, durationMs: Long, force: Boolean = false) {
        val session = mediaSession ?: return
        // 关闭进度条时不写总时长：否则 SystemUI 拿到满量程，
        // 某些 ROM 仍会画出一条不能动、也拖不了的静态进度条
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

    /** 清空曲目元数据（停止播放时调用，避免锁屏残留上一首的信息） */
    fun clearTrackMetadata() {
        lastTitle = null
        lastDurationMs = -1L
        runCatching { mediaSession?.setMetadata(null) }
    }

    /**
     * 更新播放状态。
     *
     * 进度条开启时：[position] 按真实值写入，并带 ACTION_SEEK_TO（可拖动）。
     * 进度条关闭时：位置退化为 [PlaybackState.PLAYBACK_POSITION_UNKNOWN] 且去掉
     * ACTION_SEEK_TO，SystemUI 便不会画进度条（判据与视频侧保持一致）。
     */
    fun updatePlaybackState(state: Int, position: Long = 0, speed: Float = 1.0f) {
        val progress = isProgressEnabled()
        // STOPPED/ERROR/NONE 等状态本身就不该带进度位置，统一按 UNKNOWN 处理
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
                            // 有关闭时一并去掉，免得 SystemUI 画出一条拖了没反应的条
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
