package com.tilixibiesi.bili

import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.util.AtomicFileWriter
import com.tilixibiesi.util.AppLog

import java.io.File

object BiliRateLimit {

    private const val TAG = "BiliRateLimit"

    private const val FILE_REL = "system/axeron/long/Android/Appdata/cookie/state/state.txt"

    const val TEXT_OK = "0"

    const val TEXT_LIMITED = "1"

    private const val TTL_MS = 20 * 60 * 1000L

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

        runCatching { f.setLastModified(System.currentTimeMillis()) }
    }

    private fun ensureFile(): File {
        val f = file()
        if (!f.isFile) write(TEXT_OK)
        return f
    }

    fun isLimited(): Boolean {
        val f = ensureFile()

        if (System.currentTimeMillis() - f.lastModified() >= TTL_MS) return false
        return read() == TEXT_LIMITED
    }

    fun markLimited() {
        val f = file()
        val refreshing = f.isFile &&
            System.currentTimeMillis() - f.lastModified() < REWRITE_INTERVAL_MS
        if (refreshing && read() == TEXT_LIMITED) return
        write(TEXT_LIMITED)
    }

    fun markOk() {
        if (read() == TEXT_OK) return
        write(TEXT_OK)
    }

    fun isRateLimitCode(code: Int): Boolean = code == -412 || code == -799 || code == -352

    fun isRateLimitMessage(message: String?): Boolean {
        if (message.isNullOrEmpty()) return false
        return message.contains("412") || message.contains("403") || message.contains("-412")
    }
}
