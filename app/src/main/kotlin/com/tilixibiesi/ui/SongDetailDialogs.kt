package com.tilixibiesi.ui

import com.tilixibiesi.R
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.PlaybackStatsManager
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.util.ToastUtils

import android.app.AlertDialog
import android.content.Context

class SongDetailDialogs(
    private val context: Context,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog
) {

    private var currentDialog: AlertDialog? = null

    fun showDetailDialog() {
        showSongList()
    }

    fun dismiss() {
        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun showSongList() {
        val all = PlaybackStatsManager.loadPlaybackDetails()
        if (all.isEmpty()) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.detail_no_record))
            return
        }
        val currentPlaying = MusicPlayerService.currentPlayingName
        val rawNames = all.keys.sortedWith(
            compareByDescending<String> { DataFileUtils.getDisplayName(it) == currentPlaying }
                .thenByDescending { all[it]?.keys?.maxOrNull() ?: "0000-00-00" }
        ).toTypedArray()
        val displayNames = Array(rawNames.size) { DataFileUtils.getDisplayName(rawNames[it]) }

        currentDialog?.dismiss()
        var dialog: AlertDialog? = null
        dialog = showDialog(
            AlertDialog.Builder(context, R.style.TransparentDialog)
                .setTitle(LanguageUtils.getString(context, R.string.detail_title))
                .setItems(displayNames) { _, which ->
                    showDailyDetail(rawNames[which])
                }
                .setPositiveButton(LanguageUtils.getString(context, R.string.close), null)
                .setOnDismissListener {
                    if (currentDialog === dialog) currentDialog = null
                }
        )
        currentDialog = dialog
    }

    private fun showDailyDetail(rawName: String) {
        val dateMap = PlaybackStatsManager.loadSongDetails(rawName)
        if (dateMap.isNullOrEmpty()) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.song_no_record))
            return
        }
        val items = dateMap.keys.sortedDescending().map { date ->
            "$date  ${PlaybackStatsManager.formatDuration(dateMap[date]!!, context)}"
        }.toTypedArray()

        currentDialog?.dismiss()
        var dialog: AlertDialog? = null
        dialog = showDialog(
            AlertDialog.Builder(context, R.style.TransparentDialog)
                .setTitle(DataFileUtils.getDisplayName(rawName))
                .setItems(items, null)
                .setPositiveButton(LanguageUtils.getString(context, R.string.back)) { _, _ ->
                    returnToList()
                }
                .setOnDismissListener {
                    if (currentDialog === dialog) currentDialog = null
                }
        )
        currentDialog = dialog
    }

    private fun returnToList() {
        currentDialog?.dismiss()
        showSongList()
    }
}
