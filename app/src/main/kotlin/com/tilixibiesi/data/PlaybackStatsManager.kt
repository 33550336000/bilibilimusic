package com.tilixibiesi.data

import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.R
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 今日 / 累计播放时长统计与展示。
 *
 * 原职责取自 `AppManager`（播放时长部分）：
 * - 读取 `details.json` 播放明细（按 歌曲 → 日期 → 秒 组织）
 * - 通过 TextView 弱引用实时刷新「今日时长 / 累计时长」展示
 * - 每秒定时刷新
 */
object PlaybackStatsManager {
    private val handler = Handler(Looper.getMainLooper())
    private var updaterRunnable: Runnable? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    private var tvToday: WeakReference<TextView>? = null
    private var tvTotal: WeakReference<TextView>? = null

    fun initViews(todayView: TextView, totalView: TextView) {
        tvToday = WeakReference(todayView)
        tvTotal = WeakReference(totalView)
    }

    fun releaseViews() {
        tvToday?.clear()
        tvTotal?.clear()
        tvToday = null
        tvTotal = null
    }

    fun startUpdater() {
        stopUpdater()
        updaterRunnable = object : Runnable {
            override fun run() {
                updateDisplay()
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(updaterRunnable!!)
    }

    fun stopUpdater() {
        updaterRunnable?.let { handler.removeCallbacks(it) }
        updaterRunnable = null
    }

    fun refresh() {
        updateDisplay()
    }

    private fun updateDisplay() {
        val ctx = tvToday?.get()?.context ?: tvTotal?.get()?.context ?: return
        val enabled = SpUtils.isShowTodayDurationEnabled(ctx)
        val name = MusicPlayerService.currentPlayingName
        val playing = MusicPlayerService.isPlaying
        val todayView = tvToday?.get()
        val totalView = tvTotal?.get()
        // 关闭开关时完全不读盘（与改动前一致，零 IO）。
        // 开启时只读今日那一个分片，两个统计共用这一份数据。
        val todayDurations = if (!enabled) null else loadTodayDurations()

        if (enabled) {
            val totalSeconds = todayDurations?.values?.sum() ?: 0L
            totalView?.apply {
                text = LanguageUtils.getString(ctx, R.string.format_total_duration, formatDuration(totalSeconds, ctx))
                visibility = View.VISIBLE
                try {
                    setTextColor(Color.parseColor(SpUtils.getFontColor(ctx)))
                } catch (_: Exception) {
                    setTextColor(Color.BLACK)
                }
            }
        } else {
            totalView?.visibility = View.GONE
        }
        if (!enabled || name == null || !playing) {
            todayView?.visibility = View.GONE
        } else {
            val seconds = try {
                todayDurations?.get(name) ?: 0L
            } catch (_: Exception) {
                0L
            }
            todayView?.apply {
                text = LanguageUtils.getString(ctx, R.string.format_today_duration, formatDuration(seconds, ctx))
                visibility = View.VISIBLE
                try {
                    setTextColor(Color.parseColor(SpUtils.getFontColor(ctx)))
                } catch (_: Exception) {
                    setTextColor(Color.BLACK)
                }
            }
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

    /**
     * 汇总全量播放明细。
     *
     * 数据源只有 `details/<日期>.json` 分片（按天一个文件）。
     * 单个分片解析失败会跳过并继续，避免一天的数据损坏导致整个统计不可用。
     */
    fun loadPlaybackDetails(): Map<String, Map<String, Long>> {
        val result = mutableMapOf<String, MutableMap<String, Long>>()

        fun mergeParsed(parsed: Map<String, Map<String, Long>>) {
            parsed.forEach { (name, dateMap) ->
                val m = result.getOrPut(name) { mutableMapOf() }
                dateMap.forEach { (date, seconds) ->
                    m[date] = (m[date] ?: 0L) + seconds
                }
            }
        }

        // 分片目录：单个分片损坏只跳过该文件，不影响其余日期
        val dir = StoragePaths.resolveRead(PlaybackDetails.DIR_REL)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        if (files != null) {
            for (file in files) {
                runCatching { mergeParsed(PlaybackDetails.parse(file.readText())) }
            }
        }
        return result
    }

    /**
     * 只读取「今天」的播放时长明细：歌曲名 -> 秒数。
     *
     * 为什么不用 [loadPlaybackDetails]：
     * 它要把旧单文件 + 分片目录下**全部** .json 都读一遍逐个解析，
     * 而"今日时长"只需要今天这一个分片（文件名为 `<日期>.json`）。
     * 用过的天数越多，它读得越多，但我们要的数据量根本没变。
     * 改为直读今日分片后，IO 从"读 N 个文件"降到"最多读 1 个文件"。
     *
     * 不做缓存：服务侧每秒累加一秒（addOneSecond），时长每秒都在变，
     * 任何缓存都会让显示滞后/跳变（此前 3s 缓存就是这个问题），
     * 而 updateDisplay 由 updater 每秒调用，必须保持每秒刷新。
     */
    private fun loadTodayDurations(): Map<String, Long> {
        val today = dateFormat.format(Date())
        val result = mutableMapOf<String, Long>()

        runCatching {
            val shard = File(StoragePaths.resolveRead(PlaybackDetails.DIR_REL), "$today.json")
            if (shard.exists()) {
                PlaybackDetails.parse(shard.readText()).forEach { (name, dateMap) ->
                    dateMap[today]?.let { result[name] = it }
                }
            }
        }

        return result
    }
}