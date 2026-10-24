package com.tilixibiesi.ui.page

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.util.ToastUtils

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import kotlin.random.Random

internal fun SettingsPage.showSendNotificationDialog() {
    val page = this
    val layout = LinearLayout(page).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 16, 32, 0)
        background = null
    }
    val etTitle = EditText(page).apply {
        hint = LanguageUtils.getString(page, R.string.hint_notification_title)
        setTextColor(page.userFontColor)
        setHintTextColor(Color.GRAY)
        background = page.resources.getDrawable(R.drawable.edittext_bg, null)
        setPadding(12, 12, 12, 12)
    }
    val etContent = EditText(page).apply {
        hint = LanguageUtils.getString(page, R.string.hint_notification_content)
        setTextColor(page.userFontColor)
        setHintTextColor(Color.GRAY)
        minLines = 3
        gravity = Gravity.TOP
        background = page.resources.getDrawable(R.drawable.edittext_bg, null)
        setPadding(12, 12, 12, 12)
    }
    layout.addView(etTitle)
    layout.addView(etContent)

    page.showMaterialDialog(
        AlertDialog.Builder(page)
            .setTitle(LanguageUtils.getString(page, R.string.dialog_title_send_notification))
            .setView(layout)
            .setPositiveButton(LanguageUtils.getString(page, R.string.btn_ok)) { _, _ ->
                val title = etTitle.text.toString().trim()
                    .ifEmpty { LanguageUtils.getString(page, R.string.notification_default_title) }
                val content = etContent.text.toString().trim()
                if (content.isEmpty()) {
                    ToastUtils.show(page, LanguageUtils.getString(page, R.string.toast_content_empty))
                } else {
                    page.sendCustomNotification(title, content)
                }
            }
            .setNegativeButton(LanguageUtils.getString(page, R.string.btn_cancel), null)
    )
}

internal fun SettingsPage.sendCustomNotification(title: String, content: String) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
        NotificationChannel(
            "custom_user_channel",
            LanguageUtils.getString(this, R.string.notification_default_title),
            NotificationManager.IMPORTANCE_DEFAULT
        )
    )
    val builder: Notification.Builder = Notification.Builder(this, "custom_user_channel")
    var id: Int
    do {
        id = Random.nextInt(Int.MAX_VALUE)
    } while (id == 1001)
    builder
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setAutoCancel(true)
    nm.notify(id, builder.build())
    ToastUtils.show(this, LanguageUtils.getString(this, R.string.toast_notification_sent))
}
