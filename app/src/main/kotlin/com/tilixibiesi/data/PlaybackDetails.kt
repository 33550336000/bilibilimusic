package com.tilixibiesi.data

import org.json.JSONObject

internal object PlaybackDetails {
    const val DIR_REL = "system/axeron/long/Android/Appdata/details"

    fun parse(text: String): MutableMap<String, Long> {
        val result = mutableMapOf<String, Long>()
        val root = JSONObject(text)
        val names = root.keys()
        while (names.hasNext()) {
            val name = names.next()
            val value = root.opt(name)
            if (value is Number) {
                result[name] = (result[name] ?: 0L) + value.toLong()
            }
        }
        return result
    }

    fun toJson(rootMap: Map<String, Long>): String {
        val root = JSONObject()
        rootMap.forEach { (name, seconds) -> root.put(name, seconds) }
        return root.toString()
    }
}
