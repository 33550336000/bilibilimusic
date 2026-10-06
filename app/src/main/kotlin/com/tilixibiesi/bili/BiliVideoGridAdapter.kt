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

    // ---------- 内存缓存 ----------
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val coverCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    // 线程池：根据 CPU 核心数动态调整
    private val imageLoadExecutor = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(2)
    )

    private val handler = Handler(Looper.getMainLooper())
    private val internalList = mutableListOf<BiliVideo>()

    /** footer 专用 tag，避免普通 item 与 footer 互相复用。 */
    private object FooterTag

    /** 正在请求的封面 URL；同一 URL 只发一次网络请求。由 pendingHolders 作为锁保护。 */
    private val inFlight = HashSet<String>()

    /** 同一 URL 等待回填的 ImageView 弱引用，完成后一次性更新所有可见项。 */
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

    /**
     * 释放全部封面位图内存。
     *
     * 封面缓存按堆上限的 1/8 开（256MB 堆即 32MB、512MB 堆即 64MB），
     * 单 Activity 常驻 4 页后这块内存**永远不会被回收**——旧的多 Activity 架构里
     * 搜索页退到后台就整体销毁，现在不会了，于是它从"临时占用"变成了"常驻占用"。
     * 用户离开搜索页时封面已不再需要，这里主动 evictAll() 交还给分配器。
     *
     * 与 [clearData] 的区别：clearData 只清列表项（数据），不动位图（内存）。
     * 回到搜索页时会重新拉取、重新解码，因此清掉不影响功能。
     */
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

    // ---------- 缩略图 URL 生成 ----------
    /**
     * 生成带有缩略图参数的 URL，限制宽度、等比缩放、居中裁剪并转为 WebP。
     * 若原始 URL 已包含 "@"，则直接返回原 URL（避免重复添加）。
     */
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

    // ---------- BaseAdapter 实现 ----------
    override fun getCount(): Int = internalList.size + if (hasMore) 1 else 0

    override fun getItem(position: Int): Any {
        if (position < internalList.size) return internalList[position]
        return Any()
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        // footer 视图：只复用 footer 自己，避免普通 item 被当成 footer 显示
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

        // 绑定文字
        holder.tvTitle.text = video.title
        holder.tvAuthor.text = video.author
        holder.tvDuration.text = video.duration

        // 生成缩略图 URL（宽度 320px）
        val coverUrl = getThumbnailUrl(video.coverUrl, 320)

        // 1. 内存缓存；tag 也要更新，避免旧 URL 的异步回调覆盖这张图
        val cachedBitmap = coverCache.get(coverUrl)
        if (cachedBitmap != null) {
            holder.ivCover.tag = coverUrl
            holder.ivCover.setImageBitmap(cachedBitmap)
            return view
        }

        // 2. 异步加载：同一 URL 去重，多个复用的 item 共用同一次请求结果
        val imageView = holder.ivCover
        // 占位图与「正在播放页拿不到封面时」用的是同一张（见 ic_cover_placeholder），
        // 加载中与加载失败都会停在这张图上。
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

    // ---------- 带重试的图片加载 ----------

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
                // 允许 HTTP 层缓存（若系统/ROM 提供缓存实现）
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
                // 忽略，继续重试
            } finally {
                conn?.disconnect()
            }

            // 重试前等待（指数退避）
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
        // 所有重试失败：清理等待队列并保留占位图
        finishInFlight(coverUrl)
    }

    /** 主线程统一回填所有等待该 URL 的 ImageView，并清理 in-flight 标记。 */
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

    // ---------- ViewHolder ----------
    inner class ViewHolder(view: View) {
        val ivCover: ImageView = view.findViewById(R.id.iv_cover)
        val tvDuration: TextView = view.findViewById(R.id.tv_duration_badge)
        val tvTitle: TextView = view.findViewById(R.id.tv_video_title)
        val tvAuthor: TextView = view.findViewById(R.id.tv_video_author)
    }
}
