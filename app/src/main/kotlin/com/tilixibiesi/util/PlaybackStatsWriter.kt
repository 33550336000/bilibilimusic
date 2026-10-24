package com.tilixibiesi.util

import com.tilixibiesi.data.PlaybackDetails
import com.tilixibiesi.data.StoragePaths

import android.os.SystemClock
import android.util.Log

import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 播放时长统计的唯一读写入口。
 *
 * 设计要点：
 * 1. **单一权威值**：当天每个曲目的秒数只存在 [todayTotals] 里，只增不减。
 *    显示层直接读它，因此不会出现"写入磁盘中途计数倒退、随后一次性补上好几十秒"的跳变。
 * 2. 逐秒累加零 IO；落盘由 IO 线程节流执行（≥[MIN_FLUSH_INTERVAL_MS] 一次）。
 * 3. 历史日期走磁盘 + mtime 缓存；当天以内存为准（磁盘可能滞后）。
 */
object PlaybackStatsWriter {

    private const val TAG = "PlaybackStatsWriter"

    /** 两次异步落盘之间的最小间隔。 */
    private const val MIN_FLUSH_INTERVAL_MS = 5_000L

    /** 基线读取的等待上限，超过就先用内存值落盘，避免统计文件一直不写。 */
    private const val BASELINE_WAIT_MS = 2_000L

    private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

    private val lock = Any()

    /** 串行化落盘，避免异步 flush 与退出时的同步 flush 交叉写同一分片。 */
    private val flushLock = Any()

    /** 当天各曲目的累计秒数，唯一权威来源，只增不减。 */
    private val todayTotals = LinkedHashMap<String, Long>()

    private var activeDate: String = LocalDate.now().toString()

    /** 磁盘历史值是否已合入 [todayTotals]。 */
    private var todayLoaded = false

    private var loadInFlight = false

    /** 基线读取的起始时刻（uptimeMillis），用于超时兜底。 */
    private var loadStartedAt = 0L

    private var dirty = false

    private val flushScheduled = AtomicBoolean(false)

    private val lastFlushAt = AtomicLong(0L)

    private val shardCache = HashMap<String, CachedShard>()

    private class CachedShard(val lastModified: Long, val parsed: Map<String, Long>)

    private fun detailDir(): File = StoragePaths.resolveWrite(PlaybackDetails.DIR_REL)

    private fun fileOf(date: String): File = File(detailDir(), "$date.json")

    // ---------------------------------------------------------------- 写路径

    /** 累加播放时长（纯内存）。[seconds] 应当是真实经过的秒数，而非调用次数。 */
    fun addSeconds(musicName: String, seconds: Long) {
        if (musicName.isEmpty() || seconds <= 0L) return
        synchronized(lock) {
            rollDateIfNeededLocked()
            todayTotals[musicName] = (todayTotals[musicName] ?: 0L) + seconds
            dirty = true
            if (!todayLoaded) startLoadTodayLocked()
        }
        scheduleFlush()
    }

    /** 兼容旧调用点：加一秒。 */
    fun addOneSecond(musicName: String) = addSeconds(musicName, 1L)

    private fun rollDateIfNeededLocked() {
        val today = LocalDate.now().toString()
        if (today == activeDate) return
        // 跨天：昨天的残留内存值先清掉，今天从磁盘基线重新开始累加
        activeDate = today
        todayTotals.clear()
        todayLoaded = false
        loadInFlight = false
        loadStartedAt = 0L
        startLoadTodayLocked()
    }

    /** 把当天已落盘的历史值合入内存（异步，避免主线程读文件）。 */
    private fun startLoadTodayLocked() {
        if (loadInFlight || todayLoaded) return
        loadInFlight = true
        loadStartedAt = SystemClock.uptimeMillis()
        val date = activeDate
        AppExecutors.io.execute {
            val parsed = runCatching {
                val file = fileOf(date)
                if (file.exists()) PlaybackDetails.parse(file.readText()) else emptyMap()
            }
                .onFailure { Log.d(TAG, "读取今日统计基线失败: ${it.message}") }
                .getOrDefault(emptyMap())
            synchronized(lock) {
                loadInFlight = false
                if (date != activeDate) return@synchronized
                // 磁盘值是"本轮累加之前"的历史值，叠加即可，不会重复计数
                parsed.forEach { (name, seconds) ->
                    todayTotals[name] = (todayTotals[name] ?: 0L) + seconds
                }
                // 无论读到与否都标记完成，避免读取失败后一直卡在"未就绪"而不落盘
                todayLoaded = true
            }
        }
    }

