package com.tilixibiesi.bili
import com.tilixibiesi.model.BiliVideo
import com.tilixibiesi.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class BiliVideoGridAdapter(private val context: Context) : BaseAdapter() {

    private companion object {
        const val TAG = "BiliVideoGridAdapter"
        const val IMAGE_QUEUE_CAPACITY = 64
        const val MAX_DECODE_DIMENSION = 720
        const val RETRY_SLICE_MS = 50L
    }

    @Volatile
    private var released = false

    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()

    /** 网格封面额度：最大内存的 1/16，限制在 8MB~24MB，避免低端机被图片吃掉一半堆。 */
    private val cacheSize = (maxMemoryKb / 16).coerceIn(8 * 1024, 24 * 1024)

    private val coverCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val imageLoadExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        2,
        (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(2),
        30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(IMAGE_QUEUE_CAPACITY),
        ThreadFactory { runnable ->
            Thread(runnable, "bili-cover").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        },
        ThreadPoolExecutor.DiscardPolicy()
    )

    private val handler = Handler(Looper.getMainLooper())
    private val internalList = mutableListOf<BiliVideo>()

    private object FooterTag

    private val inFlight = HashSet<String>()

    private val pendingHolders = HashMap<String, MutableList<WeakReference<ImageView>>>()

    var hasMore: Boolean = false

    fun addData(videos: List<BiliVideo>) {
        internalList.addAll(videos)
        notifyDataSetChanged()
    }

    fun clearData() {
        internalList.clear()
        notifyDataSetChanged()
    }

    fun releaseCovers() {
        coverCache.evictAll()
    }

    fun shutdown() {
        released = true
        synchronized(pendingHolders) {
            inFlight.clear()
            pendingHolders.clear()
        }
        handler.removeCallbacksAndMessages(null)
        imageLoadExecutor.shutdownNow()
    }

    private fun getThumbnailUrl(originalUrl: String, reqWidth: Int): String {
        val fixedUrl = when {
            originalUrl.startsWith("http://") -> originalUrl.replace("http://", "https://")
            originalUrl.startsWith("//") -> "https:$originalUrl"
            else -> originalUrl
        }
        return if (fixedUrl.contains("@")) {
            fixedUrl
        } else {
            "$fixedUrl@${reqWidth}w.webp"
        }
    }

    override fun getCount(): Int = internalList.size + if (hasMore) 1 else 0

    override fun getItem(position: Int): Any {
        if (position < internalList.size) return internalList[position]
        return Any()
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        if (position == internalList.size) {
            if (convertView?.tag === FooterTag) return convertView
            return LayoutInflater.from(context).inflate(R.layout.footer_loading, parent, false).apply {
                tag = FooterTag
            }
        }

        val video = internalList[position]
        val view: View
        val holder: ViewHolder

        if (convertView == null || convertView.tag !is ViewHolder) {
            view = LayoutInflater.from(context).inflate(R.layout.item_bili_video, parent, false)
            holder = ViewHolder(view)
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as ViewHolder
        }

        holder.tvTitle.text = video.title
        holder.tvAuthor.text = video.author
        holder.tvDuration.text = video.duration

        val coverUrl = getThumbnailUrl(video.coverUrl, 320)

        val cachedBitmap = coverCache.get(coverUrl)
        if (cachedBitmap != null) {
            holder.ivCover.tag = coverUrl
            holder.ivCover.setImageBitmap(cachedBitmap)
            return view
        }

        val imageView = holder.ivCover
        imageView.setImageResource(R.drawable.ic_cover_placeholder)
        imageView.tag = coverUrl

        synchronized(pendingHolders) {
            pendingHolders.getOrPut(coverUrl) { mutableListOf() }.add(WeakReference(imageView))
            if (!released && inFlight.add(coverUrl)) {
                try {
                    imageLoadExecutor.execute { loadImageWithRetry(coverUrl, maxRetries = 2) }
                } catch (e: Exception) {
                    // 线程池已关闭（页面销毁）时静默放弃本次加载
                    inFlight.remove(coverUrl)
                    Log.d(TAG, "封面加载任务被拒绝: ${e.message}")
                }
            }
        }

        return view
    }


    /** 按目标尺寸降采样解码，避免把 4K 封面整张读进内存。 */
    private fun decodeCapped(input: java.io.InputStream): Bitmap? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_DECODE_DIMENSION) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun loadImageWithRetry(coverUrl: String, maxRetries: Int) {
        var retries = 0
        while (retries <= maxRetries) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(coverUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.setRequestProperty("Referer", "https://www.bilibili.com/")
                conn.useCaches = true

                if (conn.responseCode in 200..299) {
                    val bitmap = conn.inputStream.use { decodeCapped(it) }
                    if (bitmap != null) {
                        coverCache.put(coverUrl, bitmap)
                        dispatchBitmap(coverUrl, bitmap)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "封面加载失败 $coverUrl: ${e.message}")
            } finally {
                runCatching { conn?.disconnect() }
            }

            if (released) {
                finishInFlight(coverUrl)
                return
            }
            // 分片等待代替整段 Thread.sleep：页面销毁后立刻退出，不会把池线程钉住
            if (retries < maxRetries) {
                var waited = 0L
                val totalWait = 1000L * (retries + 1)
                while (waited < totalWait && !released) {
                    try {
                        Thread.sleep(RETRY_SLICE_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        finishInFlight(coverUrl)
                        return
                    }
                    waited += RETRY_SLICE_MS
                }
                if (released) {
                    finishInFlight(coverUrl)
                    return
                }
            }
            retries++
        }
        finishInFlight(coverUrl)
    }

    private fun dispatchBitmap(coverUrl: String, bitmap: Bitmap) {
        handler.post {
            if (released) return@post
            val refs = synchronized(pendingHolders) {
                inFlight.remove(coverUrl)
                pendingHolders.remove(coverUrl)
            } ?: emptyList()
            for (ref in refs) {
                val imageView = ref.get() ?: continue
                if (imageView.tag == coverUrl) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun finishInFlight(coverUrl: String) {
        synchronized(pendingHolders) {
            inFlight.remove(coverUrl)
            pendingHolders.remove(coverUrl)
        }
    }

    inner class ViewHolder(view: View) {
        val ivCover: ImageView = view.findViewById(R.id.iv_cover)
        val tvDuration: TextView = view.findViewById(R.id.tv_duration_badge)
        val tvTitle: TextView = view.findViewById(R.id.tv_video_title)
        val tvAuthor: TextView = view.findViewById(R.id.tv_video_author)
    }
}
