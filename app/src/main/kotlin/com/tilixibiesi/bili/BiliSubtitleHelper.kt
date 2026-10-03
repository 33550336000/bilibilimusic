package com.tilixibiesi.bili

import com.tilixibiesi.network.HttpUtils
import org.json.JSONObject

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object BiliSubtitleHelper {

    /**
     * 采样 player/v2 的总次数上限。
     *
     * 该接口每次只返回**随机 1~2 条**字幕（服务端按 cid 做灰度/分流），
     * 必须多打几次并合并，才能凑齐完整语种表。
     * 凑齐即提前返回，此上限只是兜底，防止异常视频把弹窗卡死。
     */
    private const val SAMPLE_LIMIT = 32

    /** 每批并发的采样数：串行跑 30 次要十几秒，并发能把它压到 2~3 秒 */
    private const val BATCH_SIZE = 4

    /** 批次之间的间隔：太密会被风控（412） */
    private const val SAMPLE_INTERVAL_MS = 200L

    private fun playerHeaders(cookie: String): MutableMap<String, String> {
        val headers = mutableMapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        return headers
    }

    /**
     * 取稳定的语种清单：`x/web-interface/view` 的 `data.subtitle.list`。
     *
     * 与 player/v2 不同，这个接口**每次返回都一样**（实测 8/8 一致），
     * 是唯一能作为"该视频到底有几种字幕"依据的来源。
     * 代价是 `subtitle_url` 恒为空字符串，不能拿来下载。
     * AI 字幕不在这个清单里（ai_status != 2 的半成品也不会出现）。
     */
    private fun fetchStableList(bvid: String, cookie: String): List<BiliSubtitleTrack> {
        return try {
            val json = HttpUtils.get(
                "https://api.bilibili.com/x/web-interface/view?bvid=$bvid",
                playerHeaders(cookie)
            )
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) return emptyList()
            val arr = obj.optJSONObject("data")
                ?.optJSONObject("subtitle")
                ?.optJSONArray("list")
                ?: return emptyList()
            val out = ArrayList<BiliSubtitleTrack>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val lan = s.optString("lan", "")
                if (lan.isEmpty()) continue
                out.add(
                    BiliSubtitleTrack(
                        lan = lan,
                        lanDoc = s.optString("lan_doc", "").ifEmpty { lan },
                        subtitleUrl = "",
                        id = s.optString("id_str", ""),
                        isAi = s.optInt("ai_type", 0) != 0
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 采样一次 player/v2，返回本次**带 URL** 的轨道（通常只有 1~2 条） */
    private fun fetchSample(bvid: String, cid: Long, cookie: String): List<BiliSubtitleTrack> {
        return try {
            val json = HttpUtils.get(
                "https://api.bilibili.com/x/player/v2?bvid=$bvid&cid=$cid",
                playerHeaders(cookie)
            )
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) return emptyList()
            val subs = obj.optJSONObject("data")
                ?.optJSONObject("subtitle")
                ?.optJSONArray("subtitles")
                ?: return emptyList()
            val out = ArrayList<BiliSubtitleTrack>(subs.length())
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                val url = normalizeUrl(s.optString("subtitle_url", ""))
                // 大部分轨道的 url 是空的，只有被"抽中"的那 1~2 条带 url
                if (url.isEmpty()) continue
                val lan = s.optString("lan", "")
                if (lan.isEmpty()) continue
                val isAi = s.optInt("ai_type", 0) != 0 || lan.startsWith("ai-")
                out.add(
                    BiliSubtitleTrack(
                        lan = lan,
                        lanDoc = s.optString("lan_doc", "").ifEmpty { lan },
                        subtitleUrl = url,
                        id = s.optString("id_str", ""),
                        isAi = isAi
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 把采样结果并入 [acc]。
     *
     * 同一个 lan 可能对应多条轨道（人传 "zh" 与 AI "ai-zh" 的 lan_doc 都是「中文」），
     * 因此以 **lan + 是否 AI** 为键去重，而不是只按 lan。
     */
    private fun mergeInto(acc: ConcurrentHashMap<String, BiliSubtitleTrack>, tracks: List<BiliSubtitleTrack>) {
        for (t in tracks) acc.putIfAbsent("${t.lan}|${t.isAi}", t)
    }

    /** 并发跑一批采样并合并结果；单个请求失败不影响其余 */
    private fun sampleBatch(
        bvid: String,
        cid: Long,
        cookie: String,
        size: Int,
        acc: ConcurrentHashMap<String, BiliSubtitleTrack>
    ) {
        if (size <= 1) {
            mergeInto(acc, fetchSample(bvid, cid, cookie))
            return
        }
        val latch = CountDownLatch(size)
        for (i in 0 until size) {
            Thread {
                try {
                    mergeInto(acc, fetchSample(bvid, cid, cookie))
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        try {
            latch.await(20, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { /* 超时也继续用已拿到的部分 */ }
    }

    private fun pauseBetweenBatches() {
        try {
            Thread.sleep(SAMPLE_INTERVAL_MS)
        } catch (_: InterruptedException) { /* 忽略 */ }
    }

    /**
     * 查询视频可用的字幕轨道。必须在后台线程调用。
     *
     * **为什么不直接用 player/v2：** 该接口对字幕做了随机分流，
     * 同一视频连续请求会分别返回「只有中文」「只有英文」「中文+英文」「一条都没有」，
     * 于是下载弹窗里的字幕选项就跟着时多时少、甚至显示"无字幕资源"——
     * 这正是"字幕显示每次都不一样"的根因。
     *
     * **现在的做法：**
     *  1. 先用稳定的 `view` 接口拿到完整语种清单，确定"有几种字幕"；
     *  2. 再对 player/v2 采样若干次，只为把每种语言对应的 `subtitle_url` 补齐；
     *  3. 以稳定清单为准输出，保证**每次弹窗看到的语种数量完全一致**。
     *
     * 稳定清单为空时（如纯 AI 字幕视频），退化为"只用采样结果"，
     * 此时至少保证每条选项都带可用 URL、点了能下载成功。
     *
     * @return 已拿到下载链接的轨道；稳定清单里有但始终没采样到 URL 的会被丢弃（点了也下不了）
     */
    fun fetchTracks(bvid: String, cid: Long, cookie: String = ""): List<BiliSubtitleTrack> {
        if (cid <= 0) return emptyList()

        val stable = fetchStableList(bvid, cookie)
        val found = ConcurrentHashMap<String, BiliSubtitleTrack>()

        if (stable.isNotEmpty()) {
            // 稳定清单非空：目标是给清单里每个语种都凑到 URL，凑齐即收工
            val wanted = stable.mapTo(HashSet()) { it.lan }
            var done = 0
            while (done < SAMPLE_LIMIT) {
                sampleBatch(bvid, cid, cookie, BATCH_SIZE, found)
                done += BATCH_SIZE
                val gotLans = found.keys.mapTo(HashSet()) { it.substringBefore('|') }
                if (gotLans.containsAll(wanted)) break
                pauseBetweenBatches()
            }
            // 以稳定清单的语种与顺序为准，URL 取自采样结果。
            // 清单里没出现过的 AI 轨道在此被丢弃——它们本就是随机的重复项。
            val byLan = found.values.associateBy { it.lan }
            return stable.mapNotNull { s -> byLan[s.lan]?.copy(lanDoc = s.lanDoc, isAi = s.isAi) }
        }

        // 稳定清单为空：基本是纯 AI 字幕视频，只能靠采样。
        //
        // 这类视频每次采样会飘出**不同 id** 的 AI 轨道（实测同一 cid 能飘出十几个
        // 不同 id_str，全是 ai-zh/中文），若按 id 或 lan+isAi 收集就会全部收进来，
        // 条目数又变成随机——等于把老问题换个形式带回来。
        //
        // 因此这里按**语种**归组（同一语言只出一条，优先人传、其次 AI），
        // 让结果只取决于"这个视频有几种语言"，与采样运气无关。
        // 固定跑 2 批就停：这个分支本来就是"退而求其次"的兜底，
        // 采样轮数越多，越容易把偶发飘出来的第 2 种语言也收进来，条目数反而更飘。
        // 固定轮数 + 下面按语种归一，至少保证绝大多数视频稳定地只出 1 条。
        sampleBatch(bvid, cid, cookie, BATCH_SIZE, found)
        pauseBetweenBatches()
        sampleBatch(bvid, cid, cookie, BATCH_SIZE, found)
        return found.values
            .groupBy { it.lanDoc }
            .map { (_, group) -> group.minBy { if (it.isAi) 1 else 0 } }
            .sortedBy { it.lan }
    }

    /** 接口返回的 url 可能以 `//` 开头（协议相对），补成 https */
    private fun normalizeUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") -> "https://" + u.removePrefix("http://")
        else -> u
    }

    /**
     * 拉一份字幕 JSON 并解析为时间轴条目。必须在后台线程调用。
     *
     * @param cookie 登录态。**必需**：B 站的字幕（尤其 AI 字幕和 UP 主上传字幕）
     *   在未登录时常常返回空 body 或直接 403，而轨道列表接口却仍返回轨道信息，
     *   表现为"字幕列表里能看到，下载下来却是空的/失败"。
     *   进入全屏播放即处于完整 B 站源状态，此时一定有可用 Cookie，务必带上。
     */
    fun fetchCues(track: BiliSubtitleTrack, cookie: String = ""): List<BiliSubtitleCue> {
        val headers = mutableMapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        val body = HttpUtils.get(track.subtitleUrl, headers)
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
}
