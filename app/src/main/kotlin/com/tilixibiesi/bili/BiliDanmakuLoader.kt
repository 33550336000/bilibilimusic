package com.tilixibiesi.bili

import com.tilixibiesi.network.HttpUtils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

object BiliDanmakuLoader {

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 10000

    class Request internal constructor() {
        @Volatile
        internal var conn: HttpURLConnection? = null

        @Volatile
        internal var cancelled = false

        fun cancel() {
            cancelled = true
            val c = conn
            conn = null
            runCatching { c?.disconnect() }
        }

        internal fun bind(c: HttpURLConnection) {
            conn = c
            if (cancelled) runCatching { c.disconnect() }
        }
    }

    fun fetch(cid: Long, cookie: String = "", request: Request? = null): List<DanmakuItem>? {
        if (cid <= 0) return null
        val headers = mutableMapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        return try {
            val raw = HttpUtils.getBytes(
                url = "https://comment.bilibili.com/$cid.xml",
                headers = headers,
                connectTimeout = CONNECT_TIMEOUT_MS,
                readTimeout = READ_TIMEOUT_MS,
                onConnected = { request?.bind(it) }
            )
            if (request?.cancelled == true) return null
            val xml = decodeBody(raw)
            BiliDanmakuParser.parse(xml)
        } catch (_: Exception) {
            null
        }
    }

    fun decodeBody(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { inp ->
                    return String(inp.readBytes(), StandardCharsets.UTF_8)
                }
            } catch (_: Exception) {  }
        }
        try {
            val out = ByteArrayOutputStream(bytes.size * 4)
            val inf = Inflater(true)
            try {
                inf.setInput(bytes)
                val buf = ByteArray(8192)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary() || inf.finished()) break
                        throw DataFormatException("no progress")
                    }
                    out.write(buf, 0, n)
                }
            } finally {
                inf.end()
            }
            val text = String(out.toByteArray(), StandardCharsets.UTF_8)
            if (text.isNotBlank()) return text
        } catch (_: Exception) {  }
        return String(bytes, StandardCharsets.UTF_8)
    }
}
