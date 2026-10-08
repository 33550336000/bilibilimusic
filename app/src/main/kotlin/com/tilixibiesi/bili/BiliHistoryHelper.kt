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
            list.add(parseEntry(jsonArray.getJSONObject(i), normalizeName = false, durationAsSeconds = false))
        }
        return list
    }

    fun loadAllNormalized(): List<MusicBean> {
        val jsonArray = readJsonArray()
        val list = mutableListOf<MusicBean>()
        for (i in 0 until jsonArray.length()) {
            list.add(parseEntry(jsonArray.getJSONObject(i), normalizeName = true, durationAsSeconds = true))
        }
        return list
    }

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

    private fun parseEntry(obj: JSONObject, normalizeName: Boolean, durationAsSeconds: Boolean): MusicBean {
        val title = obj.getString("title")
        val rawDuration = obj.optString("duration", "0")
        return MusicBean(title, "").apply {
            isBilibili = true
            bvid = obj.getString("bvid")
            author = obj.optString("author", "")
            duration = if (durationAsSeconds) parseDurationSeconds(rawDuration) else rawDuration.toIntOrNull() ?: 0
            coverUrl = obj.optString("coverUrl", "").ifEmpty { null }
            musicUrl = ""
            if (normalizeName) {
                this.musicName = DataFileUtils.getDisplayName(title)
            }
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