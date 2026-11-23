package com.tilixibiesi.bili

import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.util.AtomicFileWriter
import com.tilixibiesi.util.AppLog

import java.io.File

/**
 * B站搜索类接口的限流状态。
 *
 * 状态只有一个存放处：文本文件
 *
 * ```
 * <存储根>/system/axeron/long/Android/Appdata/cookie/state/state.txt
 *   0   ← [TEXT_OK]       未处于限流
 *   1   ← [TEXT_LIMITED]  已处于限流
 * ```
 *
 * 所有用到 Cookie 的请求（搜索页搜索、主页面刷新、下滑翻页）遇到限流就写 `1`，
 * 搜索成功就写回 `0`；“我的”页进入时直接读它显示账号状态，不再额外发任何探测请求。
 *
 * 20 分钟窗口用文件修改时间判断：最后一次确认限流后超过 [TTL_MS] 未再确认，
 * 即使文件内容还是 `1` 也按正常显示——限流窗口已经过去，
 * 不必等下一次搜索成功才恢复。
 */
object BiliRateLimit {

    private const val TAG = "BiliRateLimit"

    private const val FILE_REL = "system/axeron/long/Android/Appdata/cookie/state/state.txt"

    const val TEXT_OK = "0"

    const val TEXT_LIMITED = "1"

    /** 限流状态有效期：超过这么久没有再确认限流，就当作已经恢复。 */
    private const val TTL_MS = 20 * 60 * 1000L

    /** 限流持续期间刷新时间戳的最小间隔，避免每个失败的分页都写一次磁盘。 */
    private const val REWRITE_INTERVAL_MS = 60 * 1000L

    private fun file(): File = StoragePaths.resolveWrite(FILE_REL)

    private fun read(): String {
        val f = file()
        if (!f.isFile || f.length() <= 0L) return ""
        return runCatching { f.readText() }.getOrDefault("").trim()
    }

    private fun write(text: String) {
        val f = file()
        val ok = AtomicFileWriter.writeText(f, text)
        if (!ok) {
            AppLog.d(TAG, "限流状态写入失败: ${f.absolutePath}")
            return
        }
        // 20 分钟窗口按 mtime 判断，但部分 ROM 上重命名替换不会刷新 mtime（实测只有 ctime 变），
        // 这里显式打一次时间戳，保证“限流期间每次确认都能把窗口往后推”。
        runCatching { f.setLastModified(System.currentTimeMillis()) }
    }

    /** 文件不存在（首次使用或用户手动删除）时就地创建为 `0`，保证它始终可读可见。 */
    private fun ensureFile(): File {
        val f = file()
        if (!f.isFile) write(TEXT_OK)
        return f
    }

    fun isLimited(): Boolean {
        val f = ensureFile()
        // 超过窗口就按正常显示，不依赖任何成功请求来清除。
        if (System.currentTimeMillis() - f.lastModified() >= TTL_MS) return false
        return read() == TEXT_LIMITED
    }

    /** 请求被限流（异常/业务 code 表明受限）：写入 `1` 并刷新时间窗口。 */
    fun markLimited() {
        val f = file()
        val refreshing = f.isFile &&
            System.currentTimeMillis() - f.lastModified() < REWRITE_INTERVAL_MS
        if (refreshing && read() == TEXT_LIMITED) return
        write(TEXT_LIMITED)
    }

    /** 搜索请求成功：限流解除。内容已是 `0` 就不落盘（翻页会频繁走到这里）。 */
    fun markOk() {
        if (read() == TEXT_OK) return
        write(TEXT_OK)
    }

    /** 业务错误码是否属于限流/风控。 */
    fun isRateLimitCode(code: Int): Boolean = code == -412 || code == -799 || code == -352

    /** 请求异常文案是否说明被限流：HttpURLConnection 对 412 等状态码只会给一句 IOException。 */
    fun isRateLimitMessage(message: String?): Boolean {
        if (message.isNullOrEmpty()) return false
        return message.contains("412") || message.contains("403") || message.contains("-412")
    }
}
