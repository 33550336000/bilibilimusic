package com.tilixibiesi.data
import com.tilixibiesi.util.AppExecutors

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 点击特效 HTML 的下载与本地管理。
 *
 * 文件放在 `<存储根>/system/axeron/long/Android/BA/ba.html`，不打包进 APK，
 * 便于随时更新特效而不必发版。
 *
 * 下载策略与多语言资源一致：**静默后台下载**，失败不影响应用其它功能。
 * 采用「临时文件 + rename」的原子落盘，避免中断留下半截文件。
 */
object ClickFxManager {

    /** 本地特效文件是否已就绪 */
    fun isReady(): Boolean {
        val f = AppPaths.clickFxFile()
        return f.exists() && f.length() > 0
    }

    /**
     * 如未就绪则后台下载一次。
     *
     * @param onDone 下载完成（成功与否）回调，参数表示本地文件是否已可用
     */
    fun ensureDownloaded(onDone: ((ok: Boolean) -> Unit)? = null) {
        if (isReady()) {
            onDone?.invoke(true)
            return
        }
        AppExecutors.io.execute {
            val ok = download()
            onDone?.invoke(ok || isReady())
        }
    }

    /**
     * 执行下载。
     *
     * 必须**手动跟随重定向**：该地址经 Cloudflare，会把 /ba.html 以 307 重定向到 /ba。
     * 若不跟随，responseCode 为 307 且 inputStream 为空，下载必然失败。
     * （HttpURLConnection 的默认 followRedirects 只覆盖 3xx 少数情况且可能因协议降级失效，
     *   这里显式处理最稳妥。）
     */
    private fun download(): Boolean {
        val dir = AppPaths.clickFxDir()
        if (!dir.exists() && !dir.mkdirs()) return false
        val target = AppPaths.clickFxFile()
        val tmp = File(dir, "${AppPaths.CLICK_FX_FILE}.tmp")
        return try {
            if (!fetchTo(AppPaths.CLICK_FX_URL, tmp, mutableSetOf())) {
                tmp.delete()
                return false
            }
            if (tmp.length() > 0 && (tmp.renameTo(target) || tmp.copyTo(target, true).length() > 0)) {
                tmp.delete()
                true
            } else {
                tmp.delete()
                false
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    /** 带重定向跟随的单次抓取；visited 防止重定向成环 */
    private fun fetchTo(urlStr: String, out: File, visited: MutableSet<String>): Boolean {
        if (urlStr in visited) return false
        visited.add(urlStr)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false   // 自行处理，便于追踪每次跳转
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    conn.inputStream.use { input ->
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(8192)
                            var n: Int
                            while (input.read(buf).also { n = it } > 0) fos.write(buf, 0, n)
                        }
                    }
                    true
                }
                // 301/302/303/307/308：跟随 Location
                301, 302, 303, 307, 308 -> {
                    val loc = conn.getHeaderField("Location") ?: return false
                    val next = java.net.URL(java.net.URL(urlStr), loc).toString()
                    conn.disconnect()
                    fetchTo(next, out, visited)
                }
                else -> false
            }
        } finally {
            conn?.disconnect()
        }
    }
}
