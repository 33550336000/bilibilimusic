package com.tilixibiesi.ui

import com.tilixibiesi.R
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.PlaybackStatsManager
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.util.ToastUtils

import android.app.AlertDialog
import android.app.Activity
import android.content.Context

class SongDetailDialogs(
    private val context: Context,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog
) {

    private var currentDialog: AlertDialog? = null

    /** 异步读取完成后，宿主的 Activity 可能已经销毁，此时弹窗会抛 BadTokenException。 */
    private fun canShowDialog(): Boolean {
        val activity = context as? Activity ?: return true
        return !activity.isDestroyed && !activity.isFinishing
    }

    fun showDetailDialog() {
        showSongList()
    }

    fun dismiss() {
        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun showSongList() {
        PlaybackStatsManager.loadPlaybackDetailsAsync { all ->
            if (!canShowDialog()) return@loadPlaybackDetailsAsync
            if (all.isEmpty()) {
                ToastUtils.show(context, LanguageUtils.getString(context, R.string.detail_no_record))
                return@loadPlaybackDetailsAsync
            }
            showSongListLoaded(all)
        }
    }

    private fun showSongListLoaded(all: Map<String, Map<String, Long>>) {
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
        PlaybackStatsManager.loadSongDetailsAsync(rawName) { dateMap ->
            if (!canShowDialog()) return@loadSongDetailsAsync
            if (dateMap.isEmpty()) {
                ToastUtils.show(context, LanguageUtils.getString(context, R.string.song_no_record))
                return@loadSongDetailsAsync
            }
            showDailyDetailLoaded(rawName, dateMap)
        }
    }

    private fun showDailyDetailLoaded(rawName: String, dateMap: Map<String, Long>) {
        val items = dateMap.keys.sortedDescending().map { date ->
            "$date  ${PlaybackStatsManager.formatDuration(dateMap.getValue(date), context)}"
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
