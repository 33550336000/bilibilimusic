package com.tilixibiesi.util

import com.tilixibiesi.data.PlaybackDetails
import com.tilixibiesi.data.StoragePaths

import android.os.SystemClock

import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object PlaybackStatsWriter {

    private const val TAG = "PlaybackStatsWriter"

    private const val MIN_FLUSH_INTERVAL_MS = 5_000L

    private const val BASELINE_WAIT_MS = 2_000L

    private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

    private val lock = Any()

    private val flushLock = Any()

    private val todayTotals = LinkedHashMap<String, Long>()

    private var activeDate: String = LocalDate.now().toString()

    private var todayLoaded = false

    private var loadInFlight = false

    private var loadStartedAt = 0L

    private var dirty = false

    private val pendingLoadCallbacks = ArrayList<() -> Unit>()

    private val flushScheduled = AtomicBoolean(false)

    private val lastFlushAt = AtomicLong(0L)

    private val shardCache = HashMap<String, CachedShard>()

    private class CachedShard(val lastModified: Long, val parsed: Map<String, Long>)

    private fun detailDir(): File = StoragePaths.resolveWrite(PlaybackDetails.DIR_REL)

    private fun fileOf(date: String): File = File(detailDir(), "$date.json")

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

    private fun rollDateIfNeededLocked() {
        val today = LocalDate.now().toString()
        if (today == activeDate) return

        activeDate = today
        todayTotals.clear()
        todayLoaded = false
        loadInFlight = false
        loadStartedAt = 0L
        pendingLoadCallbacks.clear()
        startLoadTodayLocked()
    }

    fun ensureTodayLoaded(onLoaded: () -> Unit) {
        synchronized(lock) {
            rollDateIfNeededLocked()
            if (todayLoaded) {

            } else {
                pendingLoadCallbacks.add(onLoaded)
                startLoadTodayLocked()
                return
            }
        }
        onLoaded()
    }

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
                .onFailure { AppLog.d(TAG, "读取今日统计基线失败: ${it.message}") }
                .getOrDefault(emptyMap())
            synchronized(lock) {
                loadInFlight = false
                if (date != activeDate) return@synchronized

                parsed.forEach { (name, seconds) ->
                    todayTotals[name] = (todayTotals[name] ?: 0L) + seconds
                }

                todayLoaded = true
                val callbacks = ArrayList(pendingLoadCallbacks)
                pendingLoadCallbacks.clear()
                callbacks.forEach { it() }
            }
        }
    }

    private fun scheduleFlush() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushAt.get() < MIN_FLUSH_INTERVAL_MS) return
        lastFlushAt.set(now)
        submitFlush()
    }

    fun flush() {
        submitFlush()
    }

    private fun submitFlush() {

        if (!flushScheduled.compareAndSet(false, true)) return
        AppExecutors.io.execute {
            try {
                flushInternal()
            } catch (e: Exception) {
                AppLog.d(TAG, "落盘失败: ${e.message}")
            } finally {
                flushScheduled.set(false)
            }
        }
    }

    fun flushBlocking() {
        try {

            loadTodayBaselineBlocking()
            flushInternal()
        } catch (e: Exception) {
            AppLog.d(TAG, "退出前落盘失败: ${e.message}")
        }
    }

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
            .onFailure { AppLog.d(TAG, "退出前补读基线失败: ${it.message}") }
            .getOrDefault(emptyMap())
        synchronized(lock) {
            if (date != activeDate || todayLoaded) return
            parsed.forEach { (name, seconds) ->
                todayTotals[name] = (todayTotals[name] ?: 0L) + seconds
            }
            todayLoaded = true
            pendingLoadCallbacks.clear()
        }
    }

    private fun flushInternal() {
        synchronized(flushLock) {
            val date: String
            val snapshot: Map<String, Long>
            synchronized(lock) {
                if (!dirty || todayTotals.isEmpty()) return

                if (!todayLoaded) {
                    val waited = SystemClock.uptimeMillis() - loadStartedAt
                    val stuck = loadStartedAt == 0L || waited > BASELINE_WAIT_MS
                    if (!stuck) return
                    if (!loadInFlight) todayLoaded = true
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

    private fun writeShard(date: String, values: Map<String, Long>): Boolean {
        val dir = detailDir()
        if (!dir.exists() && !dir.mkdirs()) {
            AppLog.d(TAG, "统计目录创建失败: ${dir.absolutePath}")
            return false
        }
        val target = fileOf(date)
        val ok = AtomicFileWriter.writeText(target, PlaybackDetails.toJson(values))
        if (ok) {
            synchronized(lock) { shardCache.remove(target.name) }
        } else {
            AppLog.d(TAG, "统计写入失败: ${target.name}")
        }
        return ok
    }

    fun todaySnapshot(): Map<String, Long> = synchronized(lock) {
        LinkedHashMap(todayTotals)
    }

    fun todayTotalSeconds(): Long = synchronized(lock) {
        var sum = 0L
        todayTotals.values.forEach { sum += it }
        sum
    }

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

            val parsed = if (date == active) {
                activeSeen = true
                memory
            } else {
                readShard(file)
            }
            if (parsed != null && parsed.isNotEmpty()) action(date, parsed)
        }

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
            .onFailure { AppLog.d(TAG, "统计分片解析失败 ${file.name}: ${it.message}") }
            .getOrNull()
            ?: return null
        synchronized(lock) { shardCache[file.name] = CachedShard(mtime, parsed) }
        return parsed
    }
}
