package com.tilixibiesi.data

import com.tilixibiesi.util.AtomicFileWriter
import org.json.JSONArray

/**
 * 搜索历史：<存储根>/system/axeron/long/Android/Appdata/history/search_history.json
 *
 * 读取、新增、删除都直接落盘，不再走 SharedPreferences。
 */
object SearchHistoryStore {

    private const val HISTORY_FILE_REL =
        "system/axeron/long/Android/Appdata/history/search_history.json"

    private const val LIMIT = 20

    private fun readArray(): JSONArray {
        val file = StoragePaths.resolveRead(HISTORY_FILE_REL)
        if (!file.exists()) return JSONArray()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return JSONArray()
        }
        if (text.isBlank()) return JSONArray()
        return try {
            JSONArray(text)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeArray(array: JSONArray) {
        AtomicFileWriter.writeText(StoragePaths.resolveWrite(HISTORY_FILE_REL), array.toString(2))
    }

    fun load(): List<String> {
        val array = readArray()
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val word = array.optString(i, "").trim()
            if (word.isNotEmpty()) list.add(word)
        }
        return list
    }

    fun add(keyword: String) {
        val word = keyword.trim()
        if (word.isEmpty()) return
        val array = JSONArray()
        array.put(word)
        load().filter { it != word }.take(LIMIT - 1).forEach { array.put(it) }
        writeArray(array)
    }

    fun remove(keyword: String) {
        val word = keyword.trim()
        if (word.isEmpty()) return
        val array = JSONArray()
        load().filter { it != word }.forEach { array.put(it) }
        writeArray(array)
    }
}
