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
import android.util.Log
import android.util.LruCache
import android.widget.ImageView

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object BiliCoverLoader {

    private const val TAG = "BiliCoverLoader"

    /** 解码上限：封面显示尺寸在 1080p 屏上不超过 1080px，2560 会白白多占 5 倍内存。 */
    private const val MAX_DECODE_DIMENSION = 1280

    /** 缓存额度 = 运行时最大内存的 1/16，且不小于 8MB、不大于 32MB（单位 KB）。 */
    private val CACHE_BUDGET_KB: Int = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        (maxKb / 16).coerceIn(8 * 1024, 32 * 1024)
    }

    private val cache = object : LruCache<String, Bitmap>(CACHE_BUDGET_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** 失败集合上限，避免长期运行后无限增长。 */
    private const val MAX_FAILED = 256

    private val failed = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > MAX_FAILED
    }

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
        if (isFailed(url)) return
        AppExecutors.io.execute {
            val bitmap = runCatching { download(url) }
                .onFailure { Log.d(TAG, "封面下载失败 $url: ${it.message}") }
                .getOrNull()
            if (bitmap == null) {
                markFailed(url)
                return@execute
            }
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

    private fun isFailed(url: String): Boolean = synchronized(failed) { failed.containsKey(url) }

    private fun markFailed(url: String) {
        synchronized(failed) { failed[url] = true }
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
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            var longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > MAX_DECODE_DIMENSION) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        } catch (e: Exception) {
            Log.d(TAG, "本地封面解码失败 $displayName: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            Log.d(TAG, "本地封面解码 OOM $displayName: ${e.message}")
            null
        }
    }

    fun localCoverFile(displayName: String): File =
        File(AppPaths.biliCoverDir(), localCoverName(displayName))

    /** 目录扫描结果缓存：列表滚动时 localCoverName 会被高频调用，每次都 listFiles 是明显的 IO 浪费。 */
    private var coverIndex: Map<String, String>? = null

    fun invalidateCoverIndex() {
        coverIndex = null
    }

    fun localCoverName(displayName: String): String {
        val base = AppPaths.sanitizeName(displayName)
        val index = coverIndex ?: buildCoverIndex().also { coverIndex = it }
        return index[base] ?: "$base.jpg"
    }

    private fun buildCoverIndex(): Map<String, String> {
        val dir = AppPaths.biliCoverDir()
        if (!dir.isDirectory) return emptyMap()
        val files = dir.listFiles() ?: return emptyMap()
        val map = HashMap<String, String>(files.size)
        files.forEach { file ->
            if (file.isFile) map.putIfAbsent(file.name.substringBeforeLast('.'), file.name)
        }
        return map
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
