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

    /** 普通音乐缓存目录。 */
    fun audioCacheDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "cache/")

    /** B 站音频缓存目录：自动下载的 B 站音频按播放音质放在这里。 */
    fun biliAudioCacheDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "cache/bilibili/")

    /** B 站视频封面目录：与 bilibili 音频目录同级，存放尽可能最高画质的封面。 */
    fun biliCoverDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "cache/Videocover/")

    /** B 站音乐歌词目录：与 Videocover 同级，存放该音乐的歌词。 */
    fun lyricDir(): File = StoragePaths.resolveWrite(ANDROID_REL + "cache/Lyric/")

    private val ILLEGAL_FILE_CHARS = Regex("[\\\\/:*?\"<>|]")

    /** 文件名长度上限，避免超长标题触发 ENAMETOOLONG。 */
    private const val MAX_NAME_LENGTH = 100

    /**
     * 把曲目名称变成可用的文件名。
     *
     * 下载不再使用 MD5，直接用实际名称，因此这里只处理文件系统非法字符，
     * 去掉结尾的点号（避免生成隐藏文件），并限制长度。
     */
    fun sanitizeName(name: String): String {
        val cleaned = name.replace(ILLEGAL_FILE_CHARS, "_").trim().trimEnd('.')
        val limited = if (cleaned.length > MAX_NAME_LENGTH) cleaned.substring(0, MAX_NAME_LENGTH) else cleaned
        return limited.trimEnd('.', ' ').ifEmpty { "unnamed" }
    }
}
