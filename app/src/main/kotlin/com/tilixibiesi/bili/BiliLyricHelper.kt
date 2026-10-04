package com.tilixibiesi.bili

import com.tilixibiesi.network.HttpUtils
import org.json.JSONObject

/**
 * 取 B 站视频对应歌曲的歌词，供通知栏显示。
 *
 * ## 两级来源
 *
 *  1. **B 站音乐曲库**（"版权音乐"，即视频下方"发现《XXX》"那个条目）——优先。
 *     带精确时间轴，是"这首歌的歌词"的权威来源：
 *     ```
 *      x/player/wbi/v2?bvid=&cid=             → data.bgm_info.music_id
 *      x/copyright-music-publicity/bgm/detail → data.mv_lyric（LRC 文件 URL）
 *      mv_lyric 指向的静态文件                 → 标准 LRC 文本
 *     ```
 *     不需要登录态，返回的就是 `[mm:ss.xx]` 格式的 LRC。
 *
 *  2. **视频字幕轨**（人传字幕 / AI 字幕）——曲库没有时才用。
 *     音乐稿件的字幕覆盖率不高，但内容确实是本视频在唱的；对翻唱/填词稿件，
 *     AI 字幕往往就是逐句歌词（ASR 会有少量错字）。
 *
 * ## 字幕兜底的前提：必须用 `x/player/wbi/v2`
 *
 * 这里曾发生过一次真实事故：通知栏显示了"测试新款 iPhone"之类与歌曲无关的内容。
 * 排查结论是**接口路径错了**，不是字幕本身不可靠：
 *
 *  - 旧路径 `x/player/v2`：同一视频连续请求会返回**互不相同且内容错误**的字幕。
 *    对一个"无字幕"的视频，它能报出 824 条"本节目包含××广告"的**别的视频文本**；
 *    压力测试 30 次拿到 17 个不同文件。
 *  - 新路径 `x/player/wbi/v2`：一次请求返回全部轨道，稳定可复现
 *    （压测 60/60 成功），与 `view.subtitle.list` 交叉验证 10/10 吻合。
 *  - 且**不需要 WBI 签名**：签名与不签名结果逐字节相同，起作用的是路径本身。
 *  - 但**必须带登录态**：不带 Cookie 时轨道数为 0。
 *
 * 所以"字幕不可靠"是错误结论，真相是调错了接口。详见 [BiliSubtitleHelper]。
 *
 * ## 覆盖率与现实预期
 *
 * 曲库链路实测音乐区排行榜 20 条：14 条有 `music_id`，其中 8 条真正带歌词（40%）。
 * 纯音乐 / 雨声 / 白噪音基本都没有。字幕兜底能补上一部分（尤其翻唱稿件），
 * 但两者都可能落空，因此调用方**必须**接受 null 并回退到「正在播放 / 已暂停」。
 * 本类只负责尽力而为，绝不抛异常。
 */
object BiliLyricHelper {

    /**
     * 歌词文件里标记"歌曲正式开头"与"结束"的两个占位行。
     *
     * B 站前端就是按这两个标记切片的（`u.slice(indexOf(Ut)+11, indexOf(ve))`），
     * 照抄它的行为可以顺带丢掉前面那些 `词：/曲：/编曲：` 的署名行——
     * 那些行虽然也带时间戳，但显示在通知上很怪。
     */
    private const val MARK_SONG_OFFSET = "[song_offset]"
    private const val MARK_END_POINT = "[end_point]"

    /** 主链路（曲库歌词）的超时。不在起播关键路径上，但也不该拖太久 */
    private const val TIMEOUT_MS = 8_000

    /** 最后一句唱完后再停留这么久才退回默认文案（秒），免得尾巴一闪而过 */
    private const val TAIL_HOLD_SEC = 6f

