package com.tilixibiesi.data

import android.app.Activity
import android.content.Context
import com.tilixibiesi.util.AppPermissions
import com.tilixibiesi.util.ContextUtils

object AppPermissionsBridge {

    fun unwrapActivity(context: Context): Activity? = ContextUtils.unwrapActivity(context)

    fun request(context: Context) {
        val activity = unwrapActivity(context) ?: return
        AppPermissions.requestPermissions(activity)
    }

    fun onResult(
        context: Context,
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        val activity = unwrapActivity(context) ?: return
        AppPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, activity)
    }
}
