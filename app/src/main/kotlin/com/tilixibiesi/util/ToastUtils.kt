package com.tilixibiesi.util

import android.content.Context
import android.widget.Toast

/** 短 Toast 的统一入口，避免各页面重复编写 Toast.makeText(...)。 */
object ToastUtils {
    fun show(context: Context, message: CharSequence) {
        Toast.makeText(context.applicationContext ?: context, message, Toast.LENGTH_SHORT).show()
    }
}
