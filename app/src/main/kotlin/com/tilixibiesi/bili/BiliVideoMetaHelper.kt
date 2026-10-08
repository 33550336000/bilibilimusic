package com.tilixibiesi.bili

import com.tilixibiesi.util.AppExecutors

import android.os.Handler
import android.os.Looper

object BiliVideoMetaHelper {

    data class Meta(
        val coverUrl: String?,
        val collectionTitle: String?
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Meta>()

    fun fetch(bvid: String, onDone: (Meta?) -> Unit) {
        if (bvid.isEmpty()) {
            onDone(null)
            return
        }
        cache[bvid]?.let {
            onDone(it)
            return
        }
        AppExecutors.io.execute {
            val detail = try {
                BiliSearchHelper.getVideoDetail(bvid)
            } catch (_: Exception) {
                null
            }
            val meta = detail?.let {
                Meta(
                    coverUrl = it.coverUrl.takeIf { u -> u.isNotEmpty() },
                    collectionTitle = it.collectionTitle
                )
            }
            if (meta != null) cache[bvid] = meta
            mainHandler.post { onDone(meta) }
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
