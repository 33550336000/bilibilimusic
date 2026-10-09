package com.tilixibiesi.util

import com.tilixibiesi.R
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.model.PlaylistBean

import android.content.Context
import android.content.Intent

/**
 * 分享。播放页、主页面列表长按菜单、歌单长按菜单共用这里的实现，保证行为一致。
 */
object ShareHelper {

    /** B 站视频短链前缀，拼上 bvid 即为可分享的短链。 */
    private const val BILI_SHORT_LINK_PREFIX = "https://b23.tv/"

    /**
     * 分享曲目：第一行歌名，第二行链接，交给系统分享面板（微信等）。
     *
     * 链接来源分两类：
     *  - 普通音乐：用它的网络地址（`musicUrl`，即音源主页上的那个文件链接）。
     *  - B 站音乐：用视频短链 `https://b23.tv/<bvid>`，点开就是原视频。
     *
     * [title] 用于覆盖歌名（播放页会传入已经解析好的标题）。
     */
    fun shareMusic(context: Context, bean: MusicBean?, title: String? = null) {
        val current = bean ?: return
        val shareTitle = title?.takeIf { it.isNotEmpty() }
            ?: DataFileUtils.getDisplayName(current.musicName).takeIf { it.isNotEmpty() }
            ?: LanguageUtils.getString(context, R.string.now_playing_unknown_title)

        val url = shareUrl(current)
        if (url == null) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.now_playing_share_no_link))
            return
        }
        val text = LanguageUtils.getString(context, R.string.now_playing_share_format, shareTitle, url)
        sendShareText(context, shareTitle, text)
    }

    /**
     * 分享整个歌单。文本按层级递归展开：
     *
     * 1. 先输出本层每一首音乐：歌名一行，链接一行（没有链接时只留歌名）；
     * 2. 本层音乐输出完后再依次输出每个子歌单：先输出子歌单名称一行，
     *    再按同样规则递归输出它内部的音乐与更深层的子歌单。
     *
     * 已从应用内删除（删除记录）或被屏蔽词过滤的音乐不会出现在分享文本里，
     * 与歌单页面实际显示的内容保持一致。
     */
    fun sharePlaylist(context: Context, playlist: PlaylistBean?) {
        val current = playlist ?: return

        val deletedNames = DataFileUtils.loadDeletedMusicNames()
        val blockedWords = DataFileUtils.loadBlockedWords()
        val text = buildPlaylistText(current, deletedNames, blockedWords).trimEnd('\n')

        if (text.isEmpty()) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.playlist_share_empty))
            return
        }
        sendShareText(context, current.name, text)
    }

    private fun buildPlaylistText(
        playlist: PlaylistBean,
        deletedNames: Set<String>,
        blockedWords: Set<String>
    ): String {
        val sb = StringBuilder()
        appendPlaylist(sb, playlist, deletedNames, blockedWords)
        return sb.toString()
    }

    private fun appendPlaylist(
        sb: StringBuilder,
        playlist: PlaylistBean,
        deletedNames: Set<String>,
        blockedWords: Set<String>
    ) {
        for (music in playlist.musicList) {
            if (isHidden(music, deletedNames, blockedWords)) continue
            val title = DataFileUtils.getDisplayName(music.musicName)
            if (title.isEmpty()) continue
            sb.append(title).append('\n')
            shareUrl(music)?.let { sb.append(it).append('\n') }
        }

        for (sub in playlist.subPlaylists) {
            if (sub.name.isNotEmpty()) sb.append(sub.name).append('\n')
            appendPlaylist(sb, sub, deletedNames, blockedWords)
        }
    }

    private fun isHidden(
        bean: MusicBean,
        deletedNames: Set<String>,
        blockedWords: Set<String>
    ): Boolean {
        if (deletedNames.contains(bean.musicName)) return true
        if (blockedWords.isEmpty()) return false
        return blockedWords.any { word -> bean.musicName.contains(word, ignoreCase = true) }
    }

    private fun shareUrl(bean: MusicBean): String? {
        if (bean.isBilibili) {
            val bvid = bean.bvid?.takeIf { it.isNotEmpty() } ?: return null
            return "$BILI_SHORT_LINK_PREFIX$bvid"
        }
        return bean.musicUrl.takeIf { it.isNotEmpty() }
    }

    private fun sendShareText(context: Context, subject: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(
            intent,
            LanguageUtils.getString(context, R.string.now_playing_share_chooser)
        )
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.now_playing_share_failed))
        }
    }
}