    /** 逐秒路径：带 5s 节流。 */
    private fun scheduleFlush() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushAt.get() < MIN_FLUSH_INTERVAL_MS) return
        lastFlushAt.set(now)
        submitFlush()
    }

    /** 立即请求落盘（IO 线程）。用于暂停 / 停止等低频事件，不做节流。 */
    fun flush() {
        submitFlush()
    }

    private fun submitFlush() {
        // 已经有一个落盘任务在排队/执行就不重复提交；它结束后若仍有 dirty 会由下一次 tick 补上
        if (!flushScheduled.compareAndSet(false, true)) return
        AppExecutors.io.execute {
            try {
                flushInternal()
            } catch (e: Exception) {
                Log.d(TAG, "落盘失败: ${e.message}")
            } finally {
                flushScheduled.set(false)
            }
        }
    }

    /** 同步落盘，仅供服务 onDestroy 这类"进程可能马上没了"的场景使用。 */
    fun flushBlocking() {
        try {
            // 退出时基线可能还在异步加载，先同步补一次，否则写出去的值会缺历史部分
            loadTodayBaselineBlocking()
            flushInternal()
        } catch (e: Exception) {
            Log.d(TAG, "退出前落盘失败: ${e.message}")
        }
    }

    /** 同步把当天磁盘值合入内存，仅在退出路径调用。 */
    private fun loadTodayBaselineBlocking() {
        val date: String
        synchronized(lock) {
            if (todayLoaded) return
            date = activeDate
        }
        val parsed = runCatching {
            val file = fileOf(date)
            if (file.exists()) PlaybackDetails.parse(file.readText()) else emptyMap()
        }
            .onFailure { Log.d(TAG, "退出前补读基线失败: ${it.message}") }
            .getOrDefault(emptyMap())
        synchronized(lock) {
            if (date != activeDate || todayLoaded) return
            parsed.forEach { (name, seconds) ->
                todayTotals[name] = (todayTotals[name] ?: 0L) + seconds
            }
            todayLoaded = true
        }
    }

    private fun flushInternal() {
        synchronized(flushLock) {
            val date: String
            val snapshot: Map<String, Long>
            synchronized(lock) {
                if (!dirty || todayTotals.isEmpty()) return
                // 基线未就绪时落盘会覆盖掉磁盘上的历史数据，先等一会儿；
                // 但读取若卡住或失败，不能无限跳过（那会表现为"统计文件一直不写"），
                // 超过宽限期就先用内存值写出去。
                if (!todayLoaded) {
                    val waited = SystemClock.uptimeMillis() - loadStartedAt
                    val stuck = loadStartedAt == 0L || waited > BASELINE_WAIT_MS
                    if (!stuck) return
                    if (!loadInFlight) todayLoaded = true   // 读取已经失败/结束，后续不再等待
                }
                date = activeDate
                snapshot = LinkedHashMap(todayTotals)
                dirty = false
            }
            if (!writeShard(date, snapshot)) {
                synchronized(lock) { dirty = true }
            }
        }
    }

    /** 用内存值整体覆盖当天分片。 */
    private fun writeShard(date: String, values: Map<String, Long>): Boolean {
        val dir = detailDir()
        if (!dir.exists() && !dir.mkdirs()) {
            Log.d(TAG, "统计目录创建失败: ${dir.absolutePath}")
            return false
        }
        val target = fileOf(date)
        val ok = AtomicFileWriter.writeText(target, PlaybackDetails.toJson(values))
        if (ok) {
            synchronized(lock) { shardCache.remove(target.name) }
        } else {
            Log.d(TAG, "统计写入失败: ${target.name}")
        }
        return ok
    }

    // ---------------------------------------------------------------- 读路径

    /** 今天各曲目的已播放秒数。纯内存读取，可在逐秒 tick 里安全调用。 */
    fun todaySnapshot(): Map<String, Long> = synchronized(lock) {
        LinkedHashMap(todayTotals)
    }

    fun todayTotalSeconds(): Long = synchronized(lock) {
        var sum = 0L
        todayTotals.values.forEach { sum += it }
        sum
    }

    /** 全部曲目按日期分组的统计，需要读磁盘，请在 IO 线程调用。 */
    fun loadAll(): Map<String, Map<String, Long>> {
        val result = LinkedHashMap<String, MutableMap<String, Long>>()
        forEachShard { date, parsed ->
            parsed.forEach { (name, seconds) ->
                val byDate = result.getOrPut(name) { LinkedHashMap() }
                byDate[date] = (byDate[date] ?: 0L) + seconds
            }
        }
        return result
    }

    /** 单曲的逐日统计，需要读磁盘，请在 IO 线程调用。 */
    fun loadSongDetails(rawName: String): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        forEachShard { date, parsed ->
            parsed[rawName]?.let { result[date] = (result[date] ?: 0L) + it }
        }
        return result
    }

    private fun forEachShard(action: (date: String, parsed: Map<String, Long>) -> Unit) {
        val active: String
        val memory: Map<String, Long>
        synchronized(lock) {
            active = activeDate
            memory = LinkedHashMap(todayTotals)
        }

        val seen = HashSet<String>()
        var activeSeen = false
        detailDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { file ->
            val date = dateOf(file.name) ?: return@forEach
            seen.add(file.name)
            // 当天以内存为准：磁盘可能滞后，用磁盘值会显示成偏小的旧值
            val parsed = if (date == active) {
                activeSeen = true
                memory
            } else {
                readShard(file)
            }
            if (parsed != null && parsed.isNotEmpty()) action(date, parsed)
        }
        // 今天已有数据但还没生成文件
        if (!activeSeen && memory.isNotEmpty()) action(active, memory)
        synchronized(lock) { shardCache.keys.retainAll(seen) }
    }

    private fun dateOf(fileName: String): String? {
        val date = fileName.removeSuffix(".json")
        return date.takeIf { DATE_PATTERN.matches(it) }
    }

    private fun readShard(file: File): Map<String, Long>? {
        val mtime = file.lastModified()
        synchronized(lock) {
            shardCache[file.name]?.let { if (it.lastModified == mtime) return it.parsed }
        }
        val parsed = runCatching { PlaybackDetails.parse(file.readText()) }
            .onFailure { Log.d(TAG, "统计分片解析失败 ${file.name}: ${it.message}") }
            .getOrNull()
            ?: return null
        synchronized(lock) { shardCache[file.name] = CachedShard(mtime, parsed) }
        return parsed
    }
}
