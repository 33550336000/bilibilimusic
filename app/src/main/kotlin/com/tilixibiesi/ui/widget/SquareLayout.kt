package com.tilixibiesi.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

class SquareLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var sizeRatio: Float = 1f

    var maxHeightFraction: Float = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        var side = (availableWidth * sizeRatio).toInt()
        if (availableHeight > 0) {
            val heightLimit = (availableHeight * maxHeightFraction).toInt()
            if (side > heightLimit) side = heightLimit
        }
        if (side <= 0) side = availableWidth

        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}
