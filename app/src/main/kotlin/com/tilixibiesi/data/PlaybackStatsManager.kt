package com.tilixibiesi.data

import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.R
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.FontUtils
import com.tilixibiesi.util.PlaybackStatsWriter

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * 播放时长统计的显示层。
 *
 * 数据读写全部委托给 [PlaybackStatsWriter]：
 * - 逐秒刷新的“今日时长”只读内存快照，不再每秒读文件；
 * - 按日期分组的明细查询改到 IO 线程，结果回主线程弹窗。
 */
object PlaybackStatsManager {

    private const val TAG = "PlaybackStatsManager"

    private const val TICK_MS = 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var updaterRunnable: Runnable? = null

    private var tvToday: WeakReference<TextView>? = null
    private var tvTotal: WeakReference<TextView>? = null

    /** 最近一次渲染的文本，避免每秒无谓地重设 TextView（会触发 measure/layout）。 */
    private var lastTodayText: String? = null
    private var lastTotalText: String? = null

    fun initViews(todayView: TextView, totalView: TextView) {
        tvToday = WeakReference(todayView)
        tvTotal = WeakReference(totalView)
        lastTodayText = null
        lastTotalText = null
    }

    fun releaseViews() {
        tvToday?.clear()
        tvTotal?.clear()
        tvToday = null
        tvTotal = null
        lastTodayText = null
        lastTotalText = null
    }

    fun startUpdater() {
        if (updaterRunnable != null) {
            // 已经在跑就不打断节奏，避免每秒重建 Runnable
            return
        }
        // 必须用 uptimeMillis()：Handler 的所有定时 API 都以它为基准
        var phase = android.os.SystemClock.uptimeMillis()
        updaterRunnable = object : Runnable {
            override fun run() {
                updateDisplay()
                // 固定相位调度，避免与 Service 的统计 tick 产生系统性错拍
                phase += TICK_MS
                val delayMs = phase - android.os.SystemClock.uptimeMillis()
                if (delayMs > 0L) {
                    handler.postAtTime(this, phase)
                } else {
                    phase = android.os.SystemClock.uptimeMillis() + TICK_MS
                    handler.postAtTime(this, phase)
                }
            }
        }
        handler.post(updaterRunnable!!)
    }

    fun stopUpdater() {
        // removeCallbacks 能移除 postAtTime 投递的任务（同一个 callback token），
        // 这里只移除自己的任务，不要用 removeCallbacksAndMessages(null) 误伤同 handler 的其他任务
        updaterRunnable?.let { handler.removeCallbacks(it) }
        updaterRunnable = null
        // 重置缓存，下次显示时强制刷新一次，避免沿用停止前的文本判重结果
        lastTodayText = null
        lastTotalText = null
    }

    fun refresh() {
        updateDisplay()
    }

    private fun updateDisplay() {
        val ctx = tvToday?.get()?.context ?: tvTotal?.get()?.context ?: return
        val enabled = SpUtils.isShowTodayDurationEnabled(ctx)
        val name = MusicPlayerService.currentPlayingRawName
            ?: MusicPlayerService.currentPlayingName
        val todayView = tvToday?.get()
        val totalView = tvTotal?.get()

        if (!enabled) {
            totalView?.visibility = View.GONE
            todayView?.visibility = View.GONE
            lastTodayText = null
            lastTotalText = null
            return
        }

        // 纯内存读取：本进程已累计的秒数 + 今天已落盘部分（由 writer 合并）
        val snapshot = PlaybackStatsWriter.todaySnapshot()
        val totalSeconds = PlaybackStatsWriter.todayTotalSeconds()

        val totalText = LanguageUtils.getString(
            ctx, R.string.format_total_duration, formatDuration(totalSeconds, ctx)
        )
        if (totalView != null && (totalText != lastTotalText || totalView.visibility != View.VISIBLE)) {
            totalView.text = totalText
            totalView.visibility = View.VISIBLE
            totalView.setTextColor(FontUtils.color(ctx, Color.BLACK))
            lastTotalText = totalText
        }

        if (name == null) {
            todayView?.visibility = View.GONE
            lastTodayText = null
            return
        }
        val seconds = snapshot[name] ?: 0L
        val todayText = LanguageUtils.getString(
            ctx, R.string.format_today_duration, formatDuration(seconds, ctx)
        )
        if (todayView != null && (todayText != lastTodayText || todayView.visibility != View.VISIBLE)) {
            todayView.text = todayText
            todayView.visibility = View.VISIBLE
            todayView.setTextColor(FontUtils.color(ctx, Color.BLACK))
            lastTodayText = todayText
        }
    }

    fun formatDuration(seconds: Long, context: Context): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) {
            parts.add(LanguageUtils.getString(context, R.string.duration_hours_format, h.toInt()))
        }
        if (m > 0) {
            parts.add(LanguageUtils.getString(context, R.string.duration_minutes_format, m.toInt()))
        }
        parts.add(LanguageUtils.getString(context, R.string.duration_seconds_format, s.toInt()))
        return parts.joinToString("")
    }

    // ------------------------------------------------------------ 明细查询（异步）

    fun loadPlaybackDetailsAsync(onResult: (Map<String, Map<String, Long>>) -> Unit) {
        AppExecutors.io.execute {
            val result = runCatching { PlaybackStatsWriter.loadAll() }
                .onFailure { Log.d(TAG, "读取播放明细失败: ${it.message}") }
                .getOrDefault(emptyMap())
            handler.post { onResult(result) }
        }
    }

    fun loadSongDetailsAsync(
        rawName: String,
        onResult: (Map<String, Long>) -> Unit
    ) {
        AppExecutors.io.execute {
            val result = runCatching { PlaybackStatsWriter.loadSongDetails(rawName) }
                .onFailure { Log.d(TAG, "读取单曲明细失败: ${it.message}") }
                .getOrDefault(emptyMap())
            handler.post { onResult(result) }
        }
    }
}
