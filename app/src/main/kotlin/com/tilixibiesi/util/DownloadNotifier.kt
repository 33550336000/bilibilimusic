package com.tilixibiesi.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils

/**
 * 音乐下载通知工具。
 *
 * 供自动下载（[CacheManager.startBackgroundCache]）使用，
 * 使用同一个通知渠道展示下载进度，让用户随时了解下载状态。
 */
object DownloadNotifier {

    /** 下载通知渠道 id */
    const val CHANNEL_ID = "download_channel"

    private const val NOTIFY_ID_BASE = 2000

    /** 完成通知的 id 基址：与进度通知分开，保证「清进度通知」不会顺带清掉完成通知 */
    private const val NOTIFY_ID_COMPLETE_BASE = 3000

    /** 根据下载项名称生成稳定的通知 id（同一首歌重复下载复用同一条通知） */
    fun notifyId(key: String): Int = NOTIFY_ID_BASE + Math.abs(key.hashCode() % 1000)

    /** 完成通知的 id（与 [notifyId] 不同段，互不覆盖） */
    fun completeId(key: String): Int = NOTIFY_ID_COMPLETE_BASE + Math.abs(key.hashCode() % 1000)

    /** 创建通知渠道（同一 id 重复创建是幂等的） */
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

    /** 展示/更新下载进度通知 */
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

    /**
     * 下载结束的统一收尾：先取消下载中的进度通知，再发送一条独立的完成通知。
     *
     * 完成通知使用 [completeId]（另一 id 段），因此先 [cancel] 进度通知不会把它一起清掉。
     */
    fun finish(context: Context, key: String, title: String, content: String) {
        cancel(context, notifyId(key))
        showComplete(context, completeId(key), title, content)
    }

    /** 展示下载完成通知（默认 autoCancel，由用户点击或下一次同名下载自然覆盖） */
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

    /** 取消指定通知 */
    fun cancel(context: Context, notifyId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notifyId)
    }
}
