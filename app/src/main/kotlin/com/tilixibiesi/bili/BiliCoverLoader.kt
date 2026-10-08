package com.tilixibiesi.bili

import com.tilixibiesi.util.AppExecutors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView

import java.net.HttpURLConnection
import java.net.URL

object BiliCoverLoader {

    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val failed = HashSet<String>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun load(imageView: ImageView, coverUrl: String) {
        if (coverUrl.isEmpty()) {
            imageView.tag = null
            imageView.setImageDrawable(null)
            return
        }
        val url = normalize(coverUrl)
        imageView.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageDrawable(null)
        AppExecutors.io.execute {
            val bitmap = runCatching { download(url) }.getOrNull() ?: return@execute
            cache.put(url, bitmap)
            mainHandler.post {
                if (imageView.tag == url) imageView.setImageBitmap(bitmap)
            }
        }
    }

    fun fetch(coverUrl: String, onDone: (Bitmap?) -> Unit) {
        if (coverUrl.isEmpty()) {
            onDone(null)
            return
        }
        val url = normalize(coverUrl)
        val cached = cache.get(url)
        if (cached != null) {
            onDone(cached)
            return
        }
        if (isFailed(url)) {
            onDone(null)
            return
        }
        AppExecutors.io.execute {
            val bitmap = runCatching { download(url) }.getOrNull()
            if (bitmap != null) cache.put(url, bitmap) else markFailed(url)
            mainHandler.post { onDone(bitmap) }
        }
    }

    private fun isFailed(url: String): Boolean = synchronized(failed) { failed.contains(url) }

    private fun markFailed(url: String) {
        synchronized(failed) { failed.add(url) }
    }

    private fun normalize(original: String): String {
        val fixed = if (original.startsWith("//")) "https:$original" else original
        return if (fixed.contains("@")) fixed else "$fixed@480w.webp"
    }

    private fun download(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
