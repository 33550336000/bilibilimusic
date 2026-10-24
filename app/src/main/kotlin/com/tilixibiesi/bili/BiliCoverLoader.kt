package com.tilixibiesi.bili

import com.tilixibiesi.R
import com.tilixibiesi.data.AppPaths
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.CacheManager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object BiliCoverLoader {

    private const val MAX_DECODE_DIMENSION = 2560

    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
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
            mainHandler.post { onDone(null) }
            return
        }
        val url = normalize(coverUrl)
        val cached = cache.get(url)
        if (cached != null) {
            mainHandler.post { onDone(cached) }
            return
        }
        if (isFailed(url)) {
            mainHandler.post { onDone(null) }
            return
        }
        AppExecutors.io.execute {
            val bitmap = runCatching { download(url) }.getOrNull()
            if (bitmap != null) cache.put(url, bitmap) else markFailed(url)
            mainHandler.post { onDone(bitmap) }
        }
    }

    fun loadForBean(imageView: ImageView, bean: MusicBean?, onBitmap: ((Bitmap?) -> Unit)? = null) {
        val previous = imageView.tag as? MusicBean
        imageView.tag = bean
        if (previous != bean) imageView.setImageResource(R.drawable.ic_cover_placeholder)
        if (bean == null) {
            onBitmap?.invoke(null)
            return
        }
        val baseName = CacheManager.baseNameOf(bean)
        AppExecutors.io.execute {
            val local = if (isPlayingLocalCache(bean)) loadLocalFile(baseName) else null
            val direct = bean.coverUrl?.takeIf { it.isNotEmpty() }
            val bvid = bean.bvid?.takeIf { it.isNotEmpty() }
            when {
                local != null -> mainHandler.post { applyCover(imageView, bean, local, onBitmap) }
                direct != null -> fetch(direct) { applyCover(imageView, bean, it, onBitmap) }
                bvid == null -> mainHandler.post { applyCover(imageView, bean, null, onBitmap) }
                else -> BiliVideoMetaHelper.fetch(bvid) { meta ->
                    val url = meta?.coverUrl
                    if (url.isNullOrEmpty()) {
                        applyCover(imageView, bean, null, onBitmap)
                    } else {
                        bean.coverUrl = url
                        fetch(url) { applyCover(imageView, bean, it, onBitmap) }
                    }
                }
            }
        }
    }

    private fun applyCover(
        imageView: ImageView,
        bean: MusicBean,
        bitmap: Bitmap?,
        onBitmap: ((Bitmap?) -> Unit)?
    ) {
        if (imageView.tag !== bean) return
        if (bitmap == null) imageView.setImageResource(R.drawable.ic_cover_placeholder)
        else imageView.setImageBitmap(bitmap)
        onBitmap?.invoke(bitmap)
    }

    private fun isPlayingLocalCache(bean: MusicBean): Boolean {
        val path = bean.localPath
        if (!path.isNullOrEmpty()) {
            return !path.startsWith("http") && File(path).exists()
        }
        return CacheManager.findAudioFile(CacheManager.baseNameOf(bean)) != null
    }

    private fun isFailed(url: String): Boolean = synchronized(failed) { failed.contains(url) }

    private fun markFailed(url: String) {
        synchronized(failed) { failed.add(url) }
    }

    fun highestQualityUrl(original: String): String {
        if (original.isEmpty()) return ""
        val fixed = if (original.startsWith("//")) "https:$original" else original
        val at = fixed.lastIndexOf('@')
        if (at <= 0 || at < fixed.lastIndexOf('/')) return fixed
        val suffix = fixed.substring(at + 1)
        return if (suffix.isNotEmpty() && SCALE_SUFFIX.matches(suffix)) fixed.substring(0, at) else fixed
    }

    private val SCALE_SUFFIX = Regex(
        "^(\\d+w(_\\d+h)?(_\\d+[a-z]+)?|\\d+e(_\\d+c)?)?(\\.(webp|avif|jpg|jpeg|png|gif))?$",
        RegexOption.IGNORE_CASE
    )

    private fun normalize(original: String): String = highestQualityUrl(original)

    fun loadLocalFile(displayName: String): Bitmap? {
        val file = localCoverFile(displayName)
        if (!file.isFile || file.length() <= 0) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    fun localCoverFile(displayName: String): File =
        File(AppPaths.biliCoverDir(), localCoverName(displayName))

    fun localCoverName(displayName: String): String {
        val dir = AppPaths.biliCoverDir()
        val base = AppPaths.sanitizeName(displayName)
        if (dir.isDirectory) {
            dir.listFiles()?.firstOrNull { it.isFile && it.name.substringBeforeLast('.') == base }
                ?.let { return it.name }
        }
        return "$base.jpg"
    }

    private fun download(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            val bytes = conn.inputStream.use { input ->
                ByteArrayOutputStream().use { out ->
                    input.copyTo(out)
                    out.toByteArray()
                }
            }
            decodeCapped(bytes)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun decodeCapped(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_DECODE_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
