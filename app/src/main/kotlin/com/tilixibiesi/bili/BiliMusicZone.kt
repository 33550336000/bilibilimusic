package com.tilixibiesi.bili

import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.network.HttpUtils
import com.tilixibiesi.util.AtomicFileWriter

import com.tilixibiesi.util.AppLog
import org.json.JSONArray
import org.json.JSONObject

object BiliMusicZone {

    private const val TAG = "BiliMusicZone"

    private const val ZONE_FILE_REL =
        "system/axeron/long/Android/Appdata/bilibili/music_zone.json"

    private const val PAGE_SIZE = 20
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000

    private const val PAGES_PER_COMBO = 8

    private val KEYWORDS = arrayOf(
        "VOCALOID", "术力口", "音乐推荐", "听歌", "歌单", "歌曲", "音乐", "电台"
    )

    private val ORDER_BY = arrayOf("", "pubdate", "click", "dm", "stow", "scores")

    private val MUSIC_TIDS = setOf(
        28, 31, 30, 59, 29, 130,
        17, 21, 65, 183, 251, 27, 26, 47,
        172, 242, 137, 265, 250, 138, 252, 171, 243, 216, 193, 201, 126, 236,
        267, 268, 269, 165, 136, 239
    )

    private val COMBO_COUNT = KEYWORDS.size * ORDER_BY.size

    private val MAX_PAGES = COMBO_COUNT * PAGES_PER_COMBO

    private fun cacheFile() = StoragePaths.resolveRead(ZONE_FILE_REL)

    fun isCacheFresh(): Boolean {
        val file = cacheFile()
        return file.exists() &&
            System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS
    }

    fun loadCached(): List<MusicBean> = loadCacheState().first

    fun loadCachedNextPage(): Int = loadCacheState().second

