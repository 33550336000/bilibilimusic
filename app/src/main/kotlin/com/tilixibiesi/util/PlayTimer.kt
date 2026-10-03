package com.tilixibiesi.util

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 「定时停止播放」计时器。
 *
 * 原先这段状态与逻辑内嵌在歌曲页里（3 个方法 + 4 个字段），
 * 但它与页面 UI 无关，只是「到点回调一次」，因此独立成类：
 * 页面只管弹窗与按钮，计时职责全部在这里。
 *
 * 线程：所有方法与回调都在主线程。超时动作通过 [onTimeout] 交给调用方
 * （歌曲页用它来停止播放并刷新按钮），计时器本身不依赖播放器。
 */
class PlayTimer(
    private val context: Context,
    /** 倒计时结束时的动作（停止播放 + 刷新 UI 等） */
    private val onTimeout: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var endTimeMs = 0L
    private var running = false

    /** 是否正在计时（供 UI 判断，例如按钮文案） */
    val isRunning: Boolean get() = running

    /**
     * 启动倒计时（会先取消上一次）。
     *
     * @param seconds 总秒数；调用方需保证为正数
     */
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

    /** 取消倒计时；若原本在计时则提示一次（已在计时以外调用时静默）。 */
    fun cancel() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        if (running) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.timer_cancel))
            running = false
        }
    }

    /** 把秒数格式化为「x 小时 y 分 z 秒」（按当前语言） */
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
