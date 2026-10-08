package com.tilixibiesi.util

object VideoPlaybackController {

    interface Target {
        fun isPlaying(): Boolean
        fun pause()
        fun resume()
        fun switchPrevious() {}
        fun switchNext() {}
        fun getPosition(): Int = 0
        fun getDuration(): Int = 0
        fun seekTo(positionMs: Int) {}
    }

    @Volatile
    private var target: Target? = null

    @Synchronized
    fun attach(t: Target) {
        target = t
    }

    @Synchronized
    fun detach(t: Target) {
        if (target === t) target = null
    }

    fun isAttached(): Boolean = target != null

    fun pause() {
        runCatching { target?.pause() }
    }

    fun resume() {
        runCatching { target?.resume() }
    }

    fun isPlaying(): Boolean = runCatching { target?.isPlaying() == true }.getOrDefault(false)

    fun switchPrevious() {
        runCatching { target?.switchPrevious() }
    }

    fun switchNext() {
        runCatching { target?.switchNext() }
    }

    fun getPosition(): Int = runCatching { target?.getPosition() ?: 0 }.getOrDefault(0)

    fun getDuration(): Int = runCatching { target?.getDuration() ?: 0 }.getOrDefault(0)

    fun seekTo(positionMs: Int) {
        runCatching { target?.seekTo(positionMs) }
    }
}
