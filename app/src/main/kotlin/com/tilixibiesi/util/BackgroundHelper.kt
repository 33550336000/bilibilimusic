package com.tilixibiesi.util
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.StoragePaths

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File

object BackgroundHelper {
    private const val TAG_BG_VIDEO = "bg_video"

    fun setActive(rootView: View, isActive: Boolean) {
        val video = (rootView as? ViewGroup)?.findViewWithTag<View>(TAG_BG_VIDEO)
        (video as? BackgroundVideoView)?.isActive = isActive
    }

    fun release(rootView: View) {
        val video = (rootView as? ViewGroup)?.findViewWithTag<View>(TAG_BG_VIDEO) ?: return
        (video as? BackgroundVideoView)?.release()
        (rootView as ViewGroup).removeView(video)
    }

    fun applyBackground(context: Context, rootView: View, alphaPercent: Int) {
        val existing = (rootView as? ViewGroup)
            ?.findViewWithTag<View>(TAG_BG_VIDEO) as? BackgroundVideoView

        val defaultBgDir = StoragePaths.resolveRead("system/axeron/long/Android/default_bg/")
        var defaultBgFile: File? = null
        if (defaultBgDir.exists() && defaultBgDir.isDirectory) {
            defaultBgFile = defaultBgDir.listFiles()?.firstOrNull { file ->
                file.isFile && file.name.startsWith("default_background")
            }
        }

        val finalBgPath = defaultBgFile?.takeIf { it.exists() }?.absolutePath ?: ""

        if (finalBgPath.isEmpty() || !File(finalBgPath).exists() || !isVideoFile(finalBgPath)) {
            if (existing != null) {
                existing.release()
                (rootView as ViewGroup).removeView(existing)
            }
            applyStillBackground(context, rootView, finalBgPath, alphaPercent)
            return
        }

        val host = rootView as? FrameLayout
        if (host == null) {
            if (existing != null) {
                existing.release()
                (rootView as ViewGroup).removeView(existing)
            }
            rootView.background = ColorDrawable(0xFF000000.toInt())
            return
        }

        host.background = ColorDrawable(0xFF000000.toInt())

        if (existing != null && existing.sourcePath == finalBgPath) {
            existing.alpha = alphaPercent / 100f
            return
        }
        if (existing != null) {
            existing.release()
            host.removeView(existing)
        }

        val videoView = BackgroundVideoView(context).apply {
            tag = TAG_BG_VIDEO
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            alpha = alphaPercent / 100f
            bind(finalBgPath)
        }
        host.addView(videoView, 0)
    }

    private fun applyStillBackground(
        context: Context,
        rootView: View,
        path: String,
        alphaPercent: Int
    ) {
        if (path.isEmpty() || !File(path).exists()) {
            rootView.background = ColorDrawable(0xFF000000.toInt())
            return
        }
        BitmapFactory.decodeFile(path)?.let { bmp ->
            val drawable = BitmapDrawable(context.resources, bmp)
            drawable.alpha = (alphaPercent / 100f * 255).toInt()
            rootView.background = drawable
        } ?: run {
            rootView.background = ColorDrawable(0xFF000000.toInt())
        }
    }

    private fun isVideoFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in listOf("mp4", "avi", "mkv", "mov", "webm", "3gp", "flv", "wmv")
    }
}
