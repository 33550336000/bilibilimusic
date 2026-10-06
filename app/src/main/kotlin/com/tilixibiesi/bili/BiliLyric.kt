package com.tilixibiesi.bili

/**
 * 歌词的一句（带起止时间）。
 *
 * 与 [BiliSubtitleCue] 的区别只在归属：那个描述的是「视频字幕轨里的一条」，
 * 直接对应接口返回的 from/to；这个描述的是「歌的一句」，结束时间往往不是接口给的，
 * 而是由下一句的开始时间推出来的（见 [BiliLyricHelper.parseLrc]）。
 */
data class LyricLine(
    val fromSec: Float,
    val toSec: Float,
    val text: String
)

/**
 * 一首歌的歌词。
 *
 * 由 [BiliLyricHelper] 产出，已按时间排好序、已剔除空行。
 * **注意时间轴只对得上「视频开头那首歌」**：B 站曲库的歌词对应的是它关联的
 * 那支单曲（`bgm/detail` 里的 `mv_bvid`/`mv_cid`），而本项目播放的正是
 * `view` 接口的顶层 cid，也就是 P1——两者恰好是同一首，所以对得上。
 * 一首几小时的合集稿件里，只有开头那首的歌名/歌词是准的，后面的分 P 对不上。
 */
data class BiliLyric(
    val lines: List<LyricLine>,
    val source: Source
) {
    enum class Source {
        /**
         * B 站音乐曲库（版权音乐）的 LRC 文件。带精确时间轴，**优先**采用。
         */
        MUSIC_LIBRARY,

        /**
         * 视频字幕轨（人传字幕或 AI 字幕）。曲库没有歌词时才用。
         *
         * AI 字幕是 ASR 产物、可能有识别错字，但内容确实是**本视频**在唱的，
         * 因此作为兜底优于"什么都不显示"。
         *
         * 前提是必须经 `x/player/wbi/v2` 获取：旧路径 `x/player/v2`
         * 会返回不相干的其它视频文本，详见 [BiliSubtitleHelper] 的类注释。
         */
        SUBTITLE
    }

    /**
     * 取 [positionMs] 时刻应当显示的那句歌词。
     *
     * 返回 null 表示这一刻**没有歌词可显示**（前奏、尾奏，或整份歌词为空），
     * 调用方应回退到「正在播放 / 已暂停」。
     *
     * 间奏处**不会**返回 null：占位的空行在解析阶段就被剔除了，上一句的结束时间
     * 直接接到下一句的开始，所以纯音乐段落里仍是上一句停留着，而不是文案来回跳。
     */
    fun textAt(positionMs: Long): String? {
        val index = indexAt(positionMs)
        return if (index < 0) null else lines[index].text
    }

    /**
     * 取 [positionMs] 时刻对应的歌词**行下标**；这一刻没有歌词时返回 -1。
     *
     * 与 [textAt] 同源（后者就是它的薄封装）：调用方若拿下标去驱动 UI，
     * 就不必按"文本相等"反查——重复句（副歌）在 LRC 里非常常见，
     * 按文本反查会一律命中第一处，导致高亮停在错误的那一句上。
     */
    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val sec = positionMs / 1000f

        // 二分找最后一条 fromSec <= sec 的
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

        // 只可能是最后一句：它的结束时间是"自己加一个固定尾长"，播完就该退回默认文案
        if (sec > lines[found].toSec) return -1
        return found
    }
}
