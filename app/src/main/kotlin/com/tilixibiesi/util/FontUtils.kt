package com.tilixibiesi.util

import com.tilixibiesi.data.SpUtils

import android.content.Context
import android.graphics.Color

object FontUtils {

    fun color(context: Context): Int = try {
        Color.parseColor(SpUtils.getFontColor(context))
    } catch (_: Exception) {
        Color.parseColor(SpUtils.DEFAULT_FONT_COLOR)
    }

    fun color(context: Context, fallback: Int): Int = try {
        Color.parseColor(SpUtils.getFontColor(context))
    } catch (_: Exception) {
        fallback
    }

    fun dimmed(context: Context, alpha: Int = 0x99): Int =
        Color.argb(alpha, Color.red(color(context)), Color.green(color(context)), Color.blue(color(context)))
}
