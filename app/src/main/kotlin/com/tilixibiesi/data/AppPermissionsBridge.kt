package com.tilixibiesi.data

import android.app.Activity
import android.content.Context
import com.tilixibiesi.util.AppPermissions
import com.tilixibiesi.util.ContextUtils

/**
 * 权限申请桥接：让页面（ContextWrapper）也能走原本只接受 Activity 的权限流程。
 *
 * AppPermissions 内部需要用 Activity 调 requestPermissions / startActivity，
 * 而 BasePage 只是 ContextWrapper，因此这里向上解包出真实 Activity 再转发。
 */
object AppPermissionsBridge {

    /** 从任意 Context（含页面/ContextWrapper）解出宿主 Activity */
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
