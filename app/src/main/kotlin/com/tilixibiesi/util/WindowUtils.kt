package com.tilixibiesi.util

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

object WindowUtils {

    fun setFullScreen(activity: Activity) {
        val peak = peakRefreshRate(activity)
        activity.window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes = attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                // 不显式请求就会停在默认模式（通常 60Hz），设备 120Hz 也用不上。
                if (peak != null) preferredRefreshRate = peak
            }
            setDecorFitsSystemWindows(false)
            insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            // Android 15 起自适应刷新率是 View 投票制，窗口请求之外还要投一票 HIGH，否则仍按 NORMAL(60Hz)。
            if (peak != null && Build.VERSION.SDK_INT >= 35) {
                decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            }
        }
    }

    /** 屏幕支持的最高刷新率；取不到时返回 null，保持系统默认。 */
    private fun peakRefreshRate(activity: Activity): Float? = runCatching {
        activity.display?.supportedModes?.maxOfOrNull { it.refreshRate }?.takeIf { it > 0f }
    }.getOrNull()

    fun restoreSystemUI(activity: Activity) {
        activity.window.apply {
            setDecorFitsSystemWindows(true)
            insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        }
    }
}
