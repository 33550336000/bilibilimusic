package com.tilixibiesi.bili

import com.tilixibiesi.model.BiliVideo
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.StoragePaths
import org.json.JSONArray
import org.json.JSONObject

/**
 * B 站历史文件（Appdata/bilibili/history.json）的统一读写助手。
 * 集中管理历史记录的 读取 / 追加 / 按 bvid 删除 / 按显示名查找，
 * 供 MainActivity / SearchActivity / MusicPlayerService 复用，避免三处重复文件 IO。
 */
object BiliHistoryHelper {
    private const val HISTORY_FILE_REL = "system/axeron/long/Android/Appdata/bilibili/history.json"

    private fun readJsonArray(): JSONArray {
        val file = StoragePaths.resolveRead(HISTORY_FILE_REL)
        if (!file.exists()) return JSONArray()
        val text = file.readText()
        if (text.isBlank()) return JSONArray()
        return try { JSONArray(text) } catch (e: Exception) { JSONArray() }
    }

    /** 读取全部历史（musicName 保留原始标题，duration 取整数值） */
    fun loadAll(): List<MusicBean> {
        val jsonArray = readJsonArray()
        val list = mutableListOf<MusicBean>()
        for (i in 0 until jsonArray.length()) {
            list.add(parseEntry(jsonArray.getJSONObject(i), normalizeName = false, durationAsSeconds = false))
        }
        return list
    }

    /** 读取全部历史（musicName 归一化为显示名，duration 解析 MM:SS 为秒） */
    fun loadAllNormalized(): List<MusicBean> {
        val jsonArray = readJsonArray()
        val list = mutableListOf<MusicBean>()
        for (i in 0 until jsonArray.length()) {
            list.add(parseEntry(jsonArray.getJSONObject(i), normalizeName = true, durationAsSeconds = true))
        }
        return list
    }

    /** 按显示名查找（musicName 归一化为显示名，duration 解析 MM:SS 为秒） */
    fun findByDisplayName(displayName: String): MusicBean? {
        val jsonArray = readJsonArray()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (DataFileUtils.getDisplayName(obj.getString("title")) == displayName) {
                return parseEntry(obj, normalizeName = true, durationAsSeconds = true)
            }
        }
        return null
    }

    /**
     * 把一条历史 JSON 转成 MusicBean。
     *
     * @param normalizeName true 时使用 DataFileUtils 的显示名（重命名/去扩展名），
     *                      false 时保留原始标题
     */
    private fun parseEntry(obj: JSONObject, normalizeName: Boolean, durationAsSeconds: Boolean): MusicBean {
        val title = obj.getString("title")
        val rawDuration = obj.optString("duration", "0")
        return MusicBean(title, "").apply {
            isBilibili = true
            bvid = obj.getString("bvid")
            author = obj.optString("author", "")
            duration = if (durationAsSeconds) parseDurationSeconds(rawDuration) else rawDuration.toIntOrNull() ?: 0
            musicUrl = ""
            if (normalizeName) {
                this.musicName = DataFileUtils.getDisplayName(title)
            }
        }
    }

    /** 把 MM:SS 或纯秒数字符串解析成秒；非法值按 0 处理。 */
    private fun parseDurationSeconds(raw: String): Int = raw.split(":").let { parts ->
        if (parts.size == 2) {
            (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
        } else {
            parts[0].toIntOrNull() ?: 0
        }
    }

    /** 追加一条记录（按 bvid 去重，不重复添加） */
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
        StoragePaths.resolveWrite(HISTORY_FILE_REL).writeText(jsonArray.toString(2))
    }

    /** 按 bvid 删除一条记录 */
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
            StoragePaths.resolveWrite(HISTORY_FILE_REL).writeText(newArray.toString(2))
        }
    }
}