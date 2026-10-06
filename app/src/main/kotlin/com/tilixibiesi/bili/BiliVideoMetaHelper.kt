package com.tilixibiesi.bili

import com.tilixibiesi.util.AppExecutors

import android.os.Handler
import android.os.Looper

/**
 * 按 bvid 补齐「主页面播放时缺失的展示元数据」——封面与专辑（合集）名。
 *
 * ## 为什么需要它
 *
 * 主页面播放 B 站音乐时，服务只拿到一个 [com.tilixibiesi.model.MusicBean]：
 *  - **封面**：历史记录里存了 `coverUrl`，但老记录没有这个字段；
 *    而且从「加入歌单」等入口进来的条目也可能没带封面。
 *  - **专辑名**：`view` 接口的 `ugc_season.title` 才是合集名称，
 *    它从没被写进历史文件，只能现取。
 *
 * 两者都来自同一个 `view` 接口（见 [BiliSearchHelper.getVideoDetail]），
 * 因此合并成一次请求，避免为了封面和专辑名各打一次网络。
 *
 * ## 缓存否定结果
 *
 * 「这个稿件不属于任何合集」是最常见的结果，必须连同「封面取到了没有」
 * 一起缓存住，否则每次进入播放页都要重打一次注定拿不到合集的请求。
 * 用 [ConcurrentHashMap] 会不允许存 null，故整体包成一个不可变对象。
 *
 * 线程：调用方**必须**在后台线程调用（内部是同步 HTTP）；结果在主线程回调。
 */
object BiliVideoMetaHelper {

    /** 一次 `view` 请求能拿到的展示信息。字段可为空表示"确认没有"。 */
    data class Meta(
        val coverUrl: String?,
        val collectionTitle: String?
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 缓存。容量不设上限：条目数与用户实际播放过的 B 站稿件数同阶
     * （通常几十条），每条只有两个短字符串，内存可忽略；
     * 而它带来的收益是"同一首歌反复进入播放页不再发请求"。
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Meta>()

    /**
     * 取元数据。命中缓存时**同步**回调（调用线程），未命中时在 IO 线程取、主线程回调。
     *
     * @param bvid 稿件号；为空时同步回调 null
     * @param onDone 主线程回调；接口失败或稿件不存在时传 null
     */
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
            // 只缓存成功结果：失败可能是断网造成的，不该被永久记住，
            // 否则用户恢复网络后仍拿不到封面。
            if (meta != null) cache[bvid] = meta
            mainHandler.post { onDone(meta) }
        }
    }

    /** 清空缓存（当前无调用方，保留给后续"换号/清数据"等场合） */
    fun clearCache() {
        cache.clear()
    }
}
