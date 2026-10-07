package com.tilixibiesi.ui

import com.tilixibiesi.R
import com.tilixibiesi.data.DataFileUtils
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
 *  这组弹窗自带一层"层级"状态（当前在列表层还是明细层、当前弹窗引用），
 *  原先散在歌曲页里，既让页面变长，也让"返回上一层"的状态流转难以整体阅读。
 *  集中到这里后，页面只需调用 [showDetailDialog]。
 *
 * 生命周期：随页面一起创建即可；弹窗关闭由系统处理，本类不持有 Activity 引用
 * （只持有 Context，且弹窗本身就是短生命周期对象）。
 *
 * **数据新鲜度**：播放时长由服务每秒累加落盘，而弹窗展示的是"某一刻的值"。
 * 因此本类**不缓存明细数据**——打开列表、点进某首歌、从明细返回列表，
 * 每个动作都重新读取，否则用户会看到进去 30 秒、返回再进还是 30 秒。
 * 读取成本由 [PlaybackStatsManager] 兜底：历史分片有缓存，只有今天那片实读，
 * 所以"每次都重读"并不等于"每次都全量解析"。
 *
 * @param context 用于取字符串/弹窗（页面的 ContextWrapper 即可）
 * @param showDialog 由页面提供的弹窗构建器，用于沿用页面统一的对话框样式
 */
class SongDetailDialogs(
    private val context: Context,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog
) {

    private var currentDialog: AlertDialog? = null

    /** 入口：无记录时提示，否则展示歌曲列表弹窗。 */
    fun showDetailDialog() {
        showSongList()
    }

    /** 关闭当前弹窗（页面销毁时调用，避免泄漏） */
    fun dismiss() {
        currentDialog?.dismiss()
        currentDialog = null
    }

    /**
     * 展示歌曲列表（每次调用都重新读盘）。
     *
     * 列表项显示显示名（与主页面列表项同一套口径），但查找仍以**实际名称**为键：
     * 明细文件的键是实际名称，显示名只是展示层的一次转换。
     */
    private fun showSongList() {
        val all = PlaybackStatsManager.loadPlaybackDetails()
        if (all.isEmpty()) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.detail_no_record))
            return
        }
        // 当前播放的歌排最前，其余按最近有记录的日期倒序
        val currentPlaying = MusicPlayerService.currentPlayingName
        val rawNames = all.keys.sortedWith(
            compareByDescending<String> { DataFileUtils.getDisplayName(it) == currentPlaying }
                .thenByDescending { all[it]?.keys?.maxOrNull() ?: "0000-00-00" }
        ).toTypedArray()
        val displayNames = Array(rawNames.size) { DataFileUtils.getDisplayName(rawNames[it]) }

        currentDialog?.dismiss()
        // 保存本弹窗引用：dismiss() 触发的 OnDismiss 是异步投递的，执行时
        // currentDialog 可能已指向新弹窗（如每日明细），用身份比较避免误清。
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

    /**
     * 展示某首歌的每日明细（每次调用都重新读盘）。
     *
     * @param rawName 实际名称（明细文件的键）；标题展示时才转成显示名
     */
    private fun showDailyDetail(rawName: String) {
        // 关键：重新读取而不是复用打开列表时的那份快照，否则用户返回再进来
        // 看到的永远是第一次打开时的秒数。这里只取这一首歌的明细，
        // 不必为它汇总整份数据（历史分片走缓存，今天分片实读）。
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
                }
        )
        currentDialog = dialog
    }

    /** 返回列表层：重读一次盘，让列表上的数据也是最新的 */
    private fun returnToList() {
        currentDialog?.dismiss()
        showSongList()
    }
}
