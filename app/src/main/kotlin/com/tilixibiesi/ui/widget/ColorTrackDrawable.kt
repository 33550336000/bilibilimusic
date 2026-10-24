package com.tilixibiesi.ui.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

class ColorTrackDrawable(private val cornerRadiusPx: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var colors: IntArray = intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT)

    private var shader: LinearGradient? = null

    private var shaderWidth = -1f

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

    override fun getIntrinsicHeight(): Int = cornerRadiusPx.toInt()

    override fun getIntrinsicWidth(): Int = -1
}

