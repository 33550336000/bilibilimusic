package com.tilixibiesi.util

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 全屏与系统栏工具：统一各 Activity 的全屏窗口设置。
 *
 * 仅支持 Android 11 (API 30) 及以上，统一使用 WindowInsetsController 现代 API。
 */
object WindowUtils {

    /**
     * 全屏：隐藏状态栏与导航栏（沉浸式，下滑/上滑临时呼出）。
     * 供各 Activity 统一调用。
     */
    fun setFullScreen(activity: Activity) {
        activity.window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes = attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            setDecorFitsSystemWindows(false)
            insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /**
     * 恢复系统栏：取消沉浸式，重新显示状态栏与导航栏。
     * 供全屏播放器等退出全屏时统一调用。
     */
    fun restoreSystemUI(activity: Activity) {
        activity.window.apply {
            setDecorFitsSystemWindows(true)
            insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        }
    }
}
