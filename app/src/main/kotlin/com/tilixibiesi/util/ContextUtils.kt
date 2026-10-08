package com.tilixibiesi.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

object ContextUtils {

    fun unwrapActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> unwrapActivity(context.baseContext)
        else -> null
    }
}
