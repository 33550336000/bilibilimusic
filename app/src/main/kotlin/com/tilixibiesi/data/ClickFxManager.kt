package com.tilixibiesi.data
import com.tilixibiesi.util.AppExecutors

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ClickFxManager {

    fun isReady(): Boolean {
        val f = AppPaths.clickFxFile()
        return f.exists() && f.length() > 0
    }

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

    private fun fetchTo(urlStr: String, out: File, visited: MutableSet<String>): Boolean {
        if (urlStr in visited) return false
        visited.add(urlStr)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
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
