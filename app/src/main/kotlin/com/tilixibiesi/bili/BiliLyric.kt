package com.tilixibiesi.bili

data class LyricLine(
    val fromSec: Float,
    val toSec: Float,
    val text: String
)

data class BiliLyric(
    val lines: List<LyricLine>,
    val source: Source
) {
    enum class Source {
        MUSIC_LIBRARY,

        SUBTITLE
    }

    fun textAt(positionMs: Long): String? {
        val index = indexAt(positionMs)
        return if (index < 0) null else lines[index].text
    }

    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val sec = positionMs / 1000f

        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].fromSec <= sec) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return -1

        if (sec > lines[found].toSec) return -1
        return found
    }
}
