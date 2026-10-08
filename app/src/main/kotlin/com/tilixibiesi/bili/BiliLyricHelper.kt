package com.tilixibiesi.bili

import com.tilixibiesi.network.HttpUtils
import org.json.JSONObject

object BiliLyricHelper {

    private const val MARK_SONG_OFFSET = "[song_offset]"
    private const val MARK_END_POINT = "[end_point]"

    private const val TIMEOUT_MS = 8_000

    private const val TAIL_HOLD_SEC = 6f

    private val baseHeaders: Map<String, String> = mapOf(
        "Referer" to "https://www.bilibili.com/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

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

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    fun fetch(bvid: String, cid: Long, durationSec: Int = 0, cookie: String = ""): BiliLyric? {
        if (bvid.isEmpty() || cid <= 0L) return null
        val key = "$bvid/$cid"
        if (hasCache(key)) return cached(key)

        val result = fetchFromMusicLibrary(bvid, cid, durationSec)
            ?: fetchFromSubtitle(bvid, cid, cookie)

        putCache(key, result)
        return result
    }

    private fun fetchFromSubtitle(bvid: String, cid: Long, cookie: String): BiliLyric? {
        return try {
            val tracks = BiliSubtitleHelper.fetchTracks(bvid, cid, cookie)
            if (tracks.isEmpty()) return null
            val track = tracks.firstOrNull { !it.isAi } ?: tracks.first()

            val cues = BiliSubtitleHelper.fetchCues(track, cookie)
            if (cues.isEmpty()) return null

            val lines = cues.mapNotNull { cue ->
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


    private fun fetchFromMusicLibrary(bvid: String, cid: Long, durationSec: Int): BiliLyric? {
        val musicId = try {
            val json = HttpUtils.get(
                "https://api.bilibili.com/x/player/wbi/v2?bvid=$bvid&cid=$cid",
                baseHeaders,
                connectTimeout = TIMEOUT_MS,
                readTimeout = TIMEOUT_MS
            )
            val obj = JSONObject(json)
            if (obj.optInt("code") != 0) return null
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
            obj.optJSONObject("data")
                ?.optString("mv_lyric", "")
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } ?: return null

        val lrc = try {
            val bytes = HttpUtils.getBytes(
                normalizeUrl(lyricUrl),
                baseHeaders,
                connectTimeout = TIMEOUT_MS,
                readTimeout = TIMEOUT_MS
            )
            BiliDanmakuLoader.decodeBody(bytes)
        } catch (_: Exception) {
            null
        } ?: return null

        val lines = parseLrc(lrc)
        if (lines.isEmpty()) return null
        if (!timelineLooksSane(lines, durationSec)) return null

        return BiliLyric(lines, BiliLyric.Source.MUSIC_LIBRARY)
    }


    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    private val metaTag = Regex("""^\[[a-zA-Z#]+:.*]$""")

    fun parseLrc(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()

        var body = lrc
        val startIdx = body.indexOf(MARK_SONG_OFFSET)
        if (startIdx >= 0) {
            val endIdx = body.indexOf(MARK_END_POINT, startIdx)
            body = if (endIdx > startIdx) body.substring(startIdx + MARK_SONG_OFFSET.length, endIdx)
            else body.substring(startIdx + MARK_SONG_OFFSET.length)
        }

        data class Raw(val ms: Int, val text: String)
        val raw = ArrayList<Raw>(128)
        for (line in body.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || metaTag.matches(trimmed)) continue

            val matches = timeTag.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val text = trimmed.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue

            for (m in matches) {
                val min = m.groupValues[1].toIntOrNull() ?: continue
                val sec = m.groupValues[2].toIntOrNull() ?: continue
                val fracMs = when (m.groupValues[3].length) {
                    0 -> 0
                    1 -> (m.groupValues[3].toIntOrNull() ?: 0) * 100
                    2 -> (m.groupValues[3].toIntOrNull() ?: 0) * 10
                    else -> m.groupValues[3].take(3).toIntOrNull() ?: 0
                }
                raw.add(Raw(min * 60_000 + sec * 1_000 + fracMs, text))
            }
        }
        if (raw.isEmpty()) return emptyList()

        raw.sortBy { it.ms }
        val lines = ArrayList<LyricLine>(raw.size)
        for (i in raw.indices) {
            val cur = raw[i]
            val next = raw.getOrNull(i + 1)
            val toMs = when {
                next != null -> next.ms
                else -> cur.ms + (TAIL_HOLD_SEC * 1000).toInt()
            }
            lines.add(LyricLine(cur.ms / 1000f, maxOf(toMs, cur.ms) / 1000f, cur.text))
        }
        return lines
    }


    private fun timelineLooksSane(lines: List<LyricLine>, durationSec: Int): Boolean {
        if (durationSec <= 0) return true
        val last = lines.last().fromSec
        return last <= durationSec + 120f
    }

    private fun normalizeUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") -> "https://" + u.removePrefix("http://")
        else -> u
    }
}
