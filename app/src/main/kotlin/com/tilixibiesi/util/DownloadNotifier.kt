package com.tilixibiesi.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils

object DownloadNotifier {

    const val CHANNEL_ID = "download_channel"

    private const val NOTIFY_ID_BASE = 2000

    private const val NOTIFY_ID_COMPLETE_BASE = 3000

    fun notifyId(key: String): Int = NOTIFY_ID_BASE + Math.abs(key.hashCode() % 1000)

    fun completeId(key: String): Int = NOTIFY_ID_COMPLETE_BASE + Math.abs(key.hashCode() % 1000)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            LanguageUtils.getString(context, R.string.download_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = LanguageUtils.getString(context, R.string.download_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    fun showProgress(context: Context, notifyId: Int, title: String, content: String, progress: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification: Notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()
        manager.notify(notifyId, notification)
    }

    fun finish(context: Context, key: String, title: String, content: String) {
        cancel(context, notifyId(key))
        showComplete(context, completeId(key), title, content)
    }

    fun showComplete(context: Context, notifyId: Int, title: String, content: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification: Notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
        manager.notify(notifyId, notification)
    }

    fun cancel(context: Context, notifyId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notifyId)
    }
}
