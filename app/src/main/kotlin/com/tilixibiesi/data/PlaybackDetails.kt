package com.tilixibiesi.data

import org.json.JSONObject

/**
 * 播放时长明细（按天分片存储）。
 *
 * 每个分片文件以日期命名（`yyyy-MM-dd.json`），日期由文件名承载，
 * 文件内部只保存「歌曲原始名 -> 秒数」，格式为：
 *
 * ```json
 * { "歌曲原始名": 123, "另一首": 45 }
 * ```
 *
 * 不提供旧格式兼容：旧数据请先用 migrate_details.sh 迁移。
 */
internal object PlaybackDetails {
    const val DIR_REL = "system/axeron/long/Android/Appdata/details"

    /**
     * 解析一个分片文件。
     *
     * @return 歌曲原始名 -> 累计秒数；非数值项会被忽略
     */
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

    /** 序列化：歌曲原始名 -> 秒数。 */
    fun toJson(rootMap: Map<String, Long>): String {
        val root = JSONObject()
        rootMap.forEach { (name, seconds) -> root.put(name, seconds) }
        return root.toString()
    }
}
