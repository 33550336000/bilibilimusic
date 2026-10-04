package com.tilixibiesi.bili

/**
 * 字幕轨道。
 *
 * 来源是 `x/player/wbi/v2` 的 `data.subtitle.subtitles[]`——**一次请求即返回全部轨道**
 * （人传字幕与 AI 字幕都在内），每条都带可用的 `subtitle_url`。
 *
 * 不要改用旧路径 `x/player/v2`：那个路径会返回互不相同、且内容错误的字幕
 * （详情见 [BiliSubtitleHelper] 的类注释）。历史上"每次只随机返回 1~2 条、
 * 必须多次采样"的说法正是那个 bug 造成的误解，已不成立。
 */
data class BiliSubtitleTrack(
    val lan: String,
    val lanDoc: String,
    val subtitleUrl: String,
    /** 轨道 id，仅用于区分同名轨道，不参与展示 */
    val id: String = "",
    /** 是否为 AI 字幕（B 站的 AI 字幕与人传字幕可能重名，需靠它区分命名） */
    val isAi: Boolean = false
) {
    /** 展示名：AI 字幕加后缀，避免与人传字幕显示成两条一模一样的「中文」 */
    val displayName: String
        get() = if (isAi) "$lanDoc（AI）" else lanDoc
}

/** 字幕条目（B 站字幕 JSON 的 body[]） */
data class BiliSubtitleCue(
    val fromSec: Float,
    val toSec: Float,
    val content: String
)
