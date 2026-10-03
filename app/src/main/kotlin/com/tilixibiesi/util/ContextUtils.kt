package com.tilixibiesi.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Context 相关的小工具。 */
object ContextUtils {

    /** 从任意 Context（含 Activity / ContextWrapper / BasePage）解出宿主 Activity。 */
    fun unwrapActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> unwrapActivity(context.baseContext)
        else -> null
    }
}
