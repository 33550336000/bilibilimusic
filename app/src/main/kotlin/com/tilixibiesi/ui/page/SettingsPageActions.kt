package com.tilixibiesi.ui.page

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SettingsStore
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.FontUtils
import com.tilixibiesi.util.LogExporter
import com.tilixibiesi.util.ShareHelper
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
import java.io.File
import java.util.Locale

private const val SETTINGS_REQUEST_PICK_DEFAULT_BG = 1005

private const val SETTINGS_REQUEST_EXPORT_LOG = 1006

internal fun SettingsPage.showExportLogDialog() {
    val page = this
    showMaterialDialog(
        AlertDialog.Builder(page)
            .setTitle(LanguageUtils.getString(page, R.string.dialog_title_export_log))
            .setMessage(LanguageUtils.getString(page, R.string.dialog_msg_export_log))
            .setPositiveButton(LanguageUtils.getString(page, R.string.btn_export)) { _, _ ->
                page.exportLogToDocument()
            }
            .setNeutralButton(LanguageUtils.getString(page, R.string.now_playing_share)) { _, _ ->
                page.shareLogFile()
            }
            .setNegativeButton(LanguageUtils.getString(page, R.string.btn_cancel), null)
    )
}

private fun SettingsPage.prepareLogFile(onReady: (File?) -> Unit) {
    val page = this
    AppExecutors.io.execute {
        val file = LogExporter.export(page)
        page.runOnUiThread {
            if (page.activity.isDestroyed) return@runOnUiThread
            onReady(file)
        }
    }
}

private fun SettingsPage.shareLogFile() {
    prepareLogFile { file ->
        if (file == null) {
            ToastUtils.show(this, LanguageUtils.getString(this, R.string.toast_log_export_failed))
        } else {
            ShareHelper.shareLog(this, file)
        }
    }
}

private fun SettingsPage.exportLogToDocument() {
    prepareLogFile { file ->
        if (file == null) {
            ToastUtils.show(this, LanguageUtils.getString(this, R.string.toast_log_export_failed))
            return@prepareLogFile
        }
        pendingLogFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        try {
            startActivityForResult(intent, SETTINGS_REQUEST_EXPORT_LOG)
        } catch (e: android.content.ActivityNotFoundException) {
            pendingLogFile = null
            ToastUtils.show(this, LanguageUtils.getString(this, R.string.toast_no_file_picker))
        }
    }
}

internal fun SettingsPage.handleExportLogResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
): Boolean {
    if (requestCode != SETTINGS_REQUEST_EXPORT_LOG) return false
    val source = pendingLogFile
    pendingLogFile = null
    if (resultCode != Activity.RESULT_OK || data == null || source == null) return true
    val uri = data.data ?: return true
    AppExecutors.io.execute {
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } != null
        }.getOrDefault(false)
        runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            ToastUtils.show(
                this,
                LanguageUtils.getString(
                    this,
                    if (ok) R.string.toast_log_export_saved else R.string.toast_log_export_failed
                )
            )
        }
    }
    return true
}

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

private fun SettingsPage.resetDialogStyleControls() {
    SpUtils.saveDialogBgColor(this, "#FFFFFF")
    SpUtils.saveDialogFontColor(this, "")
    SpUtils.saveDialogAlpha(this, 100)

    val white = Color.WHITE
    applyDialogBgColorToPicker(white)
    findViewById<TextView>(R.id.tv_dialog_bg_preview)?.text =
        LanguageUtils.getString(this, R.string.current_color, "#FFFFFF")
    findViewById<View>(R.id.view_dialog_bg_sample)?.setBackgroundColor(white)

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
    findViewById<Button>(R.id.btn_bili_audio_quality)?.let {
        refreshBiliAudioQualityLabel(it)
    }
    MusicPlayerService.refreshAudioQuality(this)

    updateBottomNavButtonText()
    updateLiquidGlassButtonText()
    updateClickFxButtonText()
    updateAutoCacheButtonText()
    updateToggleStyleLabels()
}

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
