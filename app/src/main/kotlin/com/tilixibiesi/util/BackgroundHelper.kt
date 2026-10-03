package com.tilixibiesi.util
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.StoragePaths

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import java.io.File

object BackgroundHelper {
    fun applyBackground(context: Context, rootView: View, alphaPercent: Int) {
        // 移除已有的视频背景
        (rootView as? ViewGroup)?.findViewWithTag<View>("bg_video")?.let {
            (it as? VideoView)?.stopPlayback()
            rootView.removeView(it)   // 无需强制转换，智能转换已生效
        }

        // 1. 优先尝试从固定目录加载默认背景（卸载重装后依然存在）
        val defaultBgDir = StoragePaths.resolveRead("system/axeron/long/Android/default_bg/")
        var defaultBgFile: File? = null
        if (defaultBgDir.exists() && defaultBgDir.isDirectory) {
            // 查找以 default_background 开头的文件（忽略扩展名）
            defaultBgFile = defaultBgDir.listFiles()?.firstOrNull { file ->
                file.isFile && file.name.startsWith("default_background")
            }
        }

        // 2. 固定目录下的默认背景是唯一来源。
        //    原先还有一个「回退到 Sp 里存的路径（兼容旧版）」分支，但全部 6 个调用点
        //    传入的 bgPath 本身就是 SpUtils.getDefaultBackgroundPath()，该分支恒不可达。
        val finalBgPath = defaultBgFile?.takeIf { it.exists() }?.absolutePath ?: ""

        if (finalBgPath.isEmpty() || !File(finalBgPath).exists()) {
            rootView.background = ColorDrawable(0xFF000000.toInt())
            return
        }

        if (isVideoFile(finalBgPath)) {
            val videoView = VideoView(context).apply {
                tag = "bg_video"
                setVideoPath(finalBgPath)
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mp.start()
                }
                setOnErrorListener { _, _, _ -> true }
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                alpha = alphaPercent / 100f
            }
            (rootView as? ViewGroup)?.addView(videoView, 0)
        } else {
            BitmapFactory.decodeFile(finalBgPath)?.let { bmp ->
                val drawable = BitmapDrawable(context.resources, bmp)
                drawable.alpha = (alphaPercent / 100f * 255).toInt()
                rootView.background = drawable
            } ?: run {
                rootView.background = ColorDrawable(0xFF000000.toInt())
            }
        }
    }

    private fun isVideoFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in listOf("mp4", "avi", "mkv", "mov", "webm", "3gp", "flv", "wmv")
    }
}
