package com.tilixibiesi.util

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * 记录当前位于前台的 Activity。
 *
 * 用于在没有界面引用（例如后台播放服务）的地方也能找到「该把登录页弹到哪」，
 * 以及在界面回到前台时补弹之前错过的登录请求。
 */
object ActivityTracker {

    private var ref: WeakReference<Activity>? = null

    fun onResumed(activity: Activity) {
        ref = WeakReference(activity)
    }

    fun onDestroyed(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    fun current(): Activity? = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
