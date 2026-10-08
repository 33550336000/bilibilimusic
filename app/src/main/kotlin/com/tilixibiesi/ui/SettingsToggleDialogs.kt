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

class SettingsToggleDialogs(
    private val context: Context,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog,
    private val onSearchModeChanged: () -> Unit,
    private val onClickFxChanged: () -> Unit,
    private val onSearchBtnStyleChanged: () -> Unit
) {

    private fun str(resId: Int, vararg args: Any): String =
        LanguageUtils.getString(context, resId, *args)

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
