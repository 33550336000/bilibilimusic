package com.tilixibiesi.util

import android.content.Context
import android.widget.Toast

object ToastUtils {
    fun show(context: Context, message: CharSequence) {
        Toast.makeText(context.applicationContext ?: context, message, Toast.LENGTH_SHORT).show()
    }
}
