package com.tilixibiesi.util

import com.tilixibiesi.R
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.model.PlaylistBean

import android.content.Context
import android.content.Intent

object ShareHelper {

    private const val BILI_SHORT_LINK_PREFIX = "https://b23.tv/"

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
