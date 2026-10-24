package com.tilixibiesi.util

import android.app.Activity
import java.lang.ref.WeakReference

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
