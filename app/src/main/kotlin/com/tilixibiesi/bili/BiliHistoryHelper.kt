package com.tilixibiesi.bili

import com.tilixibiesi.model.BiliVideo
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.util.AtomicFileWriter
import org.json.JSONArray
import org.json.JSONObject

object BiliHistoryHelper {
    private const val HISTORY_FILE_REL = "system/axeron/long/Android/Appdata/bilibili/history.json"

    private fun readJsonArray(): JSONArray {
        val file = StoragePaths.resolveRead(HISTORY_FILE_REL)
        if (!file.exists()) return JSONArray()
        val text = file.readText()
        if (text.isBlank()) return JSONArray()
        return try { JSONArray(text) } catch (e: Exception) { JSONArray() }
    }

    fun loadAll(): List<MusicBean> {
        val jsonArray = readJsonArray()
        val list = mutableListOf<MusicBean>()
        for (i in 0 until jsonArray.length()) {
            list.add(parseEntry(jsonArray.getJSONObject(i), durationAsSeconds = false))
        }
        return list
    }

    fun loadAllNormalized(): List<MusicBean> {
        val jsonArray = readJsonArray()
        val list = mutableListOf<MusicBean>()
        for (i in 0 until jsonArray.length()) {
            list.add(parseEntry(jsonArray.getJSONObject(i), durationAsSeconds = true))
        }
        return list
    }

    fun findByDisplayName(displayName: String): MusicBean? {
        val jsonArray = readJsonArray()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (DataFileUtils.getDisplayName(obj.getString("title")) == displayName) {
                return parseEntry(obj, durationAsSeconds = true)
            }
        }
        return null
    }

    /**
     * 解析一条历史记录。
     *
     * [MusicBean.musicName] 始终保存**实际名称**（历史记录里的原始标题），
     * 与缓存文件名、歌单里保存的名称保持一致；显示名称由
     * [DataFileUtils.getDisplayName] 在需要时套用重命名映射得到。
     * 绝不要把显示名称写进 `musicName`：那样重命名后缓存文件名会跟着变，
     * 同一首曲子会被重复下载。
     */
    private fun parseEntry(obj: JSONObject, durationAsSeconds: Boolean): MusicBean {
        val title = obj.getString("title")
        val rawDuration = obj.optString("duration", "0")
        return MusicBean(title, "").apply {
            isBilibili = true
            bvid = obj.getString("bvid")
            author = obj.optString("author", "")
            duration = if (durationAsSeconds) parseDurationSeconds(rawDuration) else rawDuration.toIntOrNull() ?: 0
            coverUrl = obj.optString("coverUrl", "").ifEmpty { null }
            musicUrl = ""
        }
    }

    private fun parseDurationSeconds(raw: String): Int = raw.split(":").let { parts ->
        if (parts.size == 2) {
            (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
        } else {
            parts[0].toIntOrNull() ?: 0
        }
    }

    fun addEntry(video: BiliVideo) {
        val dir = StoragePaths.resolveWrite(HISTORY_FILE_REL).parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val jsonArray = readJsonArray()
        for (i in 0 until jsonArray.length()) {
            if (jsonArray.getJSONObject(i).getString("bvid") == video.bvid) return
        }
        val newObj = JSONObject().apply {
            put("title", video.title)
            put("bvid", video.bvid)
            put("author", video.author)
            put("duration", video.duration)
            put("coverUrl", video.coverUrl)
        }
        jsonArray.put(newObj)
        AtomicFileWriter.writeText(StoragePaths.resolveWrite(HISTORY_FILE_REL), jsonArray.toString(2))
    }

    fun removeByBvid(bvid: String) {
        val jsonArray = readJsonArray()
        val newArray = JSONArray()
        var removed = false
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (obj.getString("bvid") == bvid) removed = true
            else newArray.put(obj)
        }
        if (removed) {
            AtomicFileWriter.writeText(StoragePaths.resolveWrite(HISTORY_FILE_REL), newArray.toString(2))
        }
    }
}