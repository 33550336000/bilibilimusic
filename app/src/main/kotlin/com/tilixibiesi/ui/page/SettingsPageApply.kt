package com.tilixibiesi.ui.page

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.util.FontUtils

import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

internal fun SettingsPage.applySettingsToUI() {
    applyFontControlsFromSettings()
    applyDialogStyleFromSettings()

    findViewById<Button>(R.id.btn_volume_key_switch)?.text =
        if (SpUtils.getVolumeKeySwitch(this))
            LanguageUtils.getString(this, R.string.btn_volume_key_switch_on)
        else
            LanguageUtils.getString(this, R.string.btn_volume_key_switch_off)
    findViewById<Button>(R.id.btn_toggle_search_mode)?.text =
        if (SpUtils.getSearchMode(this))
            LanguageUtils.getString(this, R.string.btn_toggle_search_page)
        else
            LanguageUtils.getString(this, R.string.btn_toggle_search_button)
    findViewById<Button>(R.id.btn_adjust_search_button)?.visibility =
        if (SpUtils.getSearchMode(this)) View.GONE else View.VISIBLE

    val modeText = when (SpUtils.getAudioFocusMode(this)) {
        SpUtils.AUDIO_FOCUS_CALL_LEVEL ->
            LanguageUtils.getString(this, R.string.play_mode_exclusive_strong)
        SpUtils.AUDIO_FOCUS_FULL_EXCLUSIVE ->
            LanguageUtils.getString(this, R.string.play_mode_full_medium)
        SpUtils.AUDIO_FOCUS_TRANSIENT ->
            LanguageUtils.getString(this, R.string.play_mode_transient_weak)
        else -> LanguageUtils.getString(this, R.string.play_mode_default)
    }
    findViewById<Button>(R.id.btn_switch_play_mode)?.text =
        LanguageUtils.getString(this, R.string.btn_play_mode_format, modeText)

    updateSearchBtnStyleButtonText()

    updateAutoCacheButtonText()
    updateBottomNavButtonText()
    updateClickFxButtonText()
    findViewById<Button>(R.id.btn_full_bili_source)?.let {
        refreshFullBiliSourceLabel(it)
    }
    findViewById<Button>(R.id.btn_bili_audio_quality)?.let {
        refreshBiliAudioQualityLabel(it)
    }
}

private fun SettingsPage.applyFontControlsFromSettings() {
    val savedColor = SpUtils.getFontColor(this)
    val parsedColor = runCatching { Color.parseColor(savedColor) }
        .getOrElse { Color.parseColor(SpUtils.DEFAULT_FONT_COLOR) }

    beginFontColorSync()
    findViewById<EditText>(R.id.et_font_color)?.let {
        it.setText(savedColor)
        it.setTextColor(parsedColor)
    }
    endFontColorSync()
    applyFontColorToPicker(parsedColor)
    findViewById<TextView>(R.id.tv_font_color_preview)?.text =
        LanguageUtils.getString(this, R.string.current_color, savedColor)

    val fontSize = SpUtils.getFontSize(this)
    findViewById<SeekBar>(R.id.sb_font_size)?.progress = fontSize
    findViewById<EditText>(R.id.et_font_size_input)?.setText(
        String.format(Locale.getDefault(), "%d", fontSize)
    )
    findViewById<TextView>(R.id.tv_font_size_preview)?.apply {
        text = LanguageUtils.getString(
            this@applyFontControlsFromSettings,
            R.string.current_font_size,
            fontSize
        )
        textSize = fontSize.toFloat()
    }

    val alpha = SpUtils.getBackgroundAlpha(this)
    findViewById<SeekBar>(R.id.sb_background_alpha)?.progress = alpha
    findViewById<TextView>(R.id.tv_alpha_value)?.text =
        LanguageUtils.getString(this, R.string.current_brightness, alpha)
}

private fun SettingsPage.applyDialogStyleFromSettings() {
    val dialogBgColor = SpUtils.getDialogBgColor(this)
    runCatching { Color.parseColor(dialogBgColor) }.getOrNull()?.let { color ->
        applyDialogBgColorToPicker(color)
        findViewById<TextView>(R.id.tv_dialog_bg_preview)?.text =
            LanguageUtils.getString(this, R.string.current_color, dialogBgColor)
        findViewById<View>(R.id.view_dialog_bg_sample)?.setBackgroundColor(color)
    }

    val dialogFontColor = SpUtils.getDialogFontColor(this)
    if (dialogFontColor.isEmpty()) {
        val globalColor = FontUtils.color(this)
        applyDialogFontColorToPicker(globalColor)
        findViewById<TextView>(R.id.tv_dialog_font_preview)?.text =
            LanguageUtils.getString(
                this,
                R.string.current_dialog_font_global_format,
                SpUtils.getFontColor(this)
            )
        findViewById<View>(R.id.view_dialog_font_sample)?.setBackgroundColor(globalColor)
    } else {
        runCatching { Color.parseColor(dialogFontColor) }.getOrNull()?.let { color ->
            applyDialogFontColorToPicker(color)
            findViewById<TextView>(R.id.tv_dialog_font_preview)?.text =
                LanguageUtils.getString(this, R.string.current_color, dialogFontColor)
            findViewById<View>(R.id.view_dialog_font_sample)?.setBackgroundColor(color)
        }
    }

    val dialogAlpha = SpUtils.getDialogAlpha(this)
    findViewById<SeekBar>(R.id.sb_dialog_alpha)?.progress = dialogAlpha
    findViewById<TextView>(R.id.tv_dialog_alpha_preview)?.text =
        LanguageUtils.getString(this, R.string.current_alpha, dialogAlpha)
}

internal fun SettingsPage.updateBottomNavButtonText() {
    findViewById<Button>(R.id.btn_bottom_nav)?.text =
        if (SpUtils.getBottomNavEnabled(this))
            LanguageUtils.getString(this, R.string.btn_bottom_nav_on)
        else
            LanguageUtils.getString(this, R.string.btn_bottom_nav_off)
}

internal fun SettingsPage.updateLiquidGlassButtonText() {
    findViewById<Button>(R.id.btn_liquid_glass)?.text = LanguageUtils.getString(
        this,
        if (SpUtils.getLiquidGlassEnabled(this)) R.string.btn_liquid_glass_on
        else R.string.btn_liquid_glass_off
    )
}

internal fun SettingsPage.updateClickFxButtonText() {
    findViewById<Button>(R.id.btn_toggle_click_fx)?.text = LanguageUtils.getString(
        this, R.string.btn_toggle_click_fx,
        if (SpUtils.getClickFxEnabled(this))
            LanguageUtils.getString(this, R.string.state_on)
        else
            LanguageUtils.getString(this, R.string.state_off)
    )
}

internal fun SettingsPage.updateAutoCacheButtonText() {
    findViewById<Button>(R.id.btn_auto_cache)?.text =
        if (SpUtils.isAutoCacheEnabled(this))
            LanguageUtils.getString(this, R.string.btn_auto_cache_on)
        else
            LanguageUtils.getString(this, R.string.btn_auto_cache_off)
}
