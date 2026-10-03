package com.tilixibiesi.data

import org.json.JSONObject

/**
 * 播放明细 details/<日期>.json 的统一编解码。
 *
 * 内存结构：歌曲名 -> 日期 -> 秒数。
 * 单文件 details.json 的旧格式已停止支持：既不读取也不再迁移。
 */
internal object PlaybackDetails {
    const val DIR_REL = "system/axeron/long/Android/Appdata/details"

    /** 解析一个 JSON 文本；同歌曲同日期取累加值，损坏内容由调用方处理。 */
    fun parse(text: String): MutableMap<String, MutableMap<String, Long>> {
        val result = mutableMapOf<String, MutableMap<String, Long>>()
        val root = JSONObject(text)
        val names = root.keys()
        while (names.hasNext()) {
            val name = names.next()
            val dateObj = root.getJSONObject(name)
            val dateMap = result.getOrPut(name) { mutableMapOf() }
            val dates = dateObj.keys()
            while (dates.hasNext()) {
                val date = dates.next()
                dateMap[date] = (dateMap[date] ?: 0L) + dateObj.optLong(date, 0L)
            }
        }
        return result
    }

    /** 序列化为分片文件的 JSON 格式。 */
    fun toJson(rootMap: Map<String, Map<String, Long>>): String {
        val root = JSONObject()
        rootMap.forEach { (name, dateMap) ->
            val dateJson = JSONObject()
            dateMap.forEach { (date, seconds) -> dateJson.put(date, seconds) }
            root.put(name, dateJson)
        }
        return root.toString()
    }
}
