package com.tilixibiesi.bili

import com.tilixibiesi.network.HttpUtils
import org.json.JSONObject

object BiliSubtitleHelper {

    private const val TIMEOUT_MS = 10_000

    private fun headers(cookie: String): MutableMap<String, String> {
        val h = mutableMapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        if (cookie.isNotEmpty()) h["Cookie"] = cookie
        return h
    }

    fun fetchTracks(bvid: String, cid: Long, cookie: String = ""): List<BiliSubtitleTrack> {
        if (bvid.isEmpty() || cid <= 0L) return emptyList()
        return try {
            val json = HttpUtils.get(
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
                if (url.isEmpty()) continue
                val lan = s.optString("lan", "")
                if (lan.isEmpty()) continue
                out.add(
                    BiliSubtitleTrack(
                        lan = lan,
                        lanDoc = s.optString("lan_doc", "").ifEmpty { lan },
                        subtitleUrl = url,
                        id = s.optString("id_str", ""),
                        isAi = s.optInt("ai_type", 0) != 0 || lan.startsWith("ai-")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

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

    private fun normalizeUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") -> "https://" + u.removePrefix("http://")
        else -> u
    }
}
