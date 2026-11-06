package com.tilixibiesi.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把本次运行留存在内存里的日志落成文件。
 *
 * 早期实现是拉起 /system/bin/logcat 子进程读本进程日志，但在多机型上并不可靠：
 * 实测同一时刻 root 能读十几万行、应用 uid 只读到数行且不含当前进程、--pid 在
 * 非特权身份下会把结果过滤成空。现在改为从 [AppLog] 的内存环形缓冲取，
 * 不依赖 logcat、SELinux 策略与 root，且内容恰好就是"本次运行"。
 *
 * 输出目录放应用外部私有目录（getExternalFilesDir），而不是 filesDir/cacheDir：
 * 本项目的 WebViewMetricsCleaner 在退到后台时会递归清空 dataDir 与 cacheDir。
 */
object LogExporter {

    private const val LOG_DIR = "logs"

    private const val FILE_PREFIX = "logcat-"

    fun logDir(context: android.content.Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_DIR)

    fun export(context: android.content.Context): File? {
        val dir = logDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return null

        val target = File(
            dir,
            FILE_PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        )
        return if (AppLog.dumpTo(target)) target else null
    }
}
