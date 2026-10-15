package com.tilixibiesi.util

import com.tilixibiesi.data.SpUtils

import android.content.Context
import android.graphics.Color

/**
 * 全局字体颜色 / 字号读取。
 *
 * 设置项以字符串形式保存，解析失败时回退到 [SpUtils.DEFAULT_FONT_COLOR]，
 * 避免各处重复 try/catch。
 *
 * 注意：回退值必须与默认字体色一致（深色）。早期各处回退成白色，
 * 在浅色卡片背景下会直接看不见，正是「默认色改成白色会不可见」的来源。
 */
object FontUtils {

    /** 全局字体颜色；非法值时回退为默认色。 */
    fun color(context: Context): Int = try {
        Color.parseColor(SpUtils.getFontColor(context))
    } catch (_: Exception) {
        Color.parseColor(SpUtils.DEFAULT_FONT_COLOR)
    }

    /** 全局字体颜色，非法值时回退到 [fallback]。 */
    fun color(context: Context, fallback: Int): Int = try {
        Color.parseColor(SpUtils.getFontColor(context))
    } catch (_: Exception) {
        fallback
    }

    /**
     * 全局字体颜色的淡化版，用于 hint 等次要文字：
     * 保留色相、降低不透明度，在深浅背景上都能与实际输入内容区分。
     */
    fun dimmed(context: Context, alpha: Int = 0x99): Int =
        Color.argb(alpha, Color.red(color(context)), Color.green(color(context)), Color.blue(color(context)))
}
