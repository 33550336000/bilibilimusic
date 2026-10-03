package com.tilixibiesi.data

import android.content.Context
import java.io.File

/**
 * 应用内各种文件目录/路径的统一出口。
 *
 * 全部基于 [StoragePaths] 当前存储根动态解析，切换存储路径后自动跟随；
 * 集中在此可以避免路径字符串散落到各业务文件里。
 */
object AppPaths {
    /** mp4 下载目录（基于当前存储根目录，切换存储路径后自动跟随） */
    fun mp4DownloadDir(): File = StoragePaths.resolveWrite("system/axeron/long/Android/mp4/")

    /**
     * 点击特效 HTML 的存放目录：`<存储根>/system/axeron/long/Android/BA/`
     *
     * 不放 APK 内的原因：特效是纯装饰资源，希望随时可更新而不必发版；
     * 同时避免约 500KB 的文件常驻安装包。
     */
    fun clickFxDir(): File = StoragePaths.resolveWrite("system/axeron/long/Android/BA/")

    /** 点击特效 HTML 文件名 */
    const val CLICK_FX_FILE = "ba.html"

    /**
     * 字幕下载目录（与视频同根：`<存储根>/system/axeron/long/Android/subtitle/`）。
     *
     * 独立目录的原因：字幕是纯文本文件，文件名与视频同名（`<标题>.srt`），
     * 与 mp4 混放会让"扫描视频"类逻辑误把 .srt 当媒体文件处理。
     */
    fun subtitleDir(): File = StoragePaths.resolveWrite("system/axeron/long/Android/subtitle/")

    /** 点击特效 HTML 的来源地址 */
    const val CLICK_FX_URL = "https://language.tibao.dpdns.org/ba.html"

    /** 点击特效 HTML 的本地文件（可能尚不存在，需先下载） */
    fun clickFxFile(): File = File(clickFxDir(), CLICK_FX_FILE)

    fun getProgressDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "Saveprogress")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}