package com.tilixibiesi
import com.tilixibiesi.service.MusicPlayerService

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 只响应开机完成广播
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            // 构建启动服务的 Intent
            val serviceIntent = Intent(context, MusicPlayerService::class.java)

            context.startForegroundService(serviceIntent)

            // 你还可以发送一个自定义广播给 Service 来执行特定动作（比如播放上次的歌曲）
            // 但这不是必须的，因为你的 Service 在 onStartCommand 中已处理了无 Action 的情况（恢复播放）
        }
    }
}
