package com.tilixibiesi.ui.widget

import android.graphics.Color
import android.widget.SeekBar

/**
 * 三滑块 HSV 调色器：色相 + 饱和度 + 明度。
 *
 * 单个滑块只能给出一个数值，也就是色彩空间里的一条一维曲线，永远无法覆盖
 * 全部颜色。补上饱和度与明度两个维度后，才能选出黑、白、灰和各种深浅彩色。
 *
 * 三条轨道的渐变都按当前状态实时重算：
 * - 色相：整条彩虹（与其它两个滑块无关）
 * - 饱和度：灰 → 当前色相的纯色
 * - 明度：黑 → 当前色相/饱和度的最亮色
 *
 * 因此拖动任意一条，另外两条的轨道都会立刻反映出「接下来能选到什么颜色」。
 *
 * 轨道用 [ColorTrackDrawable]：它忽略 SeekBar 的 level，所以始终完整显示
 * 可选范围，而不是只显示「已填充」的一段。
 */
class HsvColorPicker(
    private val hueBar: SeekBar,
    private val saturationBar: SeekBar,
    private val valueBar: SeekBar,
    cornerRadiusPx: Float
) {

    private val hueTrack = ColorTrackDrawable(cornerRadiusPx)
    private val saturationTrack = ColorTrackDrawable(cornerRadiusPx)
    private val valueTrack = ColorTrackDrawable(cornerRadiusPx)

    /** 颜色变化回调，用于保存设置与刷新预览。 */
    var onColorChanged: ((Int) -> Unit)? = null

    /** 回填 UI 期间置为 true，避免触发保存与递归。 */
    var isApplying = false

    private val hueGradient: IntArray = IntArray(361) { i ->
        Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f))
    }

    private companion object {
        /**
         * 轨道显示用的最低明度。
         *
         * 只影响轨道的观感，不参与取色：明度滑块拖到 0 时饱和度轨道若照实画，
         * 两端都是纯黑，整条轨道会看不见。留 35% 明度让它始终可辨认。
         */
        const val MIN_TRACK_VISIBILITY = 0.35f

        /**
         * 饱和度 / 明度滑块的上限。
         *
         * 用 0-255 而不是 0-100：滑块位置回填到颜色时必然要取整，
         * 100 档会让大量颜色在「打开设置页再拖一下」后被悄悄改掉
         * （例如 #7F3F1F 变成 #803F1F）。255 档把无损回填比例从约 12% 提到约 52%。
         */
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

    /** 当前滑块组合出的颜色。 */
    fun currentColor(): Int = Color.HSVToColor(
        floatArrayOf(
            hueBar.progress.toFloat(),
            saturationBar.progress / SAT_VALUE_MAX.toFloat(),
            valueBar.progress / SAT_VALUE_MAX.toFloat()
        )
    )

    /** 把外部颜色回填到三个滑块（不触发 [onColorChanged]）。 */
    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        isApplying = true
        // 三个分量都四舍五入：截断会让颜色在「打开设置页」时就被悄悄改掉
        hueBar.progress = Math.round(hsv[0]).coerceIn(0, 359)
        saturationBar.progress = Math.round(hsv[1] * SAT_VALUE_MAX).coerceIn(0, SAT_VALUE_MAX)
        valueBar.progress = Math.round(hsv[2] * SAT_VALUE_MAX).coerceIn(0, SAT_VALUE_MAX)
        isApplying = false
        refreshTracks()
    }

    /**
     * 重算三条轨道的渐变。
     *
     * 饱和度与明度轨道必须跟随当前色相，否则用户拖到某色相后，
     * 另外两条滑块仍显示旧颜色，无法预期结果。
     */
    fun refreshTracks() {
        val hue = hueBar.progress.toFloat()
        val saturation = saturationBar.progress / SAT_VALUE_MAX.toFloat()
        val value = valueBar.progress / SAT_VALUE_MAX.toFloat()

        hueTrack.setColors(hueGradient)

        // 明度很低时两端都接近黑色，轨道会整条糊成一块黑，用户看不出这条滑块的作用。
        // 这里只给「轨道显示」加一个可见度下限，实际取色仍用真实明度，不影响结果。
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
