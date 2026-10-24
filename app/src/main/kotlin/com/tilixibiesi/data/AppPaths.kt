package com.tilixibiesi.data

import java.io.File

object AppPaths {
    private const val ANDROID_REL = "system/axeron/long/Android/"

    fun mp4DownloadDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "mp4/")

    fun clickFxDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "BA/")

    const val CLICK_FX_FILE = "ba.html"

    fun subtitleDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "subtitle/")

    const val CLICK_FX_URL = "https://language.tibao.dpdns.org/ba.html"

    fun clickFxFile(): File = File(clickFxDir(), CLICK_FX_FILE)

    fun audioCacheDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "cache/")

    fun biliAudioCacheDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "cache/bilibili/")

    fun biliCoverDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "cache/Videocover/")

    fun lyricDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "cache/Lyric/")

    private val ILLEGAL_FILE_CHARS = Regex("[\\\\/:*?\"<>|]")

    private const val MAX_NAME_LENGTH = 100

    fun sanitizeName(name: String): String {
        val cleaned = name.replace(ILLEGAL_FILE_CHARS, "_").trim().trimEnd('.')
        val limited = if (cleaned.length > MAX_NAME_LENGTH) cleaned.substring(0, MAX_NAME_LENGTH) else cleaned
        return limited.trimEnd('.', ' ').ifEmpty { "unnamed" }
    }
}
