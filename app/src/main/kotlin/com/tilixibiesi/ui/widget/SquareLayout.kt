package com.tilixibiesi.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/**
 * 正方形容器：边长取 `min(可用宽度 × [sizeRatio], 可用高度 × [maxHeightFraction])`。
 *
 * 为什么需要它：正在播放页的封面必须是正方形（圆角 + centerCrop 才不变形），
 * 但封面尺寸既要"跟随屏幕宽度成比例"，又要在横屏/小屏上"不把下方控件挤出去"。
 * 纯 XML 表达不了这个约束——用固定 dp 会在不同屏宽上时大时小，
 * 用 `match_parent` 又会在窄高屏上撑满整宽而过高。
 *
 * **[maxHeightFraction] 是必需的，不是保险。**
 * 早期实现只做了 `min(宽度×比例, 可用高度)`，横屏下可用高度只剩约 800px、
 * 而宽度是 2800px，于是边长被高度顶到 800——正好等于中段的全部高度，
 * 标题/进度/控制按钮全被挤出屏幕。用比例上限显式**预留**下方控件的空间，
 * 才能从结构上避免这种"封面吃掉整页"的情况。
 *
 * [sizeRatio] / [maxHeightFraction] 由调用方在 inflate 后、首次 layout 前设置；
 * 切换横竖屏时重新设置即可。
 */
class SquareLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * 边长占可用宽度的比例。
     *
     * 默认 1f（铺满宽度），实际使用处会设置成约 0.62f（竖屏）
     * 或 0.42f（横屏双栏），与设计稿观感一致。
     */
    var sizeRatio: Float = 1f

    /**
     * 边长占可用高度的比例上限（1f = 不限制）。
     *
     * 竖屏要留出下方标题/进度/控制的位置，故取明显小于 1 的值；
     * 横屏封面在左、信息在右，纵向没有兄弟控件，可以放到 1f。
     */
    var maxHeightFraction: Float = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        var side = (availableWidth * sizeRatio).toInt()
        // 高度受限时按高度收敛，避免把下方控件顶出屏幕
        if (availableHeight > 0) {
            val heightLimit = (availableHeight * maxHeightFraction).toInt()
            if (side > heightLimit) side = heightLimit
        }
        if (side <= 0) side = availableWidth

        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}
