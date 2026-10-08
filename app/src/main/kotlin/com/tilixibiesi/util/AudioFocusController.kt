package com.tilixibiesi.util

import com.tilixibiesi.data.SpUtils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler

class AudioFocusController(
    private val context: Context,
    private val listener: AudioManager.OnAudioFocusChangeListener,
    handler: Handler
) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val callbackHandler = handler
    private var focusRequest: AudioFocusRequest? = null

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

    fun request(): Boolean {
        val req = focusRequest ?: return false
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }
}