    private fun loadCacheState(): Pair<List<MusicBean>, Int> {
        val file = cacheFile()
        if (!file.exists()) return emptyList<MusicBean>() to 1
        return try {
            val text = file.readText().trim()
            val arr: JSONArray
            var nextPage = 1
            if (text.startsWith("{")) {
                val root = JSONObject(text)
                arr = root.optJSONArray("items") ?: JSONArray()
                nextPage = root.optInt("nextPage", 1).coerceAtLeast(1)
            } else {
                arr = JSONArray(text)
                nextPage = arr.length() / PAGE_SIZE + 1
            }
            val list = ArrayList<MusicBean>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val bvid = obj.optString("bvid", "")
                if (bvid.isEmpty()) continue
                list.add(
                    MusicBean(obj.optString("title", ""), "").apply {
                        isBilibili = true
                        this.bvid = bvid
                        author = obj.optString("author", "").ifEmpty { null }
                        duration = obj.optInt("duration", 0)
                        coverUrl = obj.optString("coverUrl", "").ifEmpty { null }
                    }
                )
            }
            list to nextPage
        } catch (e: Exception) {
            AppLog.d(TAG, "读取音乐列表缓存失败: ${e.message}")
            emptyList<MusicBean>() to 1
        }
    }

    fun fetchPage(pageIndex: Int, cookie: String = ""): List<MusicBean> {
        if (pageIndex <= 0 || pageIndex > MAX_PAGES) return emptyList()
        val headers = if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap()

        val comboIndex = (pageIndex - 1) / PAGES_PER_COMBO
        val pageInCombo = (pageIndex - 1) % PAGES_PER_COMBO + 1
        val kwIndex = comboIndex % KEYWORDS.size
        val orderIndex = comboIndex / KEYWORDS.size
        val keyword = KEYWORDS[kwIndex]
        val order = ORDER_BY[orderIndex.coerceIn(0, ORDER_BY.size - 1)]

        val url = buildString {
            append("https://api.bilibili.com/x/web-interface/search/type")
            append("?search_type=video&keyword=").append(encode(keyword))
            append("&page=").append(pageInCombo)
            append("&page_size=").append(PAGE_SIZE)

            append("&tids=3")
            if (order.isNotEmpty()) append("&order=").append(order)
        }

        val json = try {
            HttpUtils.getWithRetry(
                url,
                headers = headers,
                maxRetries = 2,
                connectTimeout = TIMEOUT_MS,
                readTimeout = TIMEOUT_MS
            )
        } catch (e: Exception) {
            AppLog.d(TAG, "搜索「$keyword/$order」第 $pageInCombo 页请求失败: ${e.message}")
            if (BiliRateLimit.isRateLimitMessage(e.message)) BiliRateLimit.markLimited()
            return emptyList()
        }

        return try {
            val obj = JSONObject(json)
            val code = obj.optInt("code", -1)
            if (code != 0) {
                if (BiliRateLimit.isRateLimitCode(code)) BiliRateLimit.markLimited()
                emptyList()
            } else {

                BiliRateLimit.markOk()
                parseResults(obj.optJSONObject("data")?.optJSONArray("result"))
            }
        } catch (e: Exception) {
            AppLog.d(TAG, "解析「$keyword/$order」第 $pageInCombo 页失败: ${e.message}")
            emptyList()
        }
    }

    private fun parseResults(result: JSONArray?): List<MusicBean> {
        if (result == null || result.length() == 0) return emptyList()
        val list = ArrayList<MusicBean>(result.length())
        for (i in 0 until result.length()) {
            val v = result.optJSONObject(i) ?: continue
            if (v.optString("type") != "video") continue
            val tid = v.optInt("typeid", -1)
            if (tid !in MUSIC_TIDS) continue
            val bvid = v.optString("bvid", "")
            if (bvid.isEmpty()) continue

            if (v.optInt("is_pay", 0) == 1 || v.optInt("badgepay", 0) == 1) continue
            list.add(
                MusicBean(cleanTitle(v.optString("title", "")), "").apply {
                    isBilibili = true
                    this.bvid = bvid
                    this.aid = v.optLong("aid", 0L)
                    author = cleanTitle(v.optString("author", "")).ifEmpty { null }
                    duration = parseDuration(v.optString("duration", ""))
                    coverUrl = normalizeCover(
                        v.optString("pic", "").ifEmpty { v.optString("cover", "") }
                    )
                }
            )
        }
        return list
    }

    private fun cleanTitle(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw.replace(Regex("<[^>]+>"), "")

        s = s.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        return s.trim()
    }

    private fun parseDuration(raw: String): Int {
        if (raw.isEmpty()) return 0
        val parts = raw.split(':')
        return try {
            when (parts.size) {
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                else -> 0
            }
        } catch (e: Exception) {
            AppLog.d(TAG, "解析时长失败: ${e.message}")
            0
        }
    }

    private fun normalizeCover(url: String): String? = when {
        url.isEmpty() -> null
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

    private fun encode(s: String): String =
        try {
            java.net.URLEncoder.encode(s, "UTF-8")
        } catch (e: Exception) {
            AppLog.d(TAG, "URL 编码失败，回退原文: ${e.message}")
            s
        }

    fun saveCache(list: List<MusicBean>, nextPage: Int = 1) {
        if (list.isEmpty()) return
        try {
            val arr = JSONArray()
            for (bean in list) {
                val bvid = bean.bvid?.takeIf { it.isNotEmpty() } ?: continue
                arr.put(
                    JSONObject().apply {
                        put("bvid", bvid)
                        put("title", bean.musicName)
                        put("author", bean.author ?: "")
                        put("duration", bean.duration)
                        put("coverUrl", bean.coverUrl ?: "")
                    }
                )
            }
            val root = JSONObject().apply {
                put("nextPage", nextPage.coerceAtLeast(1))
                put("items", arr)
            }
            val file = StoragePaths.resolveWrite(ZONE_FILE_REL)
            file.parentFile?.mkdirs()
            AtomicFileWriter.writeText(file, root.toString())
        } catch (e: Exception) {
            AppLog.d(TAG, "保存音乐列表缓存失败: ${e.message}")
        }
    }

    fun canFetch(pageIndex: Int): Boolean =
        pageIndex in 1..MAX_PAGES

    fun nextRefreshStart(currentPage: Int): Int {
        val currentCombo = ((currentPage - 1).coerceAtLeast(0)) / PAGES_PER_COMBO

        val onComboStart = (currentPage - 1).coerceAtLeast(0) % PAGES_PER_COMBO == 0
        val target = if (onComboStart) currentCombo else currentCombo + 1
        val wrapped = target % COMBO_COUNT
        return wrapped * PAGES_PER_COMBO + 1
    }
}
