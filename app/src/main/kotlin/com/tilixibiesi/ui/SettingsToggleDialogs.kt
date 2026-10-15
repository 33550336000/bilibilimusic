package com.tilixibiesi.ui

import com.tilixibiesi.R
import com.tilixibiesi.bili.BiliAudioQuality
import com.tilixibiesi.bili.BiliLoginHelper
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SettingsStore
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.util.ActivityTracker
import com.tilixibiesi.util.ContextUtils
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

    /**
     * 完整B站源开关（原搜索页长按按钮的功能，现统一收到设置页「数据与功能」卡片）。
     *
     * 开启需要有登录凭证：本地文件里已有 Cookie 直接开启；
     * 没有就弹统一登录页，登录成功后自动开启。关闭时不动 Cookie 文件。
     */
    fun showFullBiliSourceDialog(btn: Button) {
        if (SpUtils.isFullBiliSource(context)) {
            showDialog(
                AlertDialog.Builder(context)
                    .setTitle(str(R.string.full_bili_source_title))
                    .setMessage(str(R.string.full_bili_source_enabled_msg))
                    .setPositiveButton(str(R.string.btn_ok)) { _, _ ->
                        SpUtils.setFullBiliSource(context, false)
                        updateFullBiliSourceButtonText(btn)
                        ToastUtils.show(context, str(R.string.full_bili_source_disabled_toast))
                    }
                    .setNegativeButton(str(R.string.btn_cancel), null)
            )
            return
        }

        val activity = ContextUtils.unwrapActivity(context) ?: ActivityTracker.current() ?: return
        if (SpUtils.getBiliCookie(activity).isNotEmpty()) {
            // 有本地 Cookie 就直接开启；若已失效，之后的接口返回 -101 会自动弹登录页
            enableFullBiliSource(btn)
            return
        }

        // 没登录过：先登录，成功后自动开启完整源
        ToastUtils.show(context, str(R.string.full_bili_source_need_login))
        BiliLoginHelper.ensureLoggedIn(activity) { enableFullBiliSource(btn) }
    }

    private fun enableFullBiliSource(btn: Button) {
        SpUtils.setFullBiliSource(context, true)
        updateFullBiliSourceButtonText(btn)
        ToastUtils.show(context, str(R.string.full_bili_source_enabled_toast))
    }

    /** 统一刷新按钮文案，避免各处重复拼字符串。 */
    fun updateFullBiliSourceButtonText(btn: Button) {
        btn.text = str(
            if (SpUtils.isFullBiliSource(context)) R.string.btn_full_bili_source_on
            else R.string.btn_full_bili_source_off
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

    /**
     * B站音乐播放音质选择。
     *
     * 列表按音质从低到高排列（枚举声明顺序），默认选中最低音质。
     * 切换后通知播放服务对当前曲目重新解析地址，立即生效。
     */
    fun showBiliAudioQualityDialog(btn: Button) {
        val qualities = BiliAudioQuality.entries
        val labels = qualities.map { str(it.labelRes) }.toTypedArray()
        val current = BiliAudioQuality.fromSetting(SpUtils.getBiliAudioQuality(context))
        val checked = qualities.indexOf(current).coerceAtLeast(0)

        showDialog(
            AlertDialog.Builder(context)
                .setTitle(str(R.string.dialog_title_bili_audio_quality))
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    val quality = qualities[which]
                    SpUtils.saveBiliAudioQuality(context, quality.id)
                    updateBiliAudioQualityButtonText(btn)
                    dialog.dismiss()
                    ToastUtils.show(
                        context,
                        str(R.string.toast_bili_audio_quality_changed, str(quality.labelRes))
                    )
                    // 通知播放服务按新音质重载当前曲目
                    MusicPlayerService.refreshAudioQuality(context)
                }
                .setNegativeButton(str(R.string.btn_cancel), null)
        )
    }

    /** 统一刷新音质按钮文案。 */
    fun updateBiliAudioQualityButtonText(btn: Button) {
        val quality = BiliAudioQuality.fromSetting(SpUtils.getBiliAudioQuality(context))
        btn.text = str(R.string.btn_bili_audio_quality, str(quality.labelRes))
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
