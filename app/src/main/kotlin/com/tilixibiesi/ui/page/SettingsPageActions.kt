package com.tilixibiesi.ui.page

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SettingsStore
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.util.FontUtils
import com.tilixibiesi.util.ToastUtils

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/*
 * 「恢复默认设置」与「选择默认背景」这两条独立流程。
 *
 * 共同点：都以对话框/系统选择器为入口，成功后集中重刷设置页控件，
 * 因此单独成文，不占用 SettingsPage 的主流程。
 */

/** 选择默认背景的 requestCode，仅本文件与 [SettingsPage.onActivityResult] 使用。 */
private const val SETTINGS_REQUEST_PICK_DEFAULT_BG = 1005

internal fun SettingsPage.pickDefaultBackground() {
    val intents = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
    }
    try {
        startActivityForResult(intents, SETTINGS_REQUEST_PICK_DEFAULT_BG)
    } catch (e: android.content.ActivityNotFoundException) {
        ToastUtils.show(this, LanguageUtils.getString(this, R.string.toast_no_file_picker))
    }
}

/**
 * 选择默认背景后的处理，由 `onActivityResult` 调用。
 *
 * @return true 表示这条 requestCode 已被处理。
 */
internal fun SettingsPage.handlePickDefaultBackgroundResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
): Boolean {
    if (requestCode != SETTINGS_REQUEST_PICK_DEFAULT_BG) return false
    if (resultCode != Activity.RESULT_OK || data == null) return true
    val uri = data.data ?: return true
    val newPath = SettingsStore.copyDefaultBackground(this, uri)
    if (newPath != null) {
        SpUtils.saveDefaultBackgroundPath(this, newPath)
        applyBackground()
        ToastUtils.show(this, LanguageUtils.getString(this, R.string.toast_default_bg_set))
    }
    return true
}

internal fun SettingsPage.restoreDefaultSettings() {
    val page = this
    page.showMaterialDialog(
        AlertDialog.Builder(page)
            .setTitle(LanguageUtils.getString(page, R.string.dialog_title_restore_default))
            .setMessage(LanguageUtils.getString(page, R.string.dialog_msg_restore_default))
            .setPositiveButton(LanguageUtils.getString(page, R.string.btn_ok)) { _, _ ->
                val defaultBg = SpUtils.getDefaultBackgroundPath(page)
                SpUtils.clearAll(page)
                if (defaultBg.isNotEmpty()) SpUtils.saveDefaultBackgroundPath(page, defaultBg)

                // B 站登录 Cookie 存在文件里（Appdata/cookie/），恢复默认设置不会清除，
                // 否则用户会被强制退出登录，需要重新扫码/登录一次
                SettingsStore.deleteSettingsFile()

                page.resetFontAndBackgroundControls()
                page.resetDialogStyleControls()
                page.resetToggleButtonLabels()

                page.applyBackground()
                ToastUtils.show(page, LanguageUtils.getString(page, R.string.toast_settings_restored))
            }
            .setNegativeButton(LanguageUtils.getString(page, R.string.btn_cancel), null)
    )
}

/** 把字体颜色/字号/背景透明度复位并同步到界面。 */
private fun SettingsPage.resetFontAndBackgroundControls() {
    SpUtils.saveBackgroundAlpha(this, 100)
    findViewById<SeekBar>(R.id.sb_background_alpha)?.progress = 100
    findViewById<TextView>(R.id.tv_alpha_value)?.text =
        LanguageUtils.getString(this, R.string.current_brightness, 100)

    val defaultColor = Color.parseColor(SpUtils.DEFAULT_FONT_COLOR)
    beginFontColorSync()
    findViewById<EditText>(R.id.et_font_color)?.let {
        it.setText(SpUtils.DEFAULT_FONT_COLOR)
        it.setTextColor(defaultColor)
    }
    endFontColorSync()
    SpUtils.saveFontColor(this, SpUtils.DEFAULT_FONT_COLOR)
    applyFontColorToPicker(defaultColor)
    findViewById<TextView>(R.id.tv_font_color_preview)?.text =
        LanguageUtils.getString(this, R.string.current_color, SpUtils.DEFAULT_FONT_COLOR)

    SpUtils.saveFontSize(this, 15)
    findViewById<SeekBar>(R.id.sb_font_size)?.progress = 15
    findViewById<EditText>(R.id.et_font_size_input)?.setText(
        String.format(Locale.getDefault(), "%d", 15)
    )
    findViewById<TextView>(R.id.tv_font_size_preview)?.apply {
        text = LanguageUtils.getString(this@resetFontAndBackgroundControls, R.string.current_font_size, 15)
        textSize = 15f
    }
}

