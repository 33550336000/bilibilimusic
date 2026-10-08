package com.tilixibiesi.bili

data class BiliSubtitleTrack(
    val lan: String,
    val lanDoc: String,
    val subtitleUrl: String,
    val id: String = "",
    val isAi: Boolean = false
) {
    val displayName: String
        get() = if (isAi) "$lanDoc（AI）" else lanDoc
}

data class BiliSubtitleCue(
    val fromSec: Float,
    val toSec: Float,
    val content: String
)