    /**
     * 浏览器 UA —— 与项目其它请求保持一致。
     *
     * 实测 `player/v2` 与 `bgm/detail` 对 UA/Referer 都不敏感（裸请求返回一样），
     * 但歌词静态文件与风控策略随时可能变，带上更稳。
     */
    private val baseHeaders: Map<String, String> = mapOf(
        "Referer" to "https://www.bilibili.com/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    /**
     * 极简 LRU 缓存。
     *
     * 同一个 bvid 的歌词在"暂停→恢复→切回来"时会反复取，
     * 而歌词文件有 3~10KB、解析也有成本，缓存能省掉全部重复网络。
     *
     * 用 [LinkedHashMap] + accessOrder 而不是 ConcurrentHashMap：
     * 后者不允许存 null，而"这个视频没有歌词"恰恰是最常见的结果，
     * 必须把否定结果也缓存住（否则每次切歌都要重打一轮三个接口）。
     *
     * 容量按"一屏能回退几首"取 24，超出后按最久未用淘汰，内存占用可忽略。
     */
    private const val CACHE_CAPACITY = 24
    private val cache = object : LinkedHashMap<String, BiliLyric?>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BiliLyric?>?): Boolean =
            size > CACHE_CAPACITY
    }

    private fun cached(key: String): BiliLyric? = synchronized(cache) { cache[key] }

    private fun hasCache(key: String): Boolean = synchronized(cache) { cache.containsKey(key) }

    private fun putCache(key: String, value: BiliLyric?) {
        synchronized(cache) { cache[key] = value }
    }

    /** 清空缓存（设置页切歌/换号等场合可调用；当前无调用方，保留给后续使用） */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * 取这首歌的歌词。**必须在后台线程调用**（内部是同步 HTTP）。
     *
     * 两级来源，按可靠性排序：
     *
     *  1. **B 站音乐曲库 LRC**（[BiliLyric.Source.MUSIC_LIBRARY]）——带精确时间轴，
     *     是"这首歌的歌词"的权威来源，优先。
     *  2. **视频字幕轨**（[BiliLyric.Source.SUBTITLE]）——曲库没有时才用。
     *     音乐稿件里字幕覆盖率不高，但**内容确实是这个视频在唱/说的**：
     *     AI 字幕是 ASR 产物，对翻唱/填词稿件往往就是逐句歌词（会有少量识别错字）。
     *
     * 字幕兜底**必须走 `x/player/wbi/v2` 并带登录态**——旧路径 `x/player/v2`
     * 会返回完全不相干的其它视频文本（一次真实事故里，通知栏显示成了别人的手机评测解说）。
     * 细节见 [BiliSubtitleHelper] 的类注释。
     *
     * 因此调用方**应当传入 cookie**（登录态）：曲库链路用不到它，
     * 但字幕兜底没有它就是空的。
     *
     * @param bvid        稿件号
     * @param cid         分 P 的 cid。**必须是当前真正在播的那个分 P**：
     *                    B 站曲库的歌词只对应它关联的那一支单曲，
     *                    而本项目播的就是 `view` 接口的顶层 cid（即 P1），两者一致。
     * @param durationSec 视频总时长（秒），仅用于兜底校验。<=0 表示未知，跳过校验。
     * @param cookie      登录态；字幕兜底必需，曲库链路不需要
     * @return 歌词；没有可用歌词时返回 null
     */
    fun fetch(bvid: String, cid: Long, durationSec: Int = 0, cookie: String = ""): BiliLyric? {
        if (bvid.isEmpty() || cid <= 0L) return null
        val key = "$bvid/$cid"
        if (hasCache(key)) return cached(key)

        // 曲库优先：它带的是"官方认定的这首歌的歌词"，时间轴也最准
        val result = fetchFromMusicLibrary(bvid, cid, durationSec)
            ?: fetchFromSubtitle(bvid, cid, cookie)

        putCache(key, result)
        return result
    }

    /**
     * 兜底：把视频字幕轨当作歌词。
     *
     * 只取**人传字幕优先、其次 AI 字幕**的第一条轨道。理由：人传字幕通常是
     * UP 主手打的歌词（质量最高）；AI 字幕是 ASR 产物，可能有错字，但内容仍是本视频的。
     *
     * 注意这里**不会**再出现"取到别的视频内容"的情况——那源于旧接口
     * `x/player/v2` 的脏数据，现在走 `wbi/v2`（见 [BiliSubtitleHelper] 类注释）。
     *
     * 无需时间轴合理性校验：字幕的 `from/to` 是相对本视频的，天然对齐。
     */
    private fun fetchFromSubtitle(bvid: String, cid: Long, cookie: String): BiliLyric? {
        return try {
            val tracks = BiliSubtitleHelper.fetchTracks(bvid, cid, cookie)
            if (tracks.isEmpty()) return null
            // 人传字幕优先；同类里取第一条
            val track = tracks.firstOrNull { !it.isAi } ?: tracks.first()

            val cues = BiliSubtitleHelper.fetchCues(track, cookie)
            if (cues.isEmpty()) return null

            val lines = cues.mapNotNull { cue ->
                // 字幕里常带 \N 换行与多余空白，都要清掉——通知栏只有一行位置
                val text = cue.content
                    .replace("\\N", " ")
                    .replace("\\n", " ")
                    .replace("\n", " ")
                    .trim()
                if (text.isEmpty() || cue.toSec <= cue.fromSec) null
                else LyricLine(cue.fromSec, cue.toSec, text)
            }
            if (lines.isEmpty()) null else BiliLyric(lines, BiliLyric.Source.SUBTITLE)
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 主链路：B 站音乐曲库 ----------

    /**
     * 走「视频 → bgm_info → 曲库详情 → LRC」三步取歌词。
     *
     * 任何一步失败都返回 null（由 [fetch] 决定是否退到字幕轨），
     * 全程不抛异常：通知栏文案绝不该因为一次网络抖动而让服务崩溃。
     */
    private fun fetchFromMusicLibrary(bvid: String, cid: Long, durationSec: Int): BiliLyric? {
        val musicId = try {
            val json = HttpUtils.get(
                // 路径必须是 wbi/v2：旧的 x/player/v2 会返回错误/串台的数据（见 BiliSubtitleHelper 的类注释）
                "https://api.bilibili.com/x/player/wbi/v2?bvid=$bvid&cid=$cid",
                baseHeaders,
                connectTimeout = TIMEOUT_MS,
                readTimeout = TIMEOUT_MS
            )
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) return null
            // 没有 bgm_info 时 B 站返回的可能是 null、也可能整个字段不存在，
            // optJSONObject 两种情况都安全地给出 null
            obj.optJSONObject("data")
                ?.optJSONObject("bgm_info")
                ?.optString("music_id", "")
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } ?: return null

        val lyricUrl = try {
            val json = HttpUtils.get(
                "https://api.bilibili.com/x/copyright-music-publicity/bgm/detail?music_id=$musicId",
                baseHeaders,
                connectTimeout = TIMEOUT_MS,
                readTimeout = TIMEOUT_MS
            )
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) return null
            // 曲目存在但没有配歌词时：mv_lyric 是空串（实测很常见，约占六成）
            obj.optJSONObject("data")
                ?.optString("mv_lyric", "")
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } ?: return null

        val lrc = try {
            // 必须走字节接口：HttpUtils.get 是逐行 readLine 拼接的，
            // 会把换行全部吃掉，而 LRC 完全依赖换行分隔——用它解析出来只会是一行。
            val bytes = HttpUtils.getBytes(
                normalizeUrl(lyricUrl),
                baseHeaders,
                connectTimeout = TIMEOUT_MS,
                readTimeout = TIMEOUT_MS
            )
            // 复用弹幕那边的解码器：它已经处理了 gzip / 裸 deflate / 明文三种情况
            BiliDanmakuLoader.decodeBody(bytes)
        } catch (_: Exception) {
            null
        } ?: return null

        val lines = parseLrc(lrc)
        if (lines.isEmpty()) return null
        if (!timelineLooksSane(lines, durationSec)) return null

        return BiliLyric(lines, BiliLyric.Source.MUSIC_LIBRARY)
    }

    // ---------- LRC 解析 ----------

    /** `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss]`，一次匹配一行里的一个时间戳 */
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** `[ti:xxx]` 之类的元信息标签——没有数字时间戳，解析时要跳过 */
    private val metaTag = Regex("""^\[[a-zA-Z#]+:.*]$""")

    /**
     * 解析 B 站曲库下发的 LRC。
     *
     * 与"通用 LRC 解析器"的三个差别，都是被真实数据逼出来的：
     *
     *  1. **先按 `[song_offset]` / `[end_point]` 切片**。文件里除了歌词，
     *     还混着 `[ti:]/[ar:]/[al:]/[offset:]` 头部、以及 `[kana:...]` 这种
     *     一整行假名注音（实测《恋爱循环》里就有一行上千字符的 kana 数据）。
     *     切片后这些全部落在窗口外，天然被排除。
     *  2. **丢弃空文本的时间戳行**。实测歌词里有大量 `[00:22.36]`（时间戳后无内容）
     *     作为间奏占位符。它们不进 [LyricLine]，于是上一句的结束时间会自然
     *     顺延到下一句开始——间奏期间停留在上一句，而不是文案一闪一闪。
     *  3. **结束时间靠下一句推导**。B 站 LRC 只给"开始时间"，[LyricLine.toSec]
     *     全部由下一句的 fromSec 补出，最后一句则加固定尾长 [TAIL_HOLD_SEC]。
     *
     * @return 按时间升序的歌词；解析不出任何内容时返回空列表
     */
    fun parseLrc(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()

        // 1) 切出正式歌词窗口。标记缺失（或顺序颠倒）时按原文解析，
        //    宁可多出几行署名也不要整首丢掉。
        var body = lrc
        val startIdx = body.indexOf(MARK_SONG_OFFSET)
        if (startIdx >= 0) {
            val endIdx = body.indexOf(MARK_END_POINT, startIdx)
            body = if (endIdx > startIdx) body.substring(startIdx + MARK_SONG_OFFSET.length, endIdx)
            else body.substring(startIdx + MARK_SONG_OFFSET.length)
        }

        // 2) 逐行抽时间戳
        //
        // 时间先按**整数毫秒**存：播放器上报的位置就是整数毫秒，两边用同一套
        // 整数基准换算成秒，才能保证"位置恰好落在句首"时算出的浮点值与
        // 歌词时间戳逐位相等（若在这里用 `分*60 + 秒 + 小数` 做浮点加法，
        // 与 positionMs/1000f 会差出 1 个 ULP，句首那一瞬间会错显示上一句）。
        data class Raw(val ms: Int, val text: String)
        val raw = ArrayList<Raw>(128)
        for (line in body.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || metaTag.matches(trimmed)) continue

            val matches = timeTag.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            // 文本 = 最后一个时间戳之后的内容
            val text = trimmed.substring(matches.last().range.last + 1).trim()
            // 时间戳后没有内容 = 间奏占位符，丢弃（见上面第 2 点）
            if (text.isEmpty()) continue

            // 一行多个时间戳（`[00:01.00][00:30.00]同一句`）表示这句重复出现，
            // 每个时间戳都算一句，因此在这里展开
            for (m in matches) {
                val min = m.groupValues[1].toIntOrNull() ?: continue
                val sec = m.groupValues[2].toIntOrNull() ?: continue
                val fracMs = when (m.groupValues[3].length) {
                    0 -> 0
                    // 1 位 = 十分之一秒，2 位 = 百分之一秒，3 位以上 = 毫秒（多余精度对通知栏无意义）
                    1 -> (m.groupValues[3].toIntOrNull() ?: 0) * 100
                    2 -> (m.groupValues[3].toIntOrNull() ?: 0) * 10
                    else -> m.groupValues[3].take(3).toIntOrNull() ?: 0
                }
                raw.add(Raw(min * 60_000 + sec * 1_000 + fracMs, text))
            }
        }
        if (raw.isEmpty()) return emptyList()

        // 3) 排序（LRC 理论上有序，但重复句展开后可能乱），再补齐结束时间
        raw.sortBy { it.ms }
        val lines = ArrayList<LyricLine>(raw.size)
        for (i in raw.indices) {
            val cur = raw[i]
            val next = raw.getOrNull(i + 1)
            val toMs = when {
                // 正常情况：唱到下一句开始
                next != null -> next.ms
                // 最后一句：没有下一句可参照，给一个固定尾长
                else -> cur.ms + (TAIL_HOLD_SEC * 1000).toInt()
            }
            // 兜底：同一时间戳重复出现时 to 会等于 from，保证区间非负
            lines.add(LyricLine(cur.ms / 1000f, maxOf(toMs, cur.ms) / 1000f, cur.text))
        }
        return lines
    }

    // ---------- 工具 ----------

    /**
     * 时间轴兜底校验：歌词比视频长出太多时判为"不是这个视频的歌词"。
     *
     * 典型场景：视频是稍短的重新剪辑版，而曲库那条对应完整版，末句会明显越界。
     * 容差给到 120 秒，是因为 B 站 LRC 常把最后一句之后还挂着几行署名/淡出文案，
     * 而且同一首歌的现场版与录音室版时长本来就差一截，卡太紧会误杀。
     *
     * `durationSec <= 0`（时长未知）时不做判断——宁可显示也不误丢。
     */
    private fun timelineLooksSane(lines: List<LyricLine>, durationSec: Int): Boolean {
        if (durationSec <= 0) return true
        val last = lines.last().fromSec
        return last <= durationSec + 120f
    }

    /** 接口可能返回 `//host/path` 或 `http://host/path`，统一补成 https */
    private fun normalizeUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") -> "https://" + u.removePrefix("http://")
        else -> u
    }
}
