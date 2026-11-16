package com.tilixibiesi.util

import android.content.Context
import android.media.MediaPlayer
import android.widget.VideoView

class BackgroundVideoView(context: Context) : VideoView(context) {

    var sourcePath: String = ""
        private set

    var isActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyState()
        }

    private var prepared = false
    private var player: MediaPlayer? = null
    private var released = false

    fun bind(path: String) {
        sourcePath = path
        setOnPreparedListener { mp ->
            if (released) return@setOnPreparedListener
            prepared = true
            player = mp
            mp.isLooping = true
            applyState()
        }
        setOnErrorListener { _, _, _ -> true }
        setVideoPath(path)
    }

    private fun applyState() {
        if (released) return
        val mp = player
        if (isActive) {
            if (prepared && !isPlaying) runCatching { start() }
            runCatching { mp?.setVolume(1f, 1f) }
        } else {
            if (prepared && isPlaying) runCatching { pause() }
            runCatching { mp?.setVolume(0f, 0f) }
        }
    }

    fun release() {
        released = true
        runCatching { player?.setVolume(0f, 0f) }
        runCatching { stopPlayback() }
        player = null
        prepared = false
    }
}
