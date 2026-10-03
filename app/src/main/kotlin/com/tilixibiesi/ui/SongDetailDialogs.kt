package com.tilixibiesi.ui

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.PlaybackStatsManager
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.util.ToastUtils

import android.app.AlertDialog
import android.content.Context

/**
 * 「播放详情」两级弹窗：歌曲列表 → 某首歌的每日明细。
 *
 * 为什么独立成类：
 *  这组弹窗自带一层"层级"状态（当前在列表层还是明细层、当前弹窗引用、
 *  缓存的数据），原先散在歌曲页里，既让页面变长，也让"返回上一层"的
 *  状态流转难以整体阅读。集中到这里后，页面只需调用 [showDetailDialog]。
 *
 * 生命周期：随页面一起创建即可；弹窗关闭由系统处理，本类不持有 Activity 引用
 * （只持有 Context，且弹窗本身就是短生命周期对象）。
 *
 * @param context 用于取字符串/弹窗（页面的 ContextWrapper 即可）
 * @param showDialog 由页面提供的弹窗构建器，用于沿用页面统一的对话框样式
 */
class SongDetailDialogs(
    private val context: Context,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog
) {

    private var currentDialog: AlertDialog? = null
    private var songNames: Array<String>? = null
    private var details: Map<String, Map<String, Long>>? = null
    private var inDailyLayer = false

    /** 入口：无记录时提示，否则展示歌曲列表弹窗。 */
    fun showDetailDialog() {
        val all = PlaybackStatsManager.loadPlaybackDetails()
        if (all.isEmpty()) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.detail_no_record))
            return
        }
        // 当前播放的歌排最前，其余按最近有记录的日期倒序
        val currentPlaying = MusicPlayerService.currentPlayingName
        val sortedNames = all.keys.sortedWith(
            compareByDescending<String> { it == currentPlaying }
                .thenByDescending { all[it]?.keys?.maxOrNull() ?: "0000-00-00" }
        )
        showSongList(all, sortedNames.toTypedArray())
    }

    /** 关闭当前弹窗（页面销毁时调用，避免泄漏） */
    fun dismiss() {
        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun showSongList(all: Map<String, Map<String, Long>>, names: Array<String>) {
        details = all
        songNames = names
        currentDialog?.dismiss()
        // 保存本弹窗引用：dismiss() 触发的 OnDismiss 是异步投递的，执行时
        // currentDialog 可能已指向新弹窗（如每日明细），用身份比较避免误清。
        var dialog: AlertDialog? = null
        dialog = showDialog(
            AlertDialog.Builder(context, R.style.TransparentDialog)
                .setTitle(LanguageUtils.getString(context, R.string.detail_title))
                .setItems(names) { _, which ->
                    showDailyDetail(names[which], all[names[which]]!!)
                }
                .setPositiveButton(LanguageUtils.getString(context, R.string.close), null)
                .setOnDismissListener {
                    if (currentDialog === dialog) currentDialog = null
                    // 不在明细层才清缓存：从列表进明细时列表弹窗也会 dismiss，
                    // 此时若清掉，明细页的「返回」就回不去了。
                    if (!inDailyLayer) {
                        details = null
                        songNames = null
                    }
                }
        )
        currentDialog = dialog
    }

    private fun showDailyDetail(songName: String, dateMap: Map<String, Long>) {
        if (dateMap.isEmpty()) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.song_no_record))
            return
        }
        val items = dateMap.keys.sortedDescending().map { date ->
            "$date  ${PlaybackStatsManager.formatDuration(dateMap[date]!!, context)}"
        }.toTypedArray()

        inDailyLayer = true
        currentDialog?.dismiss()
        var dialog: AlertDialog? = null
        dialog = showDialog(
            AlertDialog.Builder(context, R.style.TransparentDialog)
                .setTitle(songName)
                // 该列表只用于展示每日明细，点击行不应有任何动作。
                // 注意：setItems 只要传入非 null 监听器，框架就会在点击后无条件
                // dialog.dismiss()（见 AlertController.AlertParams#apply），弹窗会被关掉；
                // 因此这里传 null，让框架不安装点击监听，点击行只高亮、不关闭弹窗。
                .setItems(items, null)
                .setPositiveButton(LanguageUtils.getString(context, R.string.back)) { _, _ ->
                    returnToList()
                }
                .setOnDismissListener {
                    if (currentDialog === dialog) currentDialog = null
                    inDailyLayer = false
                }
        )
        currentDialog = dialog
    }

    private fun returnToList() {
        val names = songNames
        val all = details
        if (names != null && all != null) {
            currentDialog?.dismiss()
            showSongList(all, names)
        }
    }
}
