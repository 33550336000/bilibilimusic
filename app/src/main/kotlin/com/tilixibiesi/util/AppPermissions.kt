package com.tilixibiesi.util

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 应用权限申请的统一入口。
 *
 * 原职责取自 `AppManager`（权限申请部分），负责：
 * - 运行时权限申请（通知权限等）
 * - 全部文件访问（MANAGE_EXTERNAL_STORAGE）的特殊权限引导
 */
object AppPermissions {
    private const val PERMISSION_REQUEST_CODE = 100

    fun requestPermissions(activity: Activity) {
        // 已不支持 Android 11 以下：仅需申请通知权限（API 33+），
        // 存储走“所有文件访问”（MANAGE_EXTERNAL_STORAGE）特殊权限（见 checkSpecialPermissions）。
        val runtimePermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            runtimePermissions.add("android.permission.POST_NOTIFICATIONS")
        }
        val required = runtimePermissions.toTypedArray()
        val need = required.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (need.isNotEmpty()) {
            activity.requestPermissions(need, PERMISSION_REQUEST_CODE)
        } else {
            checkSpecialPermissions(activity)
        }
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        activity: Activity
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                checkSpecialPermissions(activity)
            } else {
                ToastUtils.show(activity, LanguageUtils.getString(activity, R.string.perm_partial))
            }
        }
    }

    private fun checkSpecialPermissions(activity: Activity) {
        // 已不支持 Android 11 以下，统一需要“所有文件访问”（MANAGE_EXTERNAL_STORAGE）特殊权限。
        if (!Environment.isExternalStorageManager()) {
            requestAllFilesPermission(activity)
        }
    }

    private fun requestAllFilesPermission(activity: Activity) {
        showMaterialDialog(
            activity,
            AlertDialog.Builder(activity, R.style.TransparentDialog)
                .setTitle(LanguageUtils.getString(activity, R.string.perm_need))
                .setMessage(LanguageUtils.getString(activity, R.string.perm_msg))
                .setPositiveButton(LanguageUtils.getString(activity, R.string.perm_go_settings)) { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    )
                }
                .setNegativeButton(LanguageUtils.getString(activity, R.string.cancel), null)
        )
    }

    private fun showMaterialDialog(activity: Activity, builder: AlertDialog.Builder): AlertDialog {
        return DialogHelper.createStyledDialog(activity, builder, boldItalicAllViews = true)
    }
}