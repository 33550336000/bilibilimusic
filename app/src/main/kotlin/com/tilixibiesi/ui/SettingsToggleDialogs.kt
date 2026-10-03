package com.tilixibiesi.ui

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SettingsStore
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.util.ToastUtils

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Button

/**
 * 设置页「开关型」确认弹窗：点击后即时生效、并刷新对应按钮文案。
 *
 * 覆盖：保存为默认设置 / 搜索按钮样式 / 点击特效 / 搜索页模式 /
 * 音量键切歌 / 音频焦点（播放模式）。
 *
 * 为什么独立成类：这些弹窗的流程高度同构（读当前值 → 弹窗 → 写入 → 刷新 UI → 提示），
 * 集中后新增一个开关只需加一个方法，不必再翻找 1000 多行的设置页。
 *
 * 与页面解耦的方式：只把「切换后需要刷新什么」以回调注入——
 * 各开关影响的范围不同（例如点特效要立刻重载特效层、搜索模式要重建底部导航），
 * 由页面决定，本类不依赖页面的具体控件。
 *
 * @param context 页面的 ContextWrapper 即可
 * @param showDialog 页面提供的统一弹窗构建器
 * @param onSearchModeChanged 搜索页/按钮模式切换后（刷新底部导航等）
 * @param onClickFxChanged 点击特效开关切换后（刷新按钮文案 + 重载特效层）
 * @param onSearchBtnStyleChanged 搜索按钮样式切换后（刷新按钮文案）
 */
class SettingsToggleDialogs(
    private val context: Context,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog,
    private val onSearchModeChanged: () -> Unit,
    private val onClickFxChanged: () -> Unit,
    private val onSearchBtnStyleChanged: () -> Unit
) {

    private fun str(resId: Int, vararg args: Any): String =
        LanguageUtils.getString(context, resId, *args)

    /** 「保存为默认设置」：确认后把当前设置导出为 JSON 快照 */
    fun showSaveAsDefaultDialog() {
        showDialog(
            AlertDialog.Builder(context)
                .setTitle(str(R.string.dialog_title_save_default))
                .setMessage(str(R.string.dialog_msg_save_default))
                .setPositiveButton(str(R.string.btn_ok)) { _, _ ->
                    SettingsStore.saveToFile(context)
                }
                .setNegativeButton(str(R.string.btn_cancel), null)
        )
    }

    /** 搜索按钮样式：透明 ⇄ 原样 */
    fun showSearchBtnStyleDialog() {
        val isTransparent = SpUtils.isSearchBtnTransparentStyle(context)
        val message = if (isTransparent) str(R.string.dialog_msg_search_btn_restore)
        else str(R.string.dialog_msg_search_btn_transparent)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle(str(R.string.dialog_title_search_btn_style))
                .setMessage(message)
                .setPositiveButton(str(R.string.btn_ok)) { _, _ ->
                    SpUtils.setSearchBtnTransparentStyle(context, !isTransparent)
                    onSearchBtnStyleChanged()
                    ToastUtils.show(
                        context,
                        if (!isTransparent) str(R.string.toast_search_style_transparent)
                        else str(R.string.toast_search_style_restored)
                    )
                }
                .setNegativeButton(str(R.string.btn_cancel), null)
        )
    }

    /** 点击特效开关 */
    fun showClickFxDialog() {
        val current = SpUtils.getClickFxEnabled(context)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle(str(R.string.dialog_title_click_fx))
                .setMessage(
                    if (current) str(R.string.dialog_msg_click_fx_off)
                    else str(R.string.dialog_msg_click_fx_on)
                )
                .setPositiveButton(str(R.string.btn_ok)) { _, _ ->
                    SpUtils.setClickFxEnabled(context, !current)
                    onClickFxChanged()
                    ToastUtils.show(context, str(R.string.toast_click_fx_changed))
                }
                .setNegativeButton(str(R.string.btn_cancel), null)
        )
    }

    /** 搜索页模式（页面式 ⇄ 按钮式） */
    fun showSearchModeDialog(btn: Button) {
        val current = SpUtils.getSearchMode(context)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle(str(R.string.dialog_title_switch_search_mode))
                .setMessage(
                    if (current) str(R.string.dialog_msg_search_mode_page)
                    else str(R.string.dialog_msg_search_mode_button)
                )
                .setPositiveButton(str(R.string.btn_ok)) { _, _ ->
                    SpUtils.saveSearchMode(context, !current)
                    btn.text = if (!current) str(R.string.btn_toggle_search_page)
                    else str(R.string.btn_toggle_search_button)
                    onSearchModeChanged()
                    ToastUtils.show(context, str(R.string.toast_search_mode_changed))
                }
                .setNegativeButton(str(R.string.btn_cancel), null)
        )
    }

    /** 音量键切歌开关 */
    fun showVolumeKeySwitchDialog(btn: Button) {
        val current = SpUtils.getVolumeKeySwitch(context)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle(str(R.string.dialog_title_volume_key_switch))
                .setMessage(
                    if (current) str(R.string.dialog_msg_volume_key_on)
                    else str(R.string.dialog_msg_volume_key_off)
                )
                .setPositiveButton(str(R.string.btn_ok)) { _, _ ->
                    val newMode = !current
                    SpUtils.saveVolumeKeySwitch(context, newMode)
                    btn.text = if (newMode) str(R.string.btn_volume_key_switch_on)
                    else str(R.string.btn_volume_key_switch_off)
                    ToastUtils.show(
                        context,
                        if (newMode) str(R.string.toast_volume_key_enabled)
                        else str(R.string.toast_volume_key_disabled)
                    )
                }
                .setNegativeButton(str(R.string.btn_cancel), null)
        )
    }

    /** 音频焦点（播放模式）单选：改档位后通知 Service 重建焦点请求 */
    fun showSwitchPlayModeDialog(btn: Button) {
        val modes = context.resources.getStringArray(R.array.audio_focus_modes).toList()
        val checked = when (SpUtils.getAudioFocusMode(context)) {
            SpUtils.AUDIO_FOCUS_CALL_LEVEL -> 0
            SpUtils.AUDIO_FOCUS_FULL_EXCLUSIVE -> 1
            else -> 2
        }
        showDialog(
            AlertDialog.Builder(context)
                .setTitle(str(R.string.dialog_title_audio_focus))
                .setSingleChoiceItems(modes.toTypedArray(), checked) { dialog, which ->
                    val newMode = when (which) {
                        0 -> SpUtils.AUDIO_FOCUS_CALL_LEVEL
                        1 -> SpUtils.AUDIO_FOCUS_FULL_EXCLUSIVE
                        else -> SpUtils.AUDIO_FOCUS_TRANSIENT
                    }
                    SpUtils.saveAudioFocusMode(context, newMode)
                    btn.text = str(R.string.btn_play_mode_format, modes[which])
                    dialog.dismiss()
                    ToastUtils.show(context, str(R.string.toast_play_mode_changed, modes[which]))
                    context.startService(Intent(context, MusicPlayerService::class.java).apply {
                        action = MusicPlayerService.ACTION_UPDATE_FOCUS_MODE
                    })
                }
                .setNegativeButton(str(R.string.btn_cancel), null)
        )
    }
}
