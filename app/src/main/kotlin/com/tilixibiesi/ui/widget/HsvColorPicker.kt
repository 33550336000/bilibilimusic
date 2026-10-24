package com.tilixibiesi.ui.widget

import android.graphics.Color
import android.widget.SeekBar

class HsvColorPicker(
    private val hueBar: SeekBar,
    private val saturationBar: SeekBar,
    private val valueBar: SeekBar,
    cornerRadiusPx: Float
) {

    private val hueTrack = ColorTrackDrawable(cornerRadiusPx)
    private val saturationTrack = ColorTrackDrawable(cornerRadiusPx)
    private val valueTrack = ColorTrackDrawable(cornerRadiusPx)

    var onColorChanged: ((Int) -> Unit)? = null

    var isApplying = false

    private val hueGradient: IntArray = IntArray(361) { i ->
        Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f))
    }

    private companion object {
        const val MIN_TRACK_VISIBILITY = 0.35f

        const val SAT_VALUE_MAX = 255
    }

    init {
        hueBar.max = 359
        saturationBar.max = SAT_VALUE_MAX
        valueBar.max = SAT_VALUE_MAX

        hueBar.progressDrawable = hueTrack
        saturationBar.progressDrawable = saturationTrack
        valueBar.progressDrawable = valueTrack

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                refreshTracks()
                if (isApplying) return
                onColorChanged?.invoke(currentColor())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        }
        hueBar.setOnSeekBarChangeListener(listener)
        saturationBar.setOnSeekBarChangeListener(listener)
        valueBar.setOnSeekBarChangeListener(listener)

        refreshTracks()
    }

    fun currentColor(): Int = Color.HSVToColor(
        floatArrayOf(
            hueBar.progress.toFloat(),
            saturationBar.progress / SAT_VALUE_MAX.toFloat(),
            valueBar.progress / SAT_VALUE_MAX.toFloat()
        )
    )

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        isApplying = true
        hueBar.progress = Math.round(hsv[0]).coerceIn(0, 359)
        saturationBar.progress = Math.round(hsv[1] * SAT_VALUE_MAX).coerceIn(0, SAT_VALUE_MAX)
        valueBar.progress = Math.round(hsv[2] * SAT_VALUE_MAX).coerceIn(0, SAT_VALUE_MAX)
        isApplying = false
        refreshTracks()
    }

    fun refreshTracks() {
        val hue = hueBar.progress.toFloat()
        val saturation = saturationBar.progress / SAT_VALUE_MAX.toFloat()
        val value = valueBar.progress / SAT_VALUE_MAX.toFloat()

        hueTrack.setColors(hueGradient)

        val displayValue = value.coerceAtLeast(MIN_TRACK_VISIBILITY)
        saturationTrack.setColors(
            intArrayOf(
                Color.HSVToColor(floatArrayOf(hue, 0f, displayValue)),
                Color.HSVToColor(floatArrayOf(hue, 1f, displayValue))
            )
        )

        valueTrack.setColors(
            intArrayOf(
                Color.HSVToColor(floatArrayOf(hue, saturation, 0f)),
                Color.HSVToColor(floatArrayOf(hue, saturation, 1f))
            )
        )
    }
}
