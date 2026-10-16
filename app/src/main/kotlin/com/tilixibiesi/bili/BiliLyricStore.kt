package com.tilixibiesi.bili

import com.tilixibiesi.data.AppPaths
import com.tilixibiesi.util.AtomicFileWriter

import java.io.File

/**
 * 本地歌词文件。
 *
 * 目录：与 `Videocover` 同级的 `cache/Lyric/`，文件名为「曲目实际名称 + `.lrc`」。
 *
 * 歌词总是在解析成功后写一份到本地；播放时优先读本地歌词，
 * 只有本地没有时才去请求网络。
 */
object BiliLyricStore {

    private const val EXTENSION = ".lrc"

    /** 歌词文件名（实际名称 + .lrc）。 */
    fun fileFor(displayName: String): File =
        File(AppPaths.lyricDir(), AppPaths.sanitizeName(displayName) + EXTENSION)

    /**
     * 读取本地歌词；文件不存在、为空或解析不出任何行时返回 null。
     */
    fun load(displayName: String): BiliLyric? {
        val file = fileFor(displayName)
        if (!file.isFile || file.length() <= 0) return null
        return try {
            val lines = BiliLyricHelper.parseLrc(file.readText())
            if (lines.isEmpty()) null else BiliLyric(lines, BiliLyric.Source.LOCAL)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 把歌词写入本地。
     *
     * 只写 LRC 时间轴正文，不再保存来源等附加信息；
     * 已存在且内容相同则跳过写盘。
     */
    fun save(displayName: String, lyric: BiliLyric?) {
        if (lyric == null || lyric.lines.isEmpty()) return
        val dir = AppPaths.lyricDir()
        try {
            if (!dir.exists()) dir.mkdirs()
            val target = fileFor(displayName)
            val text = toLrc(lyric)
            if (target.isFile && target.readText() == text) return
            AtomicFileWriter.writeText(target, text)
        } catch (_: Exception) {
        }
    }

    /** 删除本地歌词（删除缓存音乐时一并清理）。 */
    fun delete(displayName: String) {
        runCatching { fileFor(displayName).delete() }
    }

    /** 序列化为标准 LRC：`[mm:ss.xx]歌词`。 */
    fun toLrc(lyric: BiliLyric): String {
        val sb = StringBuilder()
        for (line in lyric.lines) {
            if (line.text.isEmpty()) continue
            sb.append('[')
                .append(formatTime(line.fromSec))
                .append(']')
                .append(line.text)
                .append('\n')
        }
        return sb.toString()
    }

    private fun formatTime(sec: Float): String {
        val totalMs = (sec.coerceAtLeast(0f) * 1000f).toInt()
        val min = totalMs / 60_000
        val s = (totalMs % 60_000) / 1000
        val cs = (totalMs % 1000) / 10
        return String.format(java.util.Locale.ROOT, "%02d:%02d.%02d", min, s, cs)
    }
}
