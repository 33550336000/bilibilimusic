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
