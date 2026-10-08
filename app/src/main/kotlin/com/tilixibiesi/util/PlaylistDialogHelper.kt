package com.tilixibiesi.util

import android.app.AlertDialog
import android.content.Context
import com.tilixibiesi.R
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.PlaylistManager
import com.tilixibiesi.model.MusicBean

object PlaylistDialogHelper {

    fun showAddToPlaylistDialog(context: Context, bean: MusicBean) {
        DialogHelper.createStyledDialog(
            context,
            AlertDialog.Builder(context, R.style.TransparentDialog)
                .setTitle(LanguageUtils.getString(context, R.string.add_to_playlist_title))
                .setMessage(
                    LanguageUtils.getString(
                        context,
                        R.string.add_to_playlist_message,
                        DataFileUtils.getDisplayName(bean.musicName)
                    )
                )
                .setPositiveButton(LanguageUtils.getString(context, R.string.yes)) { _, _ ->
                    showPlaylistSelector(context, bean)
                }
                .setNegativeButton(LanguageUtils.getString(context, R.string.no), null)
        )
    }

    fun showPlaylistSelector(context: Context, bean: MusicBean) {
        val playlists = PlaylistManager.getPlaylists(context)
        if (playlists.isEmpty()) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.no_playlist))
            return
        }
        DialogHelper.createStyledDialog(
            context,
            AlertDialog.Builder(context, R.style.TransparentDialog)
                .setTitle(LanguageUtils.getString(context, R.string.select_playlist_title))
                .setItems(playlists.map { it.name }.toTypedArray()) { _, which ->
                    val selected = playlists[which]
                    DialogHelper.createStyledDialog(
                        context,
                        AlertDialog.Builder(context, R.style.TransparentDialog)
                            .setTitle(LanguageUtils.getString(context, R.string.confirm_add_title))
                            .setMessage(
                                LanguageUtils.getString(
                                    context,
                                    R.string.confirm_add_message,
                                    DataFileUtils.getDisplayName(bean.musicName),
                                    selected.name
                                )
                            )
                            .setPositiveButton(LanguageUtils.getString(context, R.string.ok)) { _, _ ->
                                PlaylistManager.addMusicToPlaylist(context, selected.id, bean)
                                ToastUtils.show(context, LanguageUtils.getString(context, R.string.added))
                            }
                            .setNegativeButton(LanguageUtils.getString(context, R.string.cancel), null)
                    )
                }
        )
    }
}
