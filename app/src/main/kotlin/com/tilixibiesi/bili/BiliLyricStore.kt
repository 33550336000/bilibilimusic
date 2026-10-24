package com.tilixibiesi.bili

import com.tilixibiesi.data.AppPaths
import com.tilixibiesi.util.AtomicFileWriter

import java.io.File

object BiliLyricStore {

    private const val EXTENSION = ".lrc"

    fun fileFor(displayName: String): File =
        File(AppPaths.lyricDir(), AppPaths.sanitizeName(displayName) + EXTENSION)

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

    fun delete(displayName: String) {
        runCatching { fileFor(displayName).delete() }
    }

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
