package com.tilixibiesi.bili

data class DanmakuItem(
    /** 出现时间（毫秒，视频时间轴） */
    val timeMs: Long,
    /** 1=滚动 4=底部 5=顶部 6=逆向滚动 7=特殊（按滚动处理） */
    val mode: Int,
    /** 字号，25 为标准 */
    val fontSize: Int,
    /** 0xRRGGBB */
    val color: Int,
    val text: String
)
