package com.tilixibiesi.util

import com.tilixibiesi.data.PlaybackDetails

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlaybackStatsWriter(private val detailDirPath: String) {

    private companion object {
        const val SYNC_EVERY_WRITES = 10
    }

    @Volatile
    private var cachedDate: String? = null
    @Volatile
    private var cachedMap: MutableMap<String, MutableMap<String, Long>>? = null

    private var writesSinceSync = 0

    @Synchronized
    fun addOneSecond(musicName: String) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        savePlaybackDuration(musicName, today, 1L)
    }

    private fun fileOf(date: String): File = File(detailDirPath, "$date.json")

    private fun load(date: String): MutableMap<String, MutableMap<String, Long>> {
        cachedMap?.let { if (cachedDate == date) return it }
        val file = fileOf(date)
        val result: MutableMap<String, MutableMap<String, Long>> = try {
            if (file.exists()) PlaybackDetails.parse(file.readText()) else mutableMapOf()
        } catch (e: Exception) {
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
                target.writeText(jsonText)
                tmp.delete()
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
        }
    }

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
