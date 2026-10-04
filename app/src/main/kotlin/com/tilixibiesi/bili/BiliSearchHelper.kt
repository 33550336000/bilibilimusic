package com.tilixibiesi.bili
import com.tilixibiesi.network.HttpUtils
import com.tilixibiesi.model.BiliVideo

import android.text.Html
import org.json.JSONObject
import java.net.URLEncoder

object BiliSearchHelper {
    /**
     * 解析音频直链的超时（毫秒）。
     *
     * 这条链路处在「用户点了播放、正在等出声」的交互路径上，
     * 沿用 HttpUtils 默认的 30 秒才失败会让人以为应用卡死，因此收紧到 8 秒。
     */
    private const val AUDIO_RESOLVE_TIMEOUT_MS = 8_000

    /** bvid -> cid 缓存：同一稿件的 cid 恒定，不必每次重取 */
    private val cidCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    data class VideoDetail(
        val bvid: String,
        val cid: Long,
        val title: String,
        val coverUrl: String,
        val author: String,
        val duration: Int
    )

    data class QualityOption(
        val quality: Int,
        val description: String,
        val videoUrl: String?,
        val audioUrl: String?
    )

    data class PlayUrlResult(
        val directUrl: String,
        val videoQualities: List<QualityOption>,
        val audioQualities: List<QualityOption>
    )

    data class SearchResult(
        val videos: List<BiliVideo>,
        val hasMore: Boolean,
        val errorCode: Int  // 0 成功，-101 未登录，-1 网络/解析错误
    )

    private fun cleanHtml(text: String): String =
        Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()

