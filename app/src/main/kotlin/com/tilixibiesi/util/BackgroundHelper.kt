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
import android.widget.VideoView
import java.io.File

/**
 * 页面背景（图片 / 视频）的统一应用逻辑。
 *
 * ## 为什么背景宿主必须是 [FrameLayout]
 *
 * 视频背景是**加进来的子 View**，必须与页面内容「层叠」——背景在下、内容在上。
 * 只有 FrameLayout 这类允许重叠的容器才能这样叠放。
 *
 * 历史 bug（已修）：早期实现把 VideoView 直接 addView 到页面根布局，于是
 *  1. 根布局是 ScrollView 时（设置页 `activity_settings.xml`）直接抛
 *     `IllegalStateException: ScrollView can host only one direct child` 崩溃；
 *  2. 根布局是竖向 LinearLayout 时（歌曲 / 播放列表页），视频变成**兄弟节点**
 *     而不是背景层，会占满整屏高度把正文挤下去。
 * 现在所有调用点都传入专门的 FrameLayout 宿主，本类再对非 FrameLayout 做兜底，
 * 从结构上杜绝上述两类问题。
 */
object BackgroundHelper {
    /** 视频背景层的 tag，用于重复调用时先摘除旧层 */
    private const val TAG_BG_VIDEO = "bg_video"

    /**
     * @param rootView 背景宿主，应为页面最外层的 FrameLayout
     *                 （见各页 layout 里的 `*_bg_host`）。
     */
    fun applyBackground(context: Context, rootView: View, alphaPercent: Int) {
        // 移除已有的视频背景
        (rootView as? ViewGroup)?.findViewWithTag<View>(TAG_BG_VIDEO)?.let {
            (it as? VideoView)?.stopPlayback()
            rootView.removeView(it)
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
            // 背景宿主必须是允许层叠的容器；否则退化为纯色，绝不 addView
            // （addView 到 ScrollView 会抛异常崩溃，加到 LinearLayout 会挤走正文）。
            val host = rootView as? FrameLayout
            if (host == null) {
                rootView.background = ColorDrawable(0xFF000000.toInt())
                return
            }
            // 宿主铺一层不透明黑底：视频层带 alpha 时（透明度 < 100）
            // 旧背景会从视频后面透出来，而黑底与「无背景」时的兜底色一致，
            // 半透明视频压黑底才是预期的观感。
            host.background = ColorDrawable(0xFF000000.toInt())
            val videoView = VideoView(context).apply {
                tag = TAG_BG_VIDEO
                setVideoPath(finalBgPath)
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mp.start()
                }
                setOnErrorListener { _, _, _ -> true }
                // MATCH_PARENT 由 FrameLayout.LayoutParams 提供：
                // FrameLayout 子 View 默认叠放在左上角，背景层因此铺满且不占位。
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                alpha = alphaPercent / 100f
            }
            // 加到 index 0：位于内容之下，作为真正的背景层
            host.addView(videoView, 0)
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
