package com.tilixibiesi.bili

import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.network.HttpUtils
import com.tilixibiesi.util.AtomicFileWriter

import com.tilixibiesi.util.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主页面「音乐」数据源。
 *
 * 用站内搜索接口 `x/web-interface/search/type?search_type=video&keyword=…` 按页取结果，
 * 再用子分区（typeid）白名单过滤出音乐内容。
 *
 * 为什么不用分区接口：
 * - `x/web-interface/newlist` 忽略 pn（各页完全相同）且 ps 上限 50；
 * - `app.bilibili.com/x/v2/feed/index?rid=3` 实测并不按 rid 过滤，混入大量游戏/生活/影视内容；
 * - `x/v2/rank/region` 是固定快照，内容永不变化。
 * 搜索接口反过来：结果按关键词聚合成音乐内容、支持翻页（numPages 可达 50~200）、
 * 且自带 bvid/封面/作者/时长，渲染列表不需要额外请求。
 *
 * 关键词轮换：每个关键词翻到一定页数后切下一个词，保证列表能持续取到新内容。
 */
object BiliMusicZone {

    private const val TAG = "BiliMusicZone"

    private const val ZONE_FILE_REL =
        "system/axeron/long/Android/Appdata/bilibili/music_zone.json"

    private const val PAGE_SIZE = 20
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000

    /**
     * 每个「关键词 × 排序」组合翻多少页就换下一个组合。
     *
     * 实测：同一组合往下翻，每页新增率会衰减；而在 8 页左右切到下一个组合，
     * 新增率仍有约 15 条/页，且切换后立刻是一批完全不同的内容。
     */
    private const val PAGES_PER_COMBO = 8

    /** 轮换关键词，覆盖歌单、电台、日常听歌等不同聚合面。 */
    private val KEYWORDS = arrayOf("音乐推荐", "听歌", "歌单", "歌曲", "音乐", "电台")

    /**
     * 轮换排序方式。空串表示接口默认排序。
     *
     * 实测同一关键词换一个 order，返回结果与默认排序的重复率仅 0~1/20，
     * 相当于凭空多出一整套内容，是扩容的主要手段。
     */
    private val ORDER_BY = arrayOf("", "pubdate", "click", "dm", "stow", "scores")

    /**
     * 音乐相关子分区（老 tid）白名单。
     *
     * 请求里已经带了 `tids=3`（服务端按音乐区过滤），这里再兜一层：
     * 服务端过滤仍会漏进少量非音乐条目，客户端白名单把它们剔掉。
     * 注意 `267 电台` 这类要保留——实测搜索结果是「日推歌单」「后朋电台」等音乐合集，
     * 判成非音乐会误杀大量有效内容。
     */
    private val MUSIC_TIDS = setOf(
        28, 31, 30, 59, 29, 130,          // 原创音乐/翻唱/VOCALOID/音乐现场/演奏/音乐综合
        17, 21, 65, 183, 251, 27, 26, 47, // MV/日常·音乐/音乐/电音/音MAD/同人手书/明星
        172, 242, 137, 265, 250, 138, 252, 171, 243, 216, 193, 201, 126, 236, // 其余音乐子区
        267, 268, 269, 165, 136, 239       // 电台/歌单/鬼畜调教等音乐合集类
    )

    /** 全部「关键词 × 排序」组合数，也就是可用的轮数。 */
    private val COMBO_COUNT = KEYWORDS.size * ORDER_BY.size

    /** 整条分页流的总页数上限。 */
    private val MAX_PAGES = COMBO_COUNT * PAGES_PER_COMBO

    private fun cacheFile() = StoragePaths.resolveRead(ZONE_FILE_REL)

    fun isCacheFresh(): Boolean {
        val file = cacheFile()
        return file.exists() &&
            System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS
    }

    fun loadCached(): List<MusicBean> = loadCacheState().first

    /** 下次该从第几页开始拉（直接取自缓存，不按条目数反推）。 */
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

    /**
     * 拉取第 [pageIndex] 页（从 1 开始）。
     *
     * 页码映射到「第几轮组合 + 组合内第几页」：
     * 组合顺序是 关键词 × 排序 的笛卡尔积，每个组合翻 [PAGES_PER_COMBO] 页。
     * 这样调用方可以把它当成一条超长的连续分页流，翻到边界会自动进入下一个组合，
     * 拿到的是完全不同的一批内容，无需手动调整任何上限。
     */
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
            // 让服务端直接按音乐区过滤，比纯客户端过滤更干净
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
            return emptyList()
        }

        return try {
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) emptyList()
            else parseResults(obj.optJSONObject("data")?.optJSONArray("result"))
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
            // 付费/充电专属内容取不到音频，直接跳过
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

    /**
     * 搜索结果标题既带 `<em class="keyword">` 高亮标签，也带 `&quot;`/`&amp;` 等 HTML 实体，
     * 两种都要还原，否则列表里会直接显示 `&quot;` 这种乱码。
     */
    private fun cleanTitle(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw.replace(Regex("<[^>]+>"), "")
        // 常见实体：先处理 &amp; 之外具名的，最后再解 &
        s = s.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        return s.trim()
    }

    /** `duration` 是 "3:25" / "1:02:33" 形式的字符串。 */
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
            0
        }
    }

    /** 搜索返回的封面可能是 `//i0.hdslb.com/...`，补上协议。 */
    private fun normalizeCover(url: String): String? = when {
        url.isEmpty() -> null
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

    private fun encode(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8") } catch (e: Exception) { s }

    /** 保存缓存；[nextPage] 记住下次该拉第几页（不靠条目数反推）。 */
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

    /** 该页码是否还落在总页数范围内（用于判断「还有没有更多」）。 */
    fun canFetch(pageIndex: Int): Boolean =
        pageIndex in 1..MAX_PAGES

    /**
     * 下拉刷新时的起始页码：跳到「下一个还没被看过的组合」的第 1 页。
     *
     * 搜索同一组合的同一页结果是稳定的，重置到第 1 页会刷出完全一样的内容；
     * 换到下一个组合才能拿到新的一批音乐。
     *
     * 注意不能用 `(currentPage-1)/PAGES_PER_COMBO + 1` 直接推：
     * 首次加载后 currentPage 已经是 2（第 1 页已展示），落在组合 0 内部，
     * 那样第一次刷新会直接跳到组合 1，把组合 0 整个跳过。
     * 这里改为记住「上次刷新到哪个组合」，保证 36 个组合能被均匀轮完。
     */
    fun nextRefreshStart(currentPage: Int): Int {
        val currentCombo = ((currentPage - 1).coerceAtLeast(0)) / PAGES_PER_COMBO
        // 若当前页正好是某个组合的首页，说明该组合还没看过，就留在它上面；
        // 否则前进到下一个组合。
        val onComboStart = (currentPage - 1).coerceAtLeast(0) % PAGES_PER_COMBO == 0
        val target = if (onComboStart) currentCombo else currentCombo + 1
        val wrapped = target % COMBO_COUNT
        return wrapped * PAGES_PER_COMBO + 1
    }
}
