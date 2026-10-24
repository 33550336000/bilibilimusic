package com.tilixibiesi.bili
import com.tilixibiesi.network.HttpUtils
import com.tilixibiesi.model.BiliVideo

import android.text.Html
import org.json.JSONObject
import java.net.URLEncoder

object BiliSearchHelper {
    private const val AUDIO_RESOLVE_TIMEOUT_MS = 8_000

    private val cidCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    data class VideoDetail(
        val bvid: String,
        val cid: Long,
        val title: String,
        val coverUrl: String,
        val author: String,
        val duration: Int,
        val collectionTitle: String? = null
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
        val errorCode: Int
    )

    private fun cleanHtml(text: String): String =
        Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()

    fun resolveCid(bvid: String, cookie: String = ""): Long? {
        cidCache[bvid]?.let { return it }
        return try {
            val json = HttpUtils.get(
                "https://api.bilibili.com/x/web-interface/view?bvid=$bvid",
                if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap(),
                connectTimeout = AUDIO_RESOLVE_TIMEOUT_MS,
                readTimeout = AUDIO_RESOLVE_TIMEOUT_MS
            )
            val obj = JSONObject(json)
            val code = obj.optInt("code")
            BiliLoginHelper.handleApiCode(code, cookie)
            if (code != 0) return null
            val cid = obj.optJSONObject("data")?.optLong("cid", 0L) ?: 0L
            if (cid <= 0L) null else cid.also { cidCache[bvid] = it }
        } catch (e: Exception) {
            null
        }
    }

    fun getAudioUrlsByQuality(
        bvid: String,
        cookie: String = "",
        preferredId: Int = BiliAudioQuality.DEFAULT.id
    ): List<String> {
        val cid = resolveCid(bvid, cookie) ?: return emptyList()
        val headers = if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap()
        val dashUrls = try {
            val json = HttpUtils.getWithRetry(
                "https://api.bilibili.com/x/player/playurl" +
                    "?bvid=$bvid&cid=$cid&qn=16&fnval=16&fnver=0&fourk=0&otype=json",
                headers = headers,
                maxRetries = 2,
                connectTimeout = AUDIO_RESOLVE_TIMEOUT_MS,
                readTimeout = AUDIO_RESOLVE_TIMEOUT_MS
            )
            val obj = JSONObject(json)
            val code = obj.optInt("code")
            BiliLoginHelper.handleApiCode(code, cookie)
            if (code != 0) emptyList()
            else {
                val audios = obj.optJSONObject("data")
                    ?.optJSONObject("dash")
                    ?.optJSONArray("audio")
                if (audios == null) emptyList()
                else {
                    val byId = LinkedHashMap<Int, String>()
                    for (i in 0 until audios.length()) {
                        val a = audios.getJSONObject(i)
                        val id = a.optInt("id", 0)
                        val base = a.optString("baseUrl", "")
                        if (id == 0 || base.isEmpty() || byId.containsKey(id)) continue
                        byId[id] = normalizeUrl(base)
                    }
                    BiliAudioQuality.fallbackOrder(byId.keys.toList(), preferredId)
                        .mapNotNull { byId[it] }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        if (dashUrls.isNotEmpty()) return dashUrls.distinct()

        return try {
            val json = HttpUtils.get(
                "https://api.bilibili.com/x/player/playurl" +
                    "?bvid=$bvid&cid=$cid&qn=16&fnval=1&fnver=0&fourk=0&otype=json",
                headers,
                connectTimeout = AUDIO_RESOLVE_TIMEOUT_MS,
                readTimeout = AUDIO_RESOLVE_TIMEOUT_MS
            )
            val obj = JSONObject(json)
            val code = obj.optInt("code")
            BiliLoginHelper.handleApiCode(code, cookie)
            val durl = obj.optJSONObject("data")?.optJSONArray("durl")
            if (code == 0 && durl != null && durl.length() > 0) {
                listOf(normalizeUrl(durl.getJSONObject(0).getString("url")))
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun normalizeUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") -> "https://" + u.removePrefix("http://")
        else -> u
    }

    fun getAudioUrls(bvid: String, cookie: String = ""): List<String> =
        getAudioUrlsByQuality(bvid, cookie)

    fun getPreferredAudioUrl(bvid: String, cookie: String = ""): String? =
        getAudioUrls(bvid, cookie).firstOrNull()

    fun getPreferredAudioUrl(bvid: String, cookie: String, preferredId: Int): String? =
        getAudioUrlsByQuality(bvid, cookie, preferredId).firstOrNull()

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
            BiliLoginHelper.handleApiCode(code, cookie)
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
            val code = obj.optInt("code", -1)
            BiliLoginHelper.handleApiCode(code, cookie)
            if (code != 0) return null
            val data = obj.getJSONObject("data")
            VideoDetail(
                bvid = data.getString("bvid"),
                cid = data.getLong("cid"),
                title = cleanHtml(data.getString("title")),
                coverUrl = data.getString("pic"),
                author = data.getJSONObject("owner").getString("name"),
                duration = data.getInt("duration"),
                collectionTitle = data.optJSONObject("ugc_season")
                    ?.optString("title", "")
                    ?.takeIf { it.isNotEmpty() }
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
            val code = obj.optInt("code", -1)
            BiliLoginHelper.handleApiCode(code, cookie)
            if (code != 0) return null
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
