package com.tilixibiesi.util
import com.tilixibiesi.R
import com.tilixibiesi.data.StoragePaths

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import java.lang.ref.WeakReference

object BackgroundHelper {
    private const val TAG_BG_VIDEO = "bg_video"

    private const val BG_SAMPLE = 2

    private var cacheKey: String? = null
    private var cacheRef: WeakReference<Bitmap>? = null

    fun setActive(rootView: View, isActive: Boolean) {
        val video = (rootView as? ViewGroup)?.findViewWithTag<View>(TAG_BG_VIDEO)
        (video as? BackgroundVideoView)?.isActive = isActive
    }

    fun release(rootView: View) {
        val video = (rootView as? ViewGroup)?.findViewWithTag<View>(TAG_BG_VIDEO) ?: return
        (video as? BackgroundVideoView)?.release()
        rootView.removeView(video)
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
                rootView.removeView(existing)
            }
            applyStillBackground(context, rootView, finalBgPath, alphaPercent)
            return
        }

        val host = rootView as? FrameLayout
        if (host == null) {
            if (existing != null) {
                existing.release()
                rootView.removeView(existing)
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
        val bmp = if (path.isEmpty() || !File(path).exists()) null else obtainBackgroundBitmap(path)
        if (bmp == null) {
            rootView.background = ColorDrawable(context.getColor(R.color.activity_bg))
            return
        }
        val drawable = BitmapDrawable(context.resources, bmp)
        drawable.alpha = (alphaPercent / 100f * 255).toInt()
        rootView.background = drawable
    }

    private fun obtainBackgroundBitmap(path: String): Bitmap? {
        val file = File(path)
        val key = "$path:${file.lastModified()}"

        cacheRef?.get()?.let { cached ->
            if (cacheKey == key && !cached.isRecycled) return cached
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = BG_SAMPLE
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = false
        }
        val bmp = BitmapFactory.decodeFile(path, options) ?: return null

        cacheKey = key
        cacheRef = WeakReference(bmp)
        return bmp
    }

    private fun isVideoFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in listOf("mp4", "avi", "mkv", "mov", "webm", "3gp", "flv", "wmv")
    }
}
