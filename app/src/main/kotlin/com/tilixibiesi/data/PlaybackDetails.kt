package com.tilixibiesi.data

import org.json.JSONObject

internal object PlaybackDetails {
    const val DIR_REL = "system/axeron/long/Android/Appdata/details"

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
