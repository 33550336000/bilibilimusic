package com.tilixibiesi.ui.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 颜色选择滑块专用的渐变轨道。
 *
 * 与普通 SeekBar 轨道（seekbar_track）不同，它**忽略 level**：
 * 无论当前进度是多少，都完整画出这条滑块能选出的全部颜色，
 * 让用户一眼看出可选范围，而不是只看到一个「已填充」的色块。
 *
 * 例如色相滑块画整条彩虹、饱和度滑块画「灰 → 当前色相」、明度滑块画「黑 → 当前颜色」。
 */
class ColorTrackDrawable(private val cornerRadiusPx: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var colors: IntArray = intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT)

    private var shader: LinearGradient? = null

    private var shaderWidth = -1f

    /** 更新渐变色。传入 2 个以上颜色即可，宽度变化时会自动重建 shader。 */
    fun setColors(newColors: IntArray) {
        if (newColors.size < 2) return
        colors = newColors
        shader = null
        shaderWidth = -1f
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        if (width <= 0f || bounds.height() <= 0) return
        if (shader == null || shaderWidth != width) {
            shader = LinearGradient(0f, 0f, width, 0f, colors, null, Shader.TileMode.CLAMP)
            shaderWidth = width
        }
        paint.shader = shader
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            cornerRadiusPx,
            cornerRadiusPx,
            paint
        )
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /**
     * 轨道高度即圆角直径（原来的 seekbar_track.xml 用 `<size android:height="18dp"/>` 声明，
     * 自定义 Drawable 必须自己给出固有高度，否则 SeekBar 量出的轨道高度会是 0）。
     */
    override fun getIntrinsicHeight(): Int = cornerRadiusPx.toInt()

    override fun getIntrinsicWidth(): Int = -1
}