    /**
     * 取稿件 cid（播放/弹幕/字幕/歌词都需要的定位标识）。
     *
     * 结果缓存在内存中：同一 bvid 的 cid 恒定，重复请求纯属浪费，
     * 而它处在「点播放」的等待路径上，省一次往返就是省一次可感知的延迟。
     *
     * 对 B 站来说，顶层 cid 就是 P1 的 cid —— 本项目播放的正是它，
     * 因此它也正好对应曲库歌词所关联的那一支单曲（见 [BiliLyricHelper]）。
     */
    fun resolveCid(bvid: String): Long? {
        cidCache[bvid]?.let { return it }
        return try {
            val json = HttpUtils.get(
                "https://api.bilibili.com/x/web-interface/view?bvid=$bvid",
                connectTimeout = AUDIO_RESOLVE_TIMEOUT_MS,
                readTimeout = AUDIO_RESOLVE_TIMEOUT_MS
            )
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) return null
            val cid = obj.optJSONObject("data")?.optLong("cid", 0L) ?: 0L
            if (cid <= 0L) null else cid.also { cidCache[bvid] = it }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 按「音质从低到高」返回该稿件可用的音频直链。
     *
     * 与旧实现的两个关键区别：
     *
     *  1. **不发送登录 Cookie**。B站官方 `playurl` 对公开稿件并不要求登录态，
     *     只要请求带上 Referer 就能取到 `dash.audio` 并直接下载（已实测：
     *     无 Cookie 时返回 206 且可正常拉流）。去掉 Cookie 之后，播放不再依赖
     *     用户登录，也不会因为 Cookie 过期而整条链路失效。
     *
     *  2. **不再使用任何第三方解析站**。第三方站的可用性完全不受本项目控制，
     *     把它当作「能不能播」的前置条件风险过高，因此整体移除；
     *     现在只走官方接口，失败即失败，不做不可靠的兜底。
     *
     * 返回**升序**（最低音质在前）：主页面播放与自动缓存都直接取 `first()`，
     * 即最低音质——按需求「无所谓音质」，优先省流量、起播更快；
     * 万一该链接失效，调用方的重试游标会顺着这份列表逐档升到更高音质。
     *
     * @return 低→高排序的直链；无可用链接时返回空列表
     */
    fun getAudioUrls(bvid: String): List<String> {
        val cid = resolveCid(bvid) ?: return emptyList()
        val url = "https://api.bilibili.com/x/player/playurl" +
            "?bvid=$bvid&cid=$cid&qn=16&fnval=16&fnver=0&fourk=0&otype=json"
        val dashUrls = try {
            // 重试 2 次：偶发 403（无 buvid 时的限流）靠重试即可绕开
            val json = HttpUtils.getWithRetry(
                url,
                headers = emptyMap(),
                maxRetries = 2,
                connectTimeout = AUDIO_RESOLVE_TIMEOUT_MS,
                readTimeout = AUDIO_RESOLVE_TIMEOUT_MS
            )
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) emptyList()
            else {
                val audios = obj.optJSONObject("data")
                    ?.optJSONObject("dash")
                    ?.optJSONArray("audio")
                if (audios == null) emptyList()
                else (0 until audios.length())
                    .map { audios.getJSONObject(it) }
                    .filter { it.optString("baseUrl").isNotEmpty() }
                    // 显式按带宽升序：接口返回顺序并不稳定（实测有升序也有乱序），
                    // 依赖下标取值会时高时低，必须自己排序才能保证「总是最低档」
                    .sortedBy { it.optInt("bandwidth", 0) }
                    .map { it.getString("baseUrl").replace("http://", "https://") }
                    .distinct()
            }
        } catch (e: Exception) {
            emptyList()
        }
        if (dashUrls.isNotEmpty()) return dashUrls

        // 兜底：极老稿件可能没有 DASH 音轨（只有 flv/durl 整段流）。
        // 此时取该 mp4 直链——它自带音轨，能播但流量大，仅作最后手段。
        return try {
            val json = HttpUtils.get(
                "https://api.bilibili.com/x/player/playurl" +
                    "?bvid=$bvid&cid=$cid&qn=16&fnval=1&fnver=0&fourk=0&otype=json",
                connectTimeout = AUDIO_RESOLVE_TIMEOUT_MS,
                readTimeout = AUDIO_RESOLVE_TIMEOUT_MS
            )
            val obj = JSONObject(json)
            val durl = obj.optJSONObject("data")?.optJSONArray("durl")
            if (obj.optInt("code") == 0 && durl != null && durl.length() > 0) {
                listOf(durl.getJSONObject(0).getString("url").replace("http://", "https://"))
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 主页面播放的首选链接：**最低音质**（升序列表的第一个）。
     *
     * @return 直链；解析失败返回 null（调用方据此提示并跳过该曲）
     */
    fun getPreferredAudioUrl(bvid: String): String? = getAudioUrls(bvid).firstOrNull()

    fun searchVideosPage(
        keyword: String,
        page: Int = 1,
        cookie: String = ""
    ): SearchResult {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=$encoded&page=$page&page_size=20"
        return try {
            val headers = if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap()
            val json = HttpUtils.getWithRetry(url, headers)
            val obj = JSONObject(json)
            val code = obj.optInt("code", -1)
            if (code != 0) {
                return SearchResult(emptyList(), false, code)
            }
            val data = obj.getJSONObject("data")
            val numPages = data.optInt("numPages", 0)
            val result = data.getJSONArray("result")
            val list = mutableListOf<BiliVideo>()
            for (i in 0 until result.length()) {
                val item = result.getJSONObject(i)
                if (item.optString("type") != "video") continue

                list.add(BiliVideo(
                    bvid = item.getString("bvid"),
                    title = cleanHtml(item.getString("title")),
                    author = item.getString("author"),
                    coverUrl = item.getString("pic"),
                    duration = item.getString("duration")
                ))
            }
            SearchResult(list, page < numPages, 0)
        } catch (e: Exception) {
            SearchResult(emptyList(), false, -1)
        }
    }

    fun getVideoDetail(bvid: String, cookie: String = ""): VideoDetail? {
        val url = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
        return try {
            val headers = if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap()
            val json = HttpUtils.getWithRetry(url, headers)
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) return null
            val data = obj.getJSONObject("data")
            VideoDetail(
                bvid = data.getString("bvid"),
                cid = data.getLong("cid"),
                title = cleanHtml(data.getString("title")),
                coverUrl = data.getString("pic"),
                author = data.getJSONObject("owner").getString("name"),
                duration = data.getInt("duration")
            )
        } catch (e: Exception) { null }
    }

    fun getPlayUrls(bvid: String, cid: Long, cookie: String = ""): PlayUrlResult? {
        val url = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=127&fnval=4048&fnver=0&fourk=1&otype=json"
        return try {
            val headers = mutableMapOf(
                "Referer" to "https://www.bilibili.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            if (cookie.isNotEmpty()) headers["Cookie"] = cookie
            val json = HttpUtils.getWithRetry(url, headers)
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) return null
            val data = obj.getJSONObject("data")

            val durl = data.optJSONArray("durl")
            val directUrl = if (durl != null && durl.length() > 0)
                durl.getJSONObject(0).getString("url").replace("http://", "https://")
            else ""

            val dash = data.optJSONObject("dash")
            val videoQualities = mutableListOf<QualityOption>()
            val audioQualities = mutableListOf<QualityOption>()

            if (dash != null) {
                val videos = dash.optJSONArray("video")
                if (videos != null) {
                    for (i in 0 until videos.length()) {
                        val v = videos.getJSONObject(i)
                        videoQualities.add(QualityOption(
                            quality = v.optInt("id", 0),
                            description = v.optString("codecs", "") + " " +
                                formatResolution(v.optInt("width", 0), v.optInt("height", 0)),
                            videoUrl = v.optString("baseUrl", "").replace("http://", "https://"),
                            audioUrl = null
                        ))
                    }
                }
                val audios = dash.optJSONArray("audio")
                if (audios != null) {
                    for (i in 0 until audios.length()) {
                        val a = audios.getJSONObject(i)
                        audioQualities.add(QualityOption(
                            quality = a.optInt("id", 0),
                            description = a.optString("codecs", "") +
                                " " + (a.optInt("bandwidth", 0) / 1000).toString() + "kbps",
                            videoUrl = null,
                            audioUrl = a.optString("baseUrl", "").replace("http://", "https://")
                        ))
                    }
                }
            }
            PlayUrlResult(directUrl, videoQualities, audioQualities)
        } catch (e: Exception) { null }
    }


    private fun formatResolution(w: Int, h: Int): String = when {
        h >= 2160 -> "4K"
        h >= 1080 -> "1080P"
        h >= 720 -> "720P"
        h >= 480 -> "480P"
        h >= 360 -> "360P"
        else -> "${h}P"
    }
}