/** 把弹窗配色复位到白底、跟随全局字色、不透明。 */
private fun SettingsPage.resetDialogStyleControls() {
    SpUtils.saveDialogBgColor(this, "#FFFFFF")
    SpUtils.saveDialogFontColor(this, "")
    SpUtils.saveDialogAlpha(this, 100)

    val white = Color.WHITE
    applyDialogBgColorToPicker(white)
    findViewById<TextView>(R.id.tv_dialog_bg_preview)?.text =
        LanguageUtils.getString(this, R.string.current_color, "#FFFFFF")
    findViewById<View>(R.id.view_dialog_bg_sample)?.setBackgroundColor(white)

    // 弹窗字体默认「跟随全局」，因此滑块回填到全局字体色
    val globalFontColor = FontUtils.color(this)
    applyDialogFontColorToPicker(globalFontColor)
    findViewById<TextView>(R.id.tv_dialog_font_preview)?.text =
        LanguageUtils.getString(
            this,
            R.string.current_dialog_font_global_format,
            SpUtils.getFontColor(this)
        )
    findViewById<View>(R.id.view_dialog_font_sample)?.setBackgroundColor(globalFontColor)

    findViewById<SeekBar>(R.id.sb_dialog_alpha)?.progress = 100
    findViewById<TextView>(R.id.tv_dialog_alpha_preview)?.text =
        LanguageUtils.getString(this, R.string.current_alpha, 100)
}

/** 复位后刷新那些用文字表达状态的开关按钮。 */
private fun SettingsPage.resetToggleButtonLabels() {
    findViewById<Button>(R.id.btn_volume_key_switch)?.text =
        LanguageUtils.getString(this, R.string.btn_volume_key_switch_off)
    findViewById<Button>(R.id.btn_toggle_search_mode)?.text =
        LanguageUtils.getString(this, R.string.btn_toggle_search_page)
    findViewById<Button>(R.id.btn_switch_play_mode)?.text =
        LanguageUtils.getString(
            this,
            R.string.btn_play_mode_format,
            LanguageUtils.getString(this, R.string.play_mode_exclusive_strong)
        )
    updateSearchBtnStyleButtonText()
    findViewById<Button>(R.id.btn_video_notify_progress)?.text =
        LanguageUtils.getString(this, R.string.btn_video_notify_progress_on)
    // 恢复默认会把「完整B站源」开关重置为关（登录凭证仍保留在本地文件里）
    findViewById<Button>(R.id.btn_full_bili_source)?.let {
        refreshFullBiliSourceLabel(it)
    }
    // 播放音质同样回到默认的最低音质，并让当前曲目按新音质重载
    findViewById<Button>(R.id.btn_bili_audio_quality)?.let {
        refreshBiliAudioQualityLabel(it)
    }
    MusicPlayerService.refreshAudioQuality(this)

    // clearAll 把这几个开关也清了，界面上的文案要跟着回到各自默认值，
    // 否则会留下一批「显示着开启、实际已关闭」的按钮
    updateBottomNavButtonText()
    updateLiquidGlassButtonText()
    updateClickFxButtonText()
    updateAutoCacheButtonText()
    updateToggleStyleLabels()
}

/**
 * 刷新「开关式」按钮的文案。
 *
 * 这类按钮的标签由 `setupToggleButton` 在绑定时设置，恢复默认设置后
 * 共享偏好已被清空，但按钮上还留着旧文字，必须按当前实际值重刷一遍。
 */
private fun SettingsPage.updateToggleStyleLabels() {
    refreshToggleLabel(
        R.id.btn_liquid_glass_cards,
        R.string.btn_liquid_glass_cards_on,
        R.string.btn_liquid_glass_cards_off,
        SpUtils.getLiquidGlassCardsEnabled(this)
    )
    refreshToggleLabel(
        R.id.btn_main_add_button,
        R.string.btn_main_add_button_on,
        R.string.btn_main_add_button_off,
        SpUtils.isMainAddButtonVisible(this)
    )
    refreshToggleLabel(
        R.id.btn_main_delete_button,
        R.string.btn_main_delete_button_on,
        R.string.btn_main_delete_button_off,
        SpUtils.isMainDeleteButtonVisible(this)
    )
}

private fun SettingsPage.refreshToggleLabel(buttonId: Int, onRes: Int, offRes: Int, enabled: Boolean) {
    findViewById<Button>(buttonId)?.text =
        LanguageUtils.getString(this, if (enabled) onRes else offRes)
}
