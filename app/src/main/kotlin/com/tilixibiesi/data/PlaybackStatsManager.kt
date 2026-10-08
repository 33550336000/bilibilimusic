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
        // 明细文件的键是**实际名称**（见 MusicPlayerService.currentPlayingRawName），
        // 因此这里必须按实际名称查表；显示名会随重命名变化，查不到任何记录。
        val name = MusicPlayerService.currentPlayingRawName
            ?: MusicPlayerService.currentPlayingName
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
        // 「今日时长」只取决于"有没有当前曲目"，**不看播放状态**：
        // 暂停时这一行仍要显示——用户想知道的正是"这首歌我今天听了多久"，
        // 暂停恰恰是最想确认它的时刻。若这里判 `!isPlaying` 就隐藏，
        // 暂停后数字会消失；而且服务重启恢复"上一首"时 isPlaying 本就是 false，
        // 会让"运行中暂停"与"重启后暂停"表现不一致。
        if (!enabled || name == null) {
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
     * 历史分片的解析结果缓存：文件名 -> (最后修改时间, 解析结果)。
     *
     * 为什么可以缓存历史、但绝不缓存今天：
     * [PlaybackStatsWriter.addOneSecond] 每次都以「当天日期」为文件名写入，
     * 也就是说**只有今天那一片在变**，昨天及更早的分片一经写入就不会再被改写。
     * 于是每次加载只需实读今天这一片，历史分片按 (文件名, mtime) 命中缓存即可，
     * 既保证数字实时，又不必把用过的每一天都重新解析一遍。
     */
    private class CachedShard(
        val lastModified: Long,
        val parsed: Map<String, Map<String, Long>>
    )

    private val shardCache = HashMap<String, CachedShard>()
    private val shardCacheLock = Any()

    /**
     * 汇总全量播放明细：歌曲名 -> 日期 -> 秒。
     *
     * 数据源只有 `details/<日期>.json` 分片（按天一个文件）。
     * 单个分片解析失败会跳过并继续，避免一天的数据损坏导致整个统计不可用。
     *
     * 历史分片走缓存、今天分片永远实读（见 [shardCache]），因此重复调用
     * 的 IO 成本与「用过多少天」无关，只与今天这一片有关。
     */
    fun loadPlaybackDetails(): Map<String, Map<String, Long>> {
        val result = mutableMapOf<String, MutableMap<String, Long>>()
        forEachShard { parsed ->
            parsed.forEach { (name, dateMap) ->
                val m = result.getOrPut(name) { mutableMapOf() }
                dateMap.forEach { (date, seconds) ->
                    m[date] = (m[date] ?: 0L) + seconds
                }
            }
        }
        return result
    }

    /**
     * 只取**某一首歌**的每日明细：日期 -> 秒。
     *
     * 与 [loadPlaybackDetails] 读的是同一批分片（缓存策略也相同），
     * 区别只是不再为其余歌曲建表。详情弹窗点进某首歌时用它，
     * 避免为了一首歌的明细去汇总整份数据。
     */
    fun loadSongDetails(rawName: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        forEachShard { parsed ->
            parsed[rawName]?.forEach { (date, seconds) ->
                result[date] = (result[date] ?: 0L) + seconds
            }
        }
        return result
    }

    /**
     * 遍历全部分片并把解析结果交给 [action]。
     *
     * 分片集合以本次列目录结果为准，顺带清掉缓存里已消失的文件，
     * 避免用户清空数据后缓存里还留着幽灵记录。
     */
    private fun forEachShard(action: (Map<String, Map<String, Long>>) -> Unit) {
        synchronized(shardCacheLock) {
            val dir = StoragePaths.resolveRead(PlaybackDetails.DIR_REL)
            val todayName = "${dateFormat.format(Date())}.json"
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?: return
            val seen = HashSet<String>(files.size)
            for (file in files) {
                seen.add(file.name)
                readShard(file, todayName)?.let(action)
            }
            shardCache.keys.retainAll(seen)
        }
    }

    /**
     * 读一个分片；今天那一片永远实读，历史分片按 mtime 命中缓存。
     * 解析失败返回 null（跳过该文件，不影响其余日期）。
     */
    private fun readShard(file: File, todayName: String): Map<String, Map<String, Long>>? {
        // 今天这片每秒都在被重写，缓存它必然导致显示滞后
        if (file.name == todayName) {
            return runCatching { PlaybackDetails.parse(file.readText()) }.getOrNull()
        }
        val mtime = file.lastModified()
        shardCache[file.name]?.let { if (it.lastModified == mtime) return it.parsed }
        val parsed = runCatching { PlaybackDetails.parse(file.readText()) }.getOrNull() ?: return null
        shardCache[file.name] = CachedShard(mtime, parsed)
        return parsed
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