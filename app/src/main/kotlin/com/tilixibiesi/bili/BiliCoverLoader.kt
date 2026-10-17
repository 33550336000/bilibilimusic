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

    /** 解码上限：优先最高画质，同时避免超大原图把内存吃爆。 */
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

    /**
     * 取封面位图。[onDone] 一律在主线程回调：
     * 调用方常直接写在 ImageView 上，而本方法的命中缓存/已失败等快路径
     * 本身就在调用线程同步返回（调用线程可能是 AppExecutors.io），
     * 所以三条快路径也必须投递回主线程，不能直接调。
     */
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

    /**
     * 给 [imageView] 按播放页同一套逻辑填曲目封面。
     *
     * 命中本地封面文件就用本地，否则依次回退 [MusicBean.coverUrl] 与 B 站视频详情里的封面；
     * 都没有（普通音乐）就保持占位图。读盘与网络都在后台线程，回填与 [onBitmap] 都在主线程。
     */
    fun loadForBean(imageView: ImageView, bean: MusicBean?, onBitmap: ((Bitmap?) -> Unit)? = null) {
        val previous = imageView.tag as? MusicBean
        imageView.tag = bean
        // 同一首歌换个 Bean 实例（列表重建会这样做）时保留已显示的封面，别再闪一次占位图
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

    /** 回填位图；[imageView] 期间换了曲目就丢弃这次结果。 */
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

    /**
     * 该曲目此刻是否在播放本地缓存。
     *
     * 判定顺序：`localPath` 指向真实存在的本地文件，或者缓存目录里存在同名音频文件。
     * 本地路径可能因为文件被删而失效，所以必须校验存在性。
     * 会读盘，必须在后台线程调用。
     */
    private fun isPlayingLocalCache(bean: MusicBean): Boolean {
        val path = bean.localPath
        if (!path.isNullOrEmpty()) {
            // 已有明确路径时以它为准（在线地址不算本地缓存）
            return !path.startsWith("http") && File(path).exists()
        }
        return CacheManager.findAudioFile(CacheManager.baseNameOf(bean)) != null
    }

    private fun isFailed(url: String): Boolean = synchronized(failed) { failed.contains(url) }

    private fun markFailed(url: String) {
        synchronized(failed) { failed.add(url) }
    }

    /**
     * 取尽可能最高画质的封面地址。
     *
     * B 站封面通过在地址后追加缩放/转码后缀来降质，例如
     * `@480w.webp`、`@480w_270h.webp`、`@480w_270h_1c.avif`、`@1e_1c.webp`、`@.webp`。
     * 这里把这类后缀去掉，直接使用原图（画质最高的一档）。
     * 只识别已知的缩放/转码后缀，避免误伤地址里其它位置的 `@`。
     */
    fun highestQualityUrl(original: String): String {
        if (original.isEmpty()) return ""
        val fixed = if (original.startsWith("//")) "https:$original" else original
        val at = fixed.lastIndexOf('@')
        if (at <= 0 || at < fixed.lastIndexOf('/')) return fixed
        val suffix = fixed.substring(at + 1)
        return if (suffix.isNotEmpty() && SCALE_SUFFIX.matches(suffix)) fixed.substring(0, at) else fixed
    }

    /** `480w`、`480w_270h`、`480w_270h_1c.webp`、`1e_1c.webp`、`.webp` 等缩放/转码后缀。 */
    private val SCALE_SUFFIX = Regex(
        "^(\\d+w(_\\d+h)?(_\\d+[a-z]+)?|\\d+e(_\\d+c)?)?(\\.(webp|avif|jpg|jpeg|png|gif))?$",
        RegexOption.IGNORE_CASE
    )

    private fun normalize(original: String): String = highestQualityUrl(original)

    /**
     * 读取本地封面文件（`cache/Videocover/` 下按曲名保存的那张）。
     *
     * 找不到或解码失败返回 null，调用方再回退到网络。
     */
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

    /** 本地封面文件路径（不保证存在）。 */
    fun localCoverFile(displayName: String): File =
        File(AppPaths.biliCoverDir(), localCoverName(displayName))

    /** 本地封面文件名：优先沿用已存在的文件，否则按曲名推导。 */
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

    /** 解码原图；只有超过 [MAX_DECODE_DIMENSION] 时按 2 的幂次采样缩小。 */
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
