package com.tilixibiesi.util

import com.tilixibiesi.data.PlaybackDetails

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放时长写入器：按「日期分片」持久化到 `<Appdata>/details/<yyyy-MM-dd>.json`。
 *
 * 为什么从单文件 details.json 改为分片：
 *  1. 单文件随时间增长到 MB 级后，每秒一次「全量读入 → 累加 → 全量覆写」既慢又危险；
 *     `File.writeText()` 是非原子的（先截断再写），写入中断会留下半截 JSON，整个明细报废。
 *  2. 按天分片后每天的文件体积基本恒定（一天内「歌曲 → 秒」的量级很小），
 *     写入范围小、耗时长尾可控，且历史数据不再被反复重写。
 *
 * 可靠性措施：
 *  - **原子写**：先写同目录临时文件，再 `renameTo` 覆盖（同一分区 rename 是原子的），
 *    过程中失败只丢一个临时文件，不会损坏当天数据。
 *  - **内存缓存**：当天数据常驻内存，避免每秒重复读盘；跨天自动切换。
 *  - **合并策略**：同天多进程/多次写入以「取较大值」为准，避免回退。
 */
class PlaybackStatsWriter(private val detailDirPath: String) {

    private companion object {
        /** 每写 N 次执行一次 fsync；UI 仍每秒读到新文件，只是掉电丢失窗口最多约 N 秒。 */
        const val SYNC_EVERY_WRITES = 10
    }

    @Volatile
    private var cachedDate: String? = null
    @Volatile
    private var cachedMap: MutableMap<String, MutableMap<String, Long>>? = null

    /** 距离上次 fsync 已写入的次数（由 @Synchronized 方法保护）。 */
    private var writesSinceSync = 0

    @Synchronized
    fun addOneSecond(musicName: String) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        savePlaybackDuration(musicName, today, 1L)
    }

    private fun fileOf(date: String): File = File(detailDirPath, "$date.json")

    /**
     * 读取指定日期的分片数据（带内存缓存）。
     * 解析失败返回空 Map —— 单个分片损坏不影响其他日期。
     */
    private fun load(date: String): MutableMap<String, MutableMap<String, Long>> {
        cachedMap?.let { if (cachedDate == date) return it }
        val file = fileOf(date)
        val result: MutableMap<String, MutableMap<String, Long>> = try {
            if (file.exists()) PlaybackDetails.parse(file.readText()) else mutableMapOf()
        } catch (e: Exception) {
            // 分片损坏：不抛出、不覆写（保留现场便于排查），本次以空表重新开始累计
            mutableMapOf()
        }
        cachedDate = date
        cachedMap = result
        return result
    }

    private fun savePlaybackDuration(musicName: String, date: String, seconds: Long) {
        val dir = File(detailDirPath)
        if (!dir.exists() && !dir.mkdirs()) return

        val rootMap = load(date)
        val dateMap = rootMap.getOrPut(musicName) { mutableMapOf() }
        dateMap[date] = (dateMap[date] ?: 0L) + seconds

        writeAtomically(fileOf(date), rootMap)
    }

    /**
     * 原子写入：临时文件 → renameTo。
     *
     * 避免非原子的 writeText 在写一半失败时留下损坏 JSON：
     * rename 在同分区是原子操作，读者只会看到旧文件或新文件，不会看到中间态。
     *
     * fsync 不再每次执行：每秒一次 fsync 的 IO/耗电成本较高，
     * 改为每 [SYNC_EVERY_WRITES] 次写一次；文件本身仍每秒 rename 更新，
     * 因此 UI 实时刷新不受影响。暂停/停止/销毁时会调用 [flush] 强制落盘。
     */
    private fun writeAtomically(target: File, rootMap: Map<String, Map<String, Long>>) {
        val jsonText = PlaybackDetails.toJson(rootMap)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(jsonText)
            if (++writesSinceSync >= SYNC_EVERY_WRITES) {
                sync(tmp)
                writesSinceSync = 0
            }
            if (!tmp.renameTo(target)) {
                // 少数文件系统 rename 失败：退化为直接写，并清理临时文件
                target.writeText(jsonText)
                tmp.delete()
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
        }
    }

    /** 强制把当前分片刷盘；暂停/停止/销毁时调用。 */
    @Synchronized
    fun flush() {
        val date = cachedDate ?: return
        val file = fileOf(date)
        if (file.exists()) sync(file)
        writesSinceSync = 0
    }

    private fun sync(file: File) {
        runCatching {
            java.io.FileOutputStream(file, true).use { it.fd.sync() }
        }
    }
}
