package com.tilixibiesi.bili

/**
 * 字幕轨道。
 *
 * 来源是**两个接口的合并结果**，不是单一接口：
 *  - `x/web-interface/view` 的 `data.subtitle.list`：**稳定**的完整语种清单，但 `subtitle_url` 恒为空；
 *  - `x/player/v2` 的 `data.subtitle.subtitles[]`：**每次只随机返回其中 1~2 条**（带可用的 `subtitle_url`）。
 *
 * 因此单个字段的语义与其来源绑定：`lan`/`lanDoc` 来自稳定清单，`subtitleUrl` 来自采样。
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
