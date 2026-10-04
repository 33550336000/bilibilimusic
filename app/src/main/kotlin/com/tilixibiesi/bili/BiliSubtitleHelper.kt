package com.tilixibiesi.bili

import com.tilixibiesi.network.HttpUtils
import org.json.JSONObject

/**
 * 查询 B 站视频的字幕轨（含 AI 字幕），并下载解析其内容。
 *
 * ## 只用 `x/player/wbi/v2`，不要用旧的 `x/player/v2`
 *
 * 这是一个**踩过坑的结论**，改动时务必保住：
 *
 *  - `x/player/v2`（旧路径）：同一视频连续请求会返回**互不相同、且内容是错的**字幕。
 *    实测对一个"无字幕"的视频，它能报出 `lan=zh-Hans`、824 条内容为
 *    "本节目包含UNOVE广告"之类的**别的视频的文本**；压力测试 30 次拿到 17 个不同文件。
 *  - `x/player/wbi/v2`（新路径）：**一次请求**就返回该视频全部轨道，
 *    每条都带可用的 `subtitle_url` 与正确的 `ai_type`，且**稳定可复现**
 *    （压测 60/60 成功、始终同一份结果）。与 `view.subtitle.list` 交叉验证 10/10 吻合。
 *
 * 附带确认过的两点：
 *  1. **不需要 WBI 签名**。签名与不签名结果逐字节相同，真正起作用的是路径本身。
 *     所以这里刻意不实现 w_rid/mixin_key 那套——省掉密钥轮换的维护负担。
 *     （勿"顺手补上签名"，那不会让它更正确，只会多一堆会过期的代码。）
 *  2. **必须带登录态**。同一请求不带 Cookie 时轨道数为 0。
 *
 * ## 历史上那套"多次采样合并"为什么删掉了
 *
 * 原实现认为"该接口随机分流 1~2 条，必须采样 32 次拼出完整语种表"，
 * 还配了并发批次、间隔防 412、与 `view` 稳定清单取交集等一整套机制。
 * 那个前提是**错的**：所谓"随机"是旧路径未签名产生的脏数据，不是服务端行为。
 * 换到 `wbi/v2` 后一次请求即可拿全，上述机制全部失去意义。
 */
object BiliSubtitleHelper {

    /** 字幕接口与字幕文件下载的超时（毫秒）。两者都在"用户点了下载/切歌正在等"的路径上 */
    private const val TIMEOUT_MS = 10_000

    /**
     * 请求头。
     *
     * `player/wbi/v2` 必须带登录态（不带时轨道恒为 0），因此 [cookie] 通常都给。
     * 字幕文件本身挂在 CDN 上，Referer/UA 保持与项目其它请求一致即可。
     */
    private fun headers(cookie: String): MutableMap<String, String> {
        val h = mutableMapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        if (cookie.isNotEmpty()) h["Cookie"] = cookie
        return h
    }

    /**
     * 查询视频可用的字幕轨道。**必须在后台线程调用**。
     *
     * 一次请求返回全部轨道（人传字幕与 AI 字幕都在内），每条都带可用的下载 URL，
     * 因此不再需要任何采样/合并/去重逻辑：结果只取决于该视频真的有哪些字幕，
     * 与请求时机无关——这正是"字幕列表每次都不一样"这个老问题的根治。
     *
     * 与旧实现不同，这里**不再参考 `view.subtitle.list`**：那个清单实测会漏掉
     * AI 字幕轨道（`ai_type=1`）与部分 `zh-Hans` 轨道，而 `wbi/v2` 是全集，
     * 前者反而不如后者准。
     *
     * @return 该视频的全部字幕轨；无字幕或请求失败返回空列表（不抛异常）
     */
    fun fetchTracks(bvid: String, cid: Long, cookie: String = ""): List<BiliSubtitleTrack> {
        if (bvid.isEmpty() || cid <= 0L) return emptyList()
        return try {
            val json = HttpUtils.get(
                // 路径必须是 wbi/v2：旧路径 x/player/v2 会返回错误内容（见类注释）
                "https://api.bilibili.com/x/player/wbi/v2?bvid=$bvid&cid=$cid",
                headers(cookie),
                connectTimeout = TIMEOUT_MS,
                readTimeout = TIMEOUT_MS
            )
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) return emptyList()

            val arr = obj.optJSONObject("data")
                ?.optJSONObject("subtitle")
                ?.optJSONArray("subtitles")
                ?: return emptyList()

            val out = ArrayList<BiliSubtitleTrack>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val url = normalizeUrl(s.optString("subtitle_url", ""))
                // 没有 URL 的轨道点了也下不了，直接略过（正常返回里都带 URL）
                if (url.isEmpty()) continue
                val lan = s.optString("lan", "")
                if (lan.isEmpty()) continue
                out.add(
                    BiliSubtitleTrack(
                        lan = lan,
                        lanDoc = s.optString("lan_doc", "").ifEmpty { lan },
                        subtitleUrl = url,
                        id = s.optString("id_str", ""),
                        // ai_type 非 0 即 AI 字幕；lan 前缀是双保险（AI 轨道的 lan 形如 ai-zh）
                        isAi = s.optInt("ai_type", 0) != 0 || lan.startsWith("ai-")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 拉一份字幕 JSON 并解析为时间轴条目。必须在后台线程调用。
     *
     * @param cookie 登录态。AI 字幕与 UP 主上传字幕在未登录时常常返回空 body 或 403，
     *   而轨道列表接口却仍返回轨道信息，表现为"列表里能看到、下载下来却是空的"。
     *   调用方务必带上与 [fetchTracks] 相同的 Cookie。
     */
    fun fetchCues(track: BiliSubtitleTrack, cookie: String = ""): List<BiliSubtitleCue> {
        val body = HttpUtils.get(
            track.subtitleUrl,
            headers(cookie),
            connectTimeout = TIMEOUT_MS,
            readTimeout = TIMEOUT_MS
        )
        val arr = JSONObject(body).optJSONArray("body") ?: return emptyList()
        val cues = ArrayList<BiliSubtitleCue>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            cues.add(
                BiliSubtitleCue(
                    fromSec = o.optDouble("from", 0.0).toFloat(),
                    toSec = o.optDouble("to", 0.0).toFloat(),
                    content = o.optString("content", "")
                )
            )
        }
        cues.sortBy { it.fromSec }
        return cues
    }

    /** 把 B 站字幕 JSON 转成 SRT 文本（通用播放器可读） */
    fun toSrt(cues: List<BiliSubtitleCue>): String {
        val sb = StringBuilder()
        var idx = 1
        for (cue in cues) {
            val content = cue.content.replace("\\N", "\n").replace("\\n", "\n")
            if (content.isBlank()) continue
            sb.append(idx++).append('\n')
                .append(formatSrtTime(cue.fromSec)).append(" --> ").append(formatSrtTime(cue.toSec)).append('\n')
                .append(content).append("\n\n")
        }
        return sb.toString()
    }

    private fun formatSrtTime(secFloat: Float): String {
        val total = (secFloat.coerceAtLeast(0f) * 1000).toLong()
        val h = total / 3_600_000
        val m = (total / 60_000) % 60
        val s = (total / 1000) % 60
        val ms = total % 1000
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    /** 接口返回的 url 可能以 `//` 开头（协议相对），补成 https */
    private fun normalizeUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") -> "https://" + u.removePrefix("http://")
        else -> u
    }
}
