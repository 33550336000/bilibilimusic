package com.tilixibiesi.network
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.R
import android.content.Context

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object HttpUtils {
    const val BASE_URL = "https://www.tibao.dpdns.org/"
    private val MP3_PATTERN = Regex("href=\"([^\"]+\\.mp3)\"")

    // 原有方法：加载音乐列表
    fun getMusicListAsync(context: Context, listener: OnMusicListLoadListener?) {
        AppExecutors.io.execute {
            val musicList = mutableListOf<MusicBean>()
            var connection: HttpURLConnection? = null
            var reader: BufferedReader? = null
            try {
                val url = URL(BASE_URL)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")

                if (connection.responseCode == 200) {
                    reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val html = reader.readText()
                    MP3_PATTERN.findAll(html).forEach { match ->
                        val fileName = match.groupValues[1]
                        val decodedName = URLDecoder.decode(fileName, "UTF-8")
                        val fullUrl = BASE_URL + fileName
                        musicList.add(MusicBean(decodedName, fullUrl))
                    }
                    listener?.onSuccess(musicList)
                } else {
                    listener?.onFailed(LanguageUtils.getString(context, R.string.request_failed_code, connection.responseCode))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                listener?.onFailed(LanguageUtils.getString(context, R.string.request_error, e.message))
            } finally {
                try { reader?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    interface OnMusicListLoadListener {
        fun onSuccess(musicList: List<MusicBean>)
        fun onFailed(errorMsg: String)
    }

    /**
     * 通用 GET 请求，用于 B站 API。
     *
     * 超时默认 30 秒，但**允许调用方覆写**：像「解析音频直链」这种处在
     * 「用户点了播放正在等」的交互路径上，30 秒才失败会让人以为应用卡死了，
     * 那里会显式传更短的超时。
     */
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeout: Int = 30000,
        readTimeout: Int = 30000
    ): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val inputStream = conn.inputStream
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            sb.toString()
        } finally {
            conn?.disconnect()
        }
    }
    /**
     * 通用 GET 请求（字节形式），用于弹幕这类可能返回二进制压缩体的接口。
     * 不做任何解压/解码，原样返回，交由调用方按 Content-Encoding 处理。
     *
     * 超时**允许调用方覆写**（与 [get] 一致）：弹幕请求处在「打开视频正在等」的
     * 交互路径上，30 秒才失败会让用户以为播放器卡死，还会白占一个 IO 线程。
     *
     * @param onConnected 连接对象建好后的回调。供调用方持有连接以实现「取消」——
     *        阻塞在 `inputStream` 上的线程只能靠 `disconnect()` 唤醒，
     *        仅仅置一个标志位是无法让请求停下来的。
     */
    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeout: Int = 30000,
        readTimeout: Int = 30000,
        onConnected: ((HttpURLConnection) -> Unit)? = null
    ): ByteArray {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            onConnected?.invoke(conn)
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 带重试的 GET 请求，最多重试 5 次，每次间隔 1 秒
     */
    fun getWithRetry(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxRetries: Int = 5,
        connectTimeout: Int = 30000,
        readTimeout: Int = 30000
    ): String {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                return get(url, headers, connectTimeout, readTimeout)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    Thread.sleep(1000)
                }
            }
        }
        throw lastException ?: RuntimeException("Request failed after $maxRetries attempts")
    }
}
