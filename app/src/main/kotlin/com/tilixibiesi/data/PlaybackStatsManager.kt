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
import com.tilixibiesi.util.AppLog
import android.view.View
import android.widget.TextView
import java.lang.ref.WeakReference

object PlaybackStatsManager {

    private const val TAG = "PlaybackStatsManager"

    private const val TICK_MS = 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var updaterRunnable: Runnable? = null

    private var tvToday: WeakReference<TextView>? = null
    private var tvTotal: WeakReference<TextView>? = null

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

            return
        }

        var phase = android.os.SystemClock.uptimeMillis()
        updaterRunnable = object : Runnable {
            override fun run() {
                updateDisplay()

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

        updaterRunnable?.let { handler.removeCallbacks(it) }
        updaterRunnable = null

        lastTodayText = null
        lastTotalText = null
    }

    fun refresh() {

        PlaybackStatsWriter.ensureTodayLoaded { handler.post { updateDisplay() } }
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

    fun loadPlaybackDetailsAsync(onResult: (Map<String, Map<String, Long>>) -> Unit) {
        AppExecutors.io.execute {
            val result = runCatching { PlaybackStatsWriter.loadAll() }
                .onFailure { AppLog.d(TAG, "读取播放明细失败: ${it.message}") }
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
                .onFailure { AppLog.d(TAG, "读取单曲明细失败: ${it.message}") }
                .getOrDefault(emptyMap())
            handler.post { onResult(result) }
        }
    }
}
