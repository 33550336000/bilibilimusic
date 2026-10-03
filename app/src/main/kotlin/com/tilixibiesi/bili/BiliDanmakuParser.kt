package com.tilixibiesi.bili

object BiliDanmakuParser {

    /** 匹配 `<d p="...">文本</d>`；非贪婪，兼容属性顺序与多余空格。 */
    private val DANMAKU_RE = Regex("""<d\s+p\s*=\s*"([^"]*)"\s*>([\s\S]*?)</d>""")

    private const val DEFAULT_FONT_SIZE = 25
    private const val DEFAULT_COLOR = 0xFFFFFF

    fun parse(xml: String): List<DanmakuItem> {
        val list = ArrayList<DanmakuItem>()
        for (m in DANMAKU_RE.findAll(xml)) {
            val p = m.groupValues[1]
            val text = unescape(m.groupValues[2])
            if (text.isBlank()) continue
            val fields = p.split(",")
            // p = 出现时间(秒), 模式, 字号, 颜色, 发送时间戳, 弹幕池, 发送者hash, dbid
            val timeSec = fields.getOrNull(0)?.trim()?.toFloatOrNull() ?: continue
            if (timeSec < 0f) continue
            val mode = fields.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
            val fontSize = fields.getOrNull(2)?.trim()?.toIntOrNull() ?: DEFAULT_FONT_SIZE
            val color = fields.getOrNull(3)?.trim()?.toIntOrNull() ?: DEFAULT_COLOR
            list.add(
                DanmakuItem(
                    timeMs = (timeSec * 1000).toLong(),
                    mode = mode,
                    fontSize = fontSize.coerceIn(10, 60),
                    color = color and 0xFFFFFF,
                    text = text
                )
            )
        }
        // 渲染层依赖「按时间递增」顺序做指针推进，必须先排序
        list.sortBy { it.timeMs }
        return list
    }

    /** 只处理弹幕文本里真正会出现的几种实体 */
    private fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val semi = s.indexOf(';', i)
            if (semi < 0) { sb.append(s, i, s.length); break }
            val ent = s.substring(i, semi + 1)
            val decoded = when (ent) {
                "&amp;" -> "&"
                "&lt;" -> "<"
                "&gt;" -> ">"
                "&quot;" -> "\""
                "&apos;", "&#39;" -> "'"
                "&nbsp;" -> " "
                else -> decodeNumeric(ent)
            }
            sb.append(decoded ?: ent)
            i = semi + 1
        }
        return sb.toString()
    }

    private fun decodeNumeric(ent: String): String? {
        if (!ent.startsWith("&#") || !ent.endsWith(";")) return null
        val body = ent.substring(2, ent.length - 1)
        return try {
            val code = if (body.startsWith("x", true)) body.substring(1).toInt(16) else body.toInt()
            code.toChar().toString()
        } catch (_: Exception) {
            null
        }
    }
}
