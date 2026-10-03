package com.tilixibiesi.util

import com.tilixibiesi.data.SpUtils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler

/**
 * 音频焦点（AudioFocus）的申请 / 释放与参数构建。
 *
 * 焦点的三种档位由设置页决定（见 [SpUtils.AUDIO_FOCUS_CALL_LEVEL] 等）：
 * 「通话等级 / 完全独占」用 GAIN，「打断模式」用 GAIN_TRANSIENT_MAY_DUCK，
 * 从而决定别的 App 出声时本应用是抢占还是让路。
 *
 * 原先这段逻辑内嵌在 [com.tilixibiesi.service.MusicPlayerService] 里，
 * 与播放器状态无关，只是借 Service 当 Context / 接收回调，因此独立出来。
 *
 * @param context 用于取系统服务与读取设置
 * @param listener 焦点变化回调（由 Service 实现）
 * @param handler 回调投递所用的 Handler（与 Service 一致，保证在主线程）
 */
class AudioFocusController(
    private val context: Context,
    private val listener: AudioManager.OnAudioFocusChangeListener,
    handler: Handler
) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val callbackHandler = handler
    private var focusRequest: AudioFocusRequest? = null

    /**
     * 按当前设置构建焦点请求。
     *
     * 切换播放模式（设置页改档位）后需要重新调用，使新的 usage / gain 生效。
     */
    fun init() {
        val mode = SpUtils.getAudioFocusMode(context)
        val usage = when (mode) {
            SpUtils.AUDIO_FOCUS_CALL_LEVEL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            SpUtils.AUDIO_FOCUS_FULL_EXCLUSIVE -> AudioAttributes.USAGE_MEDIA
            SpUtils.AUDIO_FOCUS_TRANSIENT -> AudioAttributes.USAGE_MEDIA
            else -> AudioAttributes.USAGE_MEDIA
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val focusGain = when (mode) {
            SpUtils.AUDIO_FOCUS_CALL_LEVEL -> AudioManager.AUDIOFOCUS_GAIN
            SpUtils.AUDIO_FOCUS_FULL_EXCLUSIVE -> AudioManager.AUDIOFOCUS_GAIN
            SpUtils.AUDIO_FOCUS_TRANSIENT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else -> AudioManager.AUDIOFOCUS_GAIN
        }
        focusRequest = AudioFocusRequest.Builder(focusGain)
            .setAudioAttributes(audioAttributes)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(listener, callbackHandler)
            .build()
    }

    /** 申请焦点；true 表示拿到 */
    fun request(): Boolean {
        val req = focusRequest ?: return false
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** 释放焦点（未申请过则什么都不做） */
    fun abandon() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }
}
