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

/**
 * 弹幕式上下切换时的「下一条视频」封面加载器。
 *
 * 上下滑动切集需要在拖动过程中就显示目标视频的封面，
 * 而 [BiliVideoGridAdapter] 里的封面缓存是私有的、且按回收列表的 320px 缩略图尺寸取图，
 * 与这里的用途不同，因此单独做一份小缓存。
 *
 * 与列表页保持一致的两点：
 *  - `//` 开头的协议相对 URL 补成 `https:`；
 *  - 未带 `@Nw` 后缀的封面补上宽度参数（B 站图床按该后缀裁剪）。
 */
object BiliCoverLoader {

    /** 约 12MB 上限，足够容纳上一张/下一张两张全屏封面 */
    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 把封面异步加载进 [imageView]。
     *
     * 用 tag 做"这张图还该不该显示"的判据：拖动过程中目标视频会随方向变化，
     * 先发的请求可能后到，不加校验就会把上一张封面盖在新封面上。
     */
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
        // 先清空，避免显示上一个目标视频的封面造成"串图"
        imageView.setImageDrawable(null)
        AppExecutors.io.execute {
            val bitmap = runCatching { download(url) }.getOrNull() ?: return@execute
            cache.put(url, bitmap)
            mainHandler.post {
                if (imageView.tag == url) imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun normalize(original: String): String {
        val fixed = if (original.startsWith("//")) "https:$original" else original
        // 全屏预览比列表缩略图大，取 480w
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
