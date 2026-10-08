package com.tilixibiesi.util

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils

import android.content.Context
import android.os.Handler
import android.os.Looper

class PlayTimer(
    private val context: Context,
    private val onTimeout: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var endTimeMs = 0L
    private var running = false

    val isRunning: Boolean get() = running

    fun start(seconds: Int) {
        cancel()
        running = true
        endTimeMs = System.currentTimeMillis() + seconds * 1000L
        ToastUtils.show(
            context,
            LanguageUtils.getString(context, R.string.timer_start, formatDuration(seconds))
        )
        runnable = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() >= endTimeMs) {
                    onTimeout()
                    ToastUtils.show(context, LanguageUtils.getString(context, R.string.timer_end))
                    cancel()
                } else {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.post(runnable!!)
    }

    fun cancel() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        if (running) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.timer_cancel))
            running = false
        }
    }

    private fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return buildString {
            if (h > 0) append(LanguageUtils.getString(context, R.string.duration_hours_format, h))
            if (m > 0) append(LanguageUtils.getString(context, R.string.duration_minutes_format, m))
            if (s > 0 || isEmpty()) append(LanguageUtils.getString(context, R.string.duration_seconds_format, s))
        }
    }
}
