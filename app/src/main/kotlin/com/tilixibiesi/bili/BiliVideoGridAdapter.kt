package com.tilixibiesi.bili
import com.tilixibiesi.model.BiliVideo
import com.tilixibiesi.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

class BiliVideoGridAdapter(private val context: Context) : BaseAdapter() {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val coverCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val imageLoadExecutor = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(2)
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
        synchronized(pendingHolders) {
            inFlight.clear()
            pendingHolders.clear()
        }
        imageLoadExecutor.shutdown()
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
            if (inFlight.add(coverUrl)) {
                imageLoadExecutor.execute {
                    loadImageWithRetry(coverUrl, maxRetries = 2)
                }
            }
        }

        return view
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
                    val bitmap = BitmapFactory.decodeStream(conn.inputStream)
                    if (bitmap != null) {
                        coverCache.put(coverUrl, bitmap)
                        dispatchBitmap(coverUrl, bitmap)
                        return
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }

            if (retries < maxRetries) {
                try {
                    Thread.sleep(1000L * (retries + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
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
            val refs = synchronized(pendingHolders) {
                inFlight.remove(coverUrl)
                pendingHolders.remove(coverUrl)
            } ?: return@post
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
