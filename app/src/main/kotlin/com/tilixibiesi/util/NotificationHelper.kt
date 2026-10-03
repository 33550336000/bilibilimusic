package com.tilixibiesi.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import com.tilixibiesi.data.LanguageUtils

/** 音乐 / 视频前台服务通知的公共构建逻辑。 */
object NotificationHelper {

    /** 创建媒体播放通知渠道（同一 id 重复创建是幂等的）。 */
    fun createMediaChannel(context: Context, channelId: String, nameRes: Int, descRes: Int) {
        val channel = NotificationChannel(
            channelId,
            LanguageUtils.getString(context, nameRes),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = LanguageUtils.getString(context, descRes)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    /** 以 mediaPlayback 类型启动前台服务。 */
    fun startForegroundWithMediaPlayback(service: Service, id: Int, notification: Notification) {
        service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    /** 给 Notification.Builder 添加一个带图标的动作。 */
    fun addAction(
        context: Context,
        builder: Notification.Builder,
        icon: Int,
        title: String,
        pendingIntent: PendingIntent
    ) {
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, icon),
                title,
                pendingIntent
            ).build()
        )
    }
}
