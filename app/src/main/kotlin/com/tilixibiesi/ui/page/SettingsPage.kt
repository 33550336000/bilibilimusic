package com.tilixibiesi.ui.page
import com.tilixibiesi.util.ToastUtils

import com.tilixibiesi.data.SettingsStore
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.FootprintUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.util.BackgroundHelper
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.DialogHelper
import com.tilixibiesi.ui.MainPagerActivity
import com.tilixibiesi.ui.SettingsToggleDialogs
import com.tilixibiesi.ui.StorageDialogs
import com.tilixibiesi.R
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.service.VideoPlaybackService

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.*
import kotlin.random.Random
import java.util.Locale
import android.graphics.Typeface

class SettingsPage(base: Context) : BasePage(base) {
    companion object {
        private const val REQUEST_PICK_DEFAULT_BG = 1005
    }

    private lateinit var btnAutoCache: Button
    private lateinit var etFontColor: EditText
    private lateinit var labelFontColor: TextView
    private lateinit var sbFontSize: SeekBar
    private lateinit var tvFontSizePreview: TextView
    private lateinit var etFontSizeInput: EditText
    private lateinit var labelFontSize: TextView
    private lateinit var sbBackgroundAlpha: SeekBar
    private lateinit var tvAlphaValue: TextView
    private lateinit var btnSelectDefaultBackground: Button
    private lateinit var btnRestoreDefault: Button
    private var tempBackgroundAlpha: Int = 100
    private lateinit var rootView: View
    private lateinit var bgHost: View
    private lateinit var btnBlockedWords: Button
    private lateinit var sbFontColorHue: SeekBar
    private lateinit var tvFontColorPreview: TextView
    private lateinit var btnAdjustSearchButton: Button
    private lateinit var btnSearchBtnStyle: Button
    private lateinit var btnSaveAsDefault: Button
    private lateinit var btnAutoLoadDefault: Button

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_settings)
        findViewById<TextView>(R.id.title_settings_page)?.text = LanguageUtils.getString(this@SettingsPage, R.string.settings_title)
        rootView = findViewById<View>(R.id.settings_root)!!
        bgHost = findViewById<View>(R.id.settings_bg_host)!!
        rootView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                val onFontSize = ::etFontSizeInput.isInitialized && isTouchInRect(x, y, etFontSizeInput)
                val onFontColor = ::etFontColor.isInitialized && isTouchInRect(x, y, etFontColor)
                val sizeFocused = ::etFontSizeInput.isInitialized && etFontSizeInput.hasFocus()
                val colorFocused = ::etFontColor.isInitialized && etFontColor.hasFocus()
                if (!onFontSize && !onFontColor && (sizeFocused || colorFocused)) {
                    if (::etFontSizeInput.isInitialized) etFontSizeInput.clearFocus()
                    if (::etFontColor.isInitialized) etFontColor.clearFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(pageView?.windowToken, 0)
                }
            }
            false
        }
        applyBoldItalicGlobally()
        initView()
        loadCurrentSettings()
        applyBackground()
        setupCollapsibleSections()
    }

    override fun onResume() {
        BackgroundHelper.setActive(bgHost, true)
    }

    override fun onPause() {
        BackgroundHelper.setActive(bgHost, false)
    }

    override fun onDestroy() {
        BackgroundHelper.release(bgHost)
        super.onDestroy()
    }

    private fun refreshBottomNav() {
        (activity as? MainPagerActivity)?.refreshBottomNav()
    }

    private fun isTouchInRect(x: Int, y: Int, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return x in loc[0] until loc[0] + view.width && y in loc[1] until loc[1] + view.height
    }

    private fun initView() {
        val containerFontColor = findViewById<FrameLayout>(R.id.container_font_color)!!
        etFontColor = findViewById<EditText>(R.id.et_font_color)!!
        labelFontColor = findViewById<TextView>(R.id.label_font_color)!!
        setupFloatingLabel(containerFontColor, labelFontColor, etFontColor)
        val containerFontSize = findViewById<FrameLayout>(R.id.container_font_size)!!
        etFontSizeInput = findViewById<EditText>(R.id.et_font_size_input)!!
        labelFontSize = findViewById<TextView>(R.id.label_font_size)!!
        setupFloatingLabel(containerFontSize, labelFontSize, etFontSizeInput)

        sbFontColorHue = findViewById<SeekBar>(R.id.sb_font_color_hue)!!
        tvFontColorPreview = findViewById<TextView>(R.id.tv_font_color_preview)!!
        sbFontColorHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val color = Color.HSVToColor(floatArrayOf(progress.toFloat(), 1f, 1f))
                val hex = String.format("#%06X", 0xFFFFFF and color)
                tvFontColorPreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_color, hex)
                etFontColor.setText(hex)
                etFontColor.setTextColor(color)
                SpUtils.saveFontColor(this@SettingsPage, hex)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        etFontColor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s.toString().trim()
                if (text.isNotEmpty()) {
                    try {
                        val color = Color.parseColor(text)
                        val hsv = FloatArray(3)
                        Color.colorToHSV(color, hsv)
                        val hue = hsv[0].toInt()
                        if (sbFontColorHue.progress != hue) sbFontColorHue.progress = hue
                        tvFontColorPreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_color, text)
                        SpUtils.saveFontColor(this@SettingsPage, text)
                    } catch (_: Exception) {
                    }
                }
            }
        })

        sbFontSize = findViewById<SeekBar>(R.id.sb_font_size)!!
        tvFontSizePreview = findViewById<TextView>(R.id.tv_font_size_preview)!!
        sbBackgroundAlpha = findViewById<SeekBar>(R.id.sb_background_alpha)!!
        tvAlphaValue = findViewById<TextView>(R.id.tv_alpha_value)!!
        btnSelectDefaultBackground = findViewById<Button>(R.id.btn_select_default_background)!!
        btnRestoreDefault = findViewById<Button>(R.id.btn_restore_default)!!
        btnAdjustSearchButton = findViewById<Button>(R.id.btn_adjust_search_button)!!
        btnSearchBtnStyle = findViewById<Button>(R.id.btn_search_btn_style)!!
        btnBlockedWords = findViewById<Button>(R.id.btn_blocked_words)!!

        btnSaveAsDefault = findViewById<Button>(R.id.btn_save_default)!!
        btnSaveAsDefault.setOnClickListener { toggleDialogs.showSaveAsDefaultDialog() }

        btnAutoLoadDefault = findViewById<Button>(R.id.btn_auto_load_default)!!
        btnAutoLoadDefault.text = if (SpUtils.isAutoLoadDefaultEnabled(this))
            LanguageUtils.getString(this@SettingsPage, R.string.btn_auto_load_default_on)
        else
            LanguageUtils.getString(this@SettingsPage, R.string.btn_auto_load_default_off)

        btnAutoLoadDefault.setOnClickListener {
            val current = SpUtils.isAutoLoadDefaultEnabled(this)
            val message = if (current)
                LanguageUtils.getString(this@SettingsPage, R.string.dialog_msg_auto_load_default_on)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.dialog_msg_auto_load_default_off)

            showMaterialDialog(
                AlertDialog.Builder(this)
                    .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.dialog_title_auto_load_default))
                    .setMessage(message)
                    .setPositiveButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_ok)) { _, _ ->
                        SpUtils.setAutoLoadDefaultEnabled(this, !current)
                        btnAutoLoadDefault.text = if (!current)
                            LanguageUtils.getString(this@SettingsPage, R.string.btn_auto_load_default_on)
                        else
                            LanguageUtils.getString(this@SettingsPage, R.string.btn_auto_load_default_off)

                        if (!current) {
                            SettingsStore.loadFromFile(this)
                            applySettingsToUI()
                            applyBackground()
                        }

                        ToastUtils.show(this@SettingsPage, 
                            if (!current) LanguageUtils.getString(this@SettingsPage, R.string.toast_auto_load_default_enabled)
                            else LanguageUtils.getString(this@SettingsPage, R.string.toast_auto_load_default_disabled)
                        )
                    }
                    .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
            )
        }

        val sbDialogBgHue = findViewById<SeekBar>(R.id.sb_dialog_bg_hue)!!
        val tvDialogBgPreview = findViewById<TextView>(R.id.tv_dialog_bg_preview)!!
        val viewDialogBgSample = findViewById<View>(R.id.view_dialog_bg_sample)!!

        val sbDialogFontHue = findViewById<SeekBar>(R.id.sb_dialog_font_hue)!!
        val tvDialogFontPreview = findViewById<TextView>(R.id.tv_dialog_font_preview)!!
        val viewDialogFontSample = findViewById<View>(R.id.view_dialog_font_sample)!!
        val btnDialogFontFollowGlobal = findViewById<Button>(R.id.btn_dialog_font_follow_global)!!

        val sbDialogAlpha = findViewById<SeekBar>(R.id.sb_dialog_alpha)!!
        val tvDialogAlphaPreview = findViewById<TextView>(R.id.tv_dialog_alpha_preview)!!

        sbDialogBgHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val color = Color.HSVToColor(floatArrayOf(progress.toFloat(), 1f, 1f))
                val hex = String.format("#%06X", 0xFFFFFF and color)
                tvDialogBgPreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_color, hex)
                viewDialogBgSample.setBackgroundColor(color)
                SpUtils.saveDialogBgColor(this@SettingsPage, hex)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        sbDialogFontHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val color = Color.HSVToColor(floatArrayOf(progress.toFloat(), 1f, 1f))
                val hex = String.format("#%06X", 0xFFFFFF and color)
                tvDialogFontPreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_color, hex)
                viewDialogFontSample.setBackgroundColor(color)
                SpUtils.saveDialogFontColor(this@SettingsPage, hex)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        btnDialogFontFollowGlobal.setOnClickListener {
            SpUtils.saveDialogFontColor(this@SettingsPage, "")
            val globalColor = try {
                Color.parseColor(SpUtils.getFontColor(this@SettingsPage))
            } catch (_: Exception) {
                Color.WHITE
            }
            val hsv = FloatArray(3)
            Color.colorToHSV(globalColor, hsv)
            sbDialogFontHue.progress = hsv[0].toInt()
            tvDialogFontPreview.text = getString(
                R.string.current_dialog_font_global_format,
                SpUtils.getFontColor(this@SettingsPage)
            )
            viewDialogFontSample.setBackgroundColor(globalColor)
        }

        sbDialogAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvDialogAlphaPreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_alpha, progress)
                SpUtils.saveDialogAlpha(this@SettingsPage, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        findViewById<Button>(R.id.btn_playlist_footprint)!!.setOnClickListener {
            storageDialogs.showFootprintDialog(
                LanguageUtils.getString(this@SettingsPage, R.string.dialog_title_footprint_playlist),
                FootprintUtils.readPlaylistFootprints(this@SettingsPage)
            ) {
                FootprintUtils.clearPlaylistFootprints()
                ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.toast_playlist_footprint_cleared))
            }
        }

        val btnVolumeKeySwitch = findViewById<Button>(R.id.btn_volume_key_switch)!!
        btnVolumeKeySwitch.text = if (SpUtils.getVolumeKeySwitch(this))
            LanguageUtils.getString(this@SettingsPage, R.string.btn_volume_key_switch_on)
        else
            LanguageUtils.getString(this@SettingsPage, R.string.btn_volume_key_switch_off)
        btnVolumeKeySwitch.setOnClickListener { toggleDialogs.showVolumeKeySwitchDialog(btnVolumeKeySwitch) }

        val btnBottomNav = findViewById<Button>(R.id.btn_bottom_nav)!!
        updateBottomNavButtonText()
        btnBottomNav.setOnClickListener {
            val current = SpUtils.getBottomNavEnabled(this)
            val message = if (current)
                LanguageUtils.getString(this@SettingsPage, R.string.toast_bottom_nav_disabled)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.toast_bottom_nav_enabled)
            showMaterialDialog(
                AlertDialog.Builder(this)
                    .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.btn_bottom_nav))
                    .setMessage(getString(if (current)
                        R.string.dialog_msg_bottom_nav_off
                    else
                        R.string.dialog_msg_bottom_nav_on))
                    .setPositiveButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_ok)) { _, _ ->
                        SpUtils.setBottomNavEnabled(this, !current)
                        updateBottomNavButtonText()
                        refreshBottomNav()
                        ToastUtils.show(this@SettingsPage, message)
                    }
                    .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
            )
        }

        findViewById<Button>(R.id.btn_clear_cache)!!.setOnClickListener { storageDialogs.showCacheManager() }

        val btnToggleClickFx = findViewById<Button>(R.id.btn_toggle_click_fx)!!
        updateClickFxButtonText()
        btnToggleClickFx.setOnClickListener { toggleDialogs.showClickFxDialog() }

        val btnToggleSearchMode = findViewById<Button>(R.id.btn_toggle_search_mode)!!
        btnToggleSearchMode.text = if (SpUtils.getSearchMode(this))
            LanguageUtils.getString(this@SettingsPage, R.string.btn_toggle_search_page)
        else
            LanguageUtils.getString(this@SettingsPage, R.string.btn_toggle_search_button)
        btnToggleSearchMode.setOnClickListener { toggleDialogs.showSearchModeDialog(btnToggleSearchMode) }

        btnAutoCache = findViewById<Button>(R.id.btn_auto_cache)!!
        updateAutoCacheButtonText()
        btnAutoCache.setOnClickListener {
            val current = SpUtils.isAutoCacheEnabled(this)
            val message = if (current)
                LanguageUtils.getString(this@SettingsPage, R.string.dialog_msg_confirm_disable_auto_cache)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.dialog_msg_confirm_enable_auto_cache)
            showMaterialDialog(
                AlertDialog.Builder(this)
                    .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.dialog_title_auto_cache))
                    .setMessage(message)
                    .setPositiveButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_ok)) { _, _ ->
                        SpUtils.setAutoCacheEnabled(this, !current)
                        updateAutoCacheButtonText()
                        ToastUtils.show(this@SettingsPage, 
                            if (!current) LanguageUtils.getString(this@SettingsPage, R.string.toast_auto_cache_enabled)
                            else LanguageUtils.getString(this@SettingsPage, R.string.toast_auto_cache_disabled)
                        )
                    }
                    .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
            )
        }

        btnAdjustSearchButton.setOnClickListener {
            SongsPage.pendingAdjustMode = true
            gotoPage(MainPagerActivity.PAGE_SONGS, true)
        }
        btnAdjustSearchButton.visibility =
            if (SpUtils.getSearchMode(this)) View.GONE else View.VISIBLE

        findViewById<Button>(R.id.btn_send_notification)!!.setOnClickListener { showSendNotificationDialog() }

        val btnSwitchPlayMode = findViewById<Button>(R.id.btn_switch_play_mode)!!
        btnSwitchPlayMode.setOnClickListener { toggleDialogs.showSwitchPlayModeDialog(btnSwitchPlayMode) }

        sbFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvFontSizePreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_font_size, progress)
                tvFontSizePreview.textSize = progress.toFloat()
                if (fromUser) {
                    etFontSizeInput.setText(String.format(Locale.getDefault(), "%d", progress))
                    SpUtils.saveFontSize(this@SettingsPage, progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        etFontSizeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val str = s.toString().trim()
                if (TextUtils.isEmpty(str)) return
                try {
                    val size = str.toInt()
                    if (size <= 0) etFontSizeInput.error = LanguageUtils.getString(this@SettingsPage, R.string.error_font_size_positive)
                    else {
                        sbFontSize.progress = minOf(size, sbFontSize.max)
                        tvFontSizePreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_font_size, size)
                        tvFontSizePreview.textSize = size.toFloat()
                        SpUtils.saveFontSize(this@SettingsPage, size)
                    }
                } catch (e: NumberFormatException) {
                    etFontSizeInput.error = LanguageUtils.getString(this@SettingsPage, R.string.error_invalid_number)
                }
            }
        })

        sbBackgroundAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tempBackgroundAlpha = progress
                tvAlphaValue.text = LanguageUtils.getString(this@SettingsPage, R.string.current_brightness, progress)
                if (fromUser) {
                    SpUtils.saveBackgroundAlpha(this@SettingsPage, progress)
                    applyBackground()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        btnSelectDefaultBackground.setOnClickListener { pickDefaultBackground() }

        btnRestoreDefault.setOnClickListener { restoreDefaultSettings() }
        btnBlockedWords.setOnClickListener { storageDialogs.showBlockedWordsManager() }
        findViewById<Button>(R.id.btn_switch_language)!!.setOnClickListener {
            showLanguageDialog()
        }
        btnSearchBtnStyle.setOnClickListener { toggleDialogs.showSearchBtnStyleDialog() }
        updateSearchBtnStyleButtonText()

        val btnShowTodayDuration = findViewById<Button>(R.id.btn_show_today_duration)!!
        btnShowTodayDuration.text = if (SpUtils.isShowTodayDurationEnabled(this))
            LanguageUtils.getString(this@SettingsPage, R.string.btn_show_today_duration_on)
        else
            LanguageUtils.getString(this@SettingsPage, R.string.btn_show_today_duration_off)
        btnShowTodayDuration.setOnClickListener {
            val current = SpUtils.isShowTodayDurationEnabled(this)
            val message = if (current)
                LanguageUtils.getString(this@SettingsPage, R.string.dialog_msg_today_duration_on)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.dialog_msg_today_duration_off)
            showMaterialDialog(
                AlertDialog.Builder(this)
                    .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.dialog_title_today_duration))
                    .setMessage(message)
                    .setPositiveButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_ok)) { _, _ ->
                        SpUtils.setShowTodayDurationEnabled(this, !current)
                        btnShowTodayDuration.text = if (!current)
                            LanguageUtils.getString(this@SettingsPage, R.string.btn_show_today_duration_on)
                        else
                            LanguageUtils.getString(this@SettingsPage, R.string.btn_show_today_duration_off)
                        ToastUtils.show(this@SettingsPage, 
                            if (!current) LanguageUtils.getString(this@SettingsPage, R.string.toast_today_duration_on)
                            else LanguageUtils.getString(this@SettingsPage, R.string.toast_today_duration_off)
                        )
                    }
                    .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
            )
        }

        initVideoNotifyProgressButton()
    }

    private fun initVideoNotifyProgressButton() {
        val btn = findViewById<Button>(R.id.btn_video_notify_progress) ?: return
        btn.text = if (SpUtils.isVideoNotifyProgressEnabled(this))
            LanguageUtils.getString(this@SettingsPage, R.string.btn_video_notify_progress_on)
        else
            LanguageUtils.getString(this@SettingsPage, R.string.btn_video_notify_progress_off)
        btn.setOnClickListener {
            val current = SpUtils.isVideoNotifyProgressEnabled(this)
            val message = if (current)
                LanguageUtils.getString(this@SettingsPage, R.string.dialog_msg_video_notify_progress_on)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.dialog_msg_video_notify_progress_off)
            showMaterialDialog(
                AlertDialog.Builder(this)
                    .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.dialog_title_video_notify_progress))
                    .setMessage(message)
                    .setPositiveButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_ok)) { _, _ ->
                        val newMode = !current
                        SpUtils.setVideoNotifyProgressEnabled(this, newMode)
                        btn.text = if (newMode)
                            LanguageUtils.getString(this@SettingsPage, R.string.btn_video_notify_progress_on)
                        else
                            LanguageUtils.getString(this@SettingsPage, R.string.btn_video_notify_progress_off)
                        ToastUtils.show(this@SettingsPage, 
                            if (newMode) LanguageUtils.getString(this@SettingsPage, R.string.toast_video_notify_progress_on)
                            else LanguageUtils.getString(this@SettingsPage, R.string.toast_video_notify_progress_off)
                        )
                        VideoPlaybackService.refreshProgressSetting(this)
                        MusicPlayerService.refreshProgressSetting(this)
                    }
                    .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
            )
        }
    }

    private fun setupCollapsibleSections() {
        val collapseConfigs = listOf(
            R.id.title_section_display to R.id.btn_collapse_display,
            R.id.title_section_background to R.id.btn_collapse_background,
            R.id.title_section_music to R.id.btn_collapse_music,
            R.id.title_section_ui_nav to R.id.btn_collapse_ui_nav,
            R.id.title_section_tools to R.id.btn_collapse_tools,
            R.id.title_section_other to R.id.btn_collapse_other
        )

        val collapsedSet = SpUtils.getCollapsedSections(this).toMutableSet()
        val collapseButtons = mutableListOf<Button>()

        for ((cardId, buttonId) in collapseConfigs) {
            val card = findViewById<LinearLayout>(cardId) ?: continue
            val button = findViewById<Button>(buttonId) ?: continue
            val key = cardId.toString()

            button.text = LanguageUtils.getString(this@SettingsPage, R.string.btn_collapse)

            if (key in collapsedSet) {
                for (i in 1 until card.childCount) {
                    card.getChildAt(i).visibility = View.GONE
                }
                button.text = LanguageUtils.getString(this@SettingsPage, R.string.btn_restore_section)
            }

            button.setOnClickListener {
                val collapsed = button.text.toString() == LanguageUtils.getString(this@SettingsPage, R.string.btn_collapse)
                if (collapsed) {
                    for (i in 1 until card.childCount) {
                        card.getChildAt(i).visibility = View.GONE
                    }
                    button.text = LanguageUtils.getString(this@SettingsPage, R.string.btn_restore_section)
                    collapsedSet.add(key)
                } else {
                    for (i in 1 until card.childCount) {
                        val child = card.getChildAt(i)
                        if (child.id == R.id.btn_adjust_search_button) {
                            child.visibility =
                                if (SpUtils.getSearchMode(this)) View.GONE else View.VISIBLE
                        } else {
                            child.visibility = View.VISIBLE
                        }
                    }
                    button.text = LanguageUtils.getString(this@SettingsPage, R.string.btn_collapse)
                    collapsedSet.remove(key)
                }
                SpUtils.setCollapsedSections(this, collapsedSet)
            }
            collapseButtons.add(button)
        }
    }
    private fun updateBottomNavButtonText() {
        findViewById<Button>(R.id.btn_bottom_nav)?.text =
            if (SpUtils.getBottomNavEnabled(this))
                LanguageUtils.getString(this@SettingsPage, R.string.btn_bottom_nav_on)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.btn_bottom_nav_off)
    }

    private fun updateClickFxButtonText() {
        findViewById<Button>(R.id.btn_toggle_click_fx)?.text = LanguageUtils.getString(
            this@SettingsPage, R.string.btn_toggle_click_fx,
            if (SpUtils.getClickFxEnabled(this))
                LanguageUtils.getString(this@SettingsPage, R.string.state_on)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.state_off)
        )
    }

    private fun updateAutoCacheButtonText() {
        if (::btnAutoCache.isInitialized) {
            btnAutoCache.text = if (SpUtils.isAutoCacheEnabled(this))
                LanguageUtils.getString(this@SettingsPage, R.string.btn_auto_cache_on)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.btn_auto_cache_off)
        }
    }

    private fun setupFloatingLabel(container: FrameLayout, label: TextView, editText: EditText) {
        val density = resources.displayMetrics.density
        val floatOffset = -12 * density
        val accentColor = Color.parseColor("#99002BFF")
        val normalColor = Color.parseColor("#888888")

        fun update(animate: Boolean = true) {
            val shouldFloat = editText.hasFocus() || editText.text.isNotEmpty()
            val targetY = if (shouldFloat) floatOffset else 0f
            val targetScale = if (shouldFloat) 0.8f else 1f
            val targetColor = if (shouldFloat) accentColor else normalColor

            if (animate) {
                label.animate().translationY(targetY).scaleX(targetScale).scaleY(targetScale)
                    .setDuration(200).start()
            } else {
                label.translationY = targetY
                label.scaleX = targetScale
                label.scaleY = targetScale
            }
            label.setTextColor(targetColor)
        }

        editText.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> update() }
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { update() }
        })
        update(animate = false)
    }

    private fun applyBackground() {
        val alpha = tempBackgroundAlpha.coerceAtLeast(0)
        BackgroundHelper.applyBackground(this, bgHost, alpha)
    }

    private fun showLanguageDialog() {
        val languages = LanguageUtils.getSupportedLanguages(this)
        val currentLang = LanguageUtils.getLanguage(this)
        val names = languages.map { it.second }.toTypedArray()
        var checkedIndex = languages.indexOfFirst { it.first == currentLang }
        if (checkedIndex < 0) checkedIndex = 0

        AlertDialog.Builder(this)
            .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.setting_language))
            .setSingleChoiceItems(names, checkedIndex) { dialog, which ->
                val selectedCode = languages[which].first
                dialog.dismiss()
                if (selectedCode == LanguageUtils.FOLLOW_SYSTEM || selectedCode == LanguageUtils.BUILTIN_LANG) {
                    SettingsStore.saveLanguage(selectedCode)
                    LanguageUtils.setAppLanguage(activity, selectedCode)
                    return@setSingleChoiceItems
                }
                if (!LanguageUtils.isLanguageInstalled(this, selectedCode)) {
                    showMaterialDialog(
                        AlertDialog.Builder(this)
                            .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.language_not_installed_title))
                            .setMessage(LanguageUtils.getString(this@SettingsPage, R.string.language_not_installed_msg, languages[which].second))
                            .setPositiveButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_ok)) { _, _ ->
                                downloadAndApplyLanguage(selectedCode)
                            }
                            .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
                    )
                } else if (LanguageUtils.needsUpdate(this, selectedCode)) {
                    showMaterialDialog(
                        AlertDialog.Builder(this)
                            .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.language_update_title))
                            .setMessage(LanguageUtils.getString(this@SettingsPage, R.string.language_update_msg, languages[which].second))
                            .setPositiveButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_ok)) { _, _ ->
                                downloadAndApplyLanguage(selectedCode)
                            }
                            .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
                    )
                } else {
                    SettingsStore.saveLanguage(selectedCode)
                    LanguageUtils.setAppLanguage(activity, selectedCode)
                }
            }
            .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
            .also { showMaterialDialog(it) }
    }

    private fun downloadAndApplyLanguage(code: String) {
        ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.language_downloading))
        AppExecutors.io.execute {
            val ok = LanguageUtils.downloadLanguage(this, code)
            runOnUiThread {
                if (ok) {
                    SettingsStore.saveLanguage(code)
                    LanguageUtils.setAppLanguage(activity, code)
                } else {
                    ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.language_download_failed))
                }
            }
        }    }
    private fun applySettingsToUI() {
        val fontColor = SpUtils.getFontColor(this)
        etFontColor.setText(fontColor)
        try {
            val colorInt = Color.parseColor(fontColor)
            etFontColor.setTextColor(colorInt)
            val hsv = FloatArray(3)
            Color.colorToHSV(colorInt, hsv)
            sbFontColorHue.progress = hsv[0].toInt()
            tvFontColorPreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_color, fontColor)
        } catch (e: Exception) {
            etFontColor.setTextColor(0xFFFFFFFF.toInt())
            sbFontColorHue.progress = 0
            tvFontColorPreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_color, "#FFFFFF")
        }

        val fontSize = SpUtils.getFontSize(this)
        sbFontSize.progress = fontSize
        etFontSizeInput.setText(String.format(Locale.getDefault(), "%d", fontSize))
        tvFontSizePreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_font_size, fontSize)
        tvFontSizePreview.textSize = fontSize.toFloat()

        val alpha = SpUtils.getBackgroundAlpha(this)
        sbBackgroundAlpha.progress = alpha
        tempBackgroundAlpha = alpha
        tvAlphaValue.text = LanguageUtils.getString(this@SettingsPage, R.string.current_brightness, alpha)

        findViewById<Button>(R.id.btn_volume_key_switch)!!.text =
            if (SpUtils.getVolumeKeySwitch(this))
                LanguageUtils.getString(this@SettingsPage, R.string.btn_volume_key_switch_on)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.btn_volume_key_switch_off)
        findViewById<Button>(R.id.btn_toggle_search_mode)!!.text =
            if (SpUtils.getSearchMode(this))
                LanguageUtils.getString(this@SettingsPage, R.string.btn_toggle_search_page)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.btn_toggle_search_button)
        btnAdjustSearchButton.visibility =
            if (SpUtils.getSearchMode(this)) View.GONE else View.VISIBLE

        val modeText = when (SpUtils.getAudioFocusMode(this)) {
            SpUtils.AUDIO_FOCUS_CALL_LEVEL -> LanguageUtils.getString(this@SettingsPage, R.string.play_mode_exclusive_strong)
            SpUtils.AUDIO_FOCUS_FULL_EXCLUSIVE -> LanguageUtils.getString(this@SettingsPage, R.string.play_mode_full_medium)
            SpUtils.AUDIO_FOCUS_TRANSIENT -> LanguageUtils.getString(this@SettingsPage, R.string.play_mode_transient_weak)
            else -> LanguageUtils.getString(this@SettingsPage, R.string.play_mode_default)
        }
        findViewById<Button>(R.id.btn_switch_play_mode)!!.text =
            LanguageUtils.getString(this@SettingsPage, R.string.btn_play_mode_format, modeText)

        updateSearchBtnStyleButtonText()

        val dialogBgColor = SpUtils.getDialogBgColor(this)
        try {
            val color = Color.parseColor(dialogBgColor)
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            findViewById<SeekBar>(R.id.sb_dialog_bg_hue)!!.progress = hsv[0].toInt()
            findViewById<TextView>(R.id.tv_dialog_bg_preview)!!.text =
                LanguageUtils.getString(this@SettingsPage, R.string.current_color, dialogBgColor)
            findViewById<View>(R.id.view_dialog_bg_sample)!!.setBackgroundColor(color)
        } catch (_: Exception) {
        }

        val dialogFontColor = SpUtils.getDialogFontColor(this)
        if (dialogFontColor.isEmpty()) {
            val globalColor = try {
                Color.parseColor(SpUtils.getFontColor(this))
            } catch (_: Exception) {
                Color.WHITE
            }
            val hsv = FloatArray(3)
            Color.colorToHSV(globalColor, hsv)
            findViewById<SeekBar>(R.id.sb_dialog_font_hue)!!.progress = hsv[0].toInt()
            findViewById<TextView>(R.id.tv_dialog_font_preview)!!.text =
                LanguageUtils.getString(this@SettingsPage, R.string.current_dialog_font_global_format, SpUtils.getFontColor(this))
            findViewById<View>(R.id.view_dialog_font_sample)!!.setBackgroundColor(globalColor)
        } else {
            try {
                val color = Color.parseColor(dialogFontColor)
                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                findViewById<SeekBar>(R.id.sb_dialog_font_hue)!!.progress = hsv[0].toInt()
                findViewById<TextView>(R.id.tv_dialog_font_preview)!!.text =
                    LanguageUtils.getString(this@SettingsPage, R.string.current_color, dialogFontColor)
                findViewById<View>(R.id.view_dialog_font_sample)!!.setBackgroundColor(color)
            } catch (_: Exception) {
            }
        }

        val dialogAlpha = SpUtils.getDialogAlpha(this)
        findViewById<SeekBar>(R.id.sb_dialog_alpha)!!.progress = dialogAlpha
        findViewById<TextView>(R.id.tv_dialog_alpha_preview)!!.text =
            LanguageUtils.getString(this@SettingsPage, R.string.current_alpha, dialogAlpha)

        updateAutoCacheButtonText()
        updateBottomNavButtonText()
        updateClickFxButtonText()
    }

    private fun loadCurrentSettings() {
        if (SpUtils.isAutoLoadDefaultEnabled(this)) {
            SettingsStore.loadFromFile(this)
        }
        applySettingsToUI()
    }

    private fun updateSearchBtnStyleButtonText() {
        btnSearchBtnStyle.text = if (SpUtils.isSearchBtnTransparentStyle(this))
            LanguageUtils.getString(this@SettingsPage, R.string.btn_search_style_restore)
        else
            LanguageUtils.getString(this@SettingsPage, R.string.btn_search_style_transparent)
    }
    private fun pickDefaultBackground() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_DEFAULT_BG)
        } catch (e: android.content.ActivityNotFoundException) {
            ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.toast_no_file_picker))
        }
    }

    private fun restoreDefaultSettings() {
        AlertDialog.Builder(this)
            .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.dialog_title_restore_default))
            .setMessage(LanguageUtils.getString(this@SettingsPage, R.string.dialog_msg_restore_default))
            .setPositiveButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_ok)) { _, _ ->
                val defaultBg = SpUtils.getDefaultBackgroundPath(this)
                SpUtils.clearAll(this)
                if (defaultBg.isNotEmpty()) SpUtils.saveDefaultBackgroundPath(this, defaultBg)

                SettingsStore.deleteSettingsFile()

                tempBackgroundAlpha = 100
                SpUtils.saveBackgroundAlpha(this, 100)
                sbBackgroundAlpha.progress = 100
                tvAlphaValue.text = LanguageUtils.getString(this@SettingsPage, R.string.current_brightness, 100)

                etFontColor.setText(R.string.default_font_color)
                SpUtils.saveFontColor(this, "#FFFFFF")
                sbFontColorHue.progress = 0
                tvFontColorPreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_color, "#FFFFFF")

                sbFontSize.progress = 15
                SpUtils.saveFontSize(this, 15)
                etFontSizeInput.setText(String.format(Locale.getDefault(), "%d", 15))
                tvFontSizePreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_font_size, 15)
                tvFontSizePreview.textSize = 15f

                findViewById<Button>(R.id.btn_volume_key_switch)!!.text =
                    LanguageUtils.getString(this@SettingsPage, R.string.btn_volume_key_switch_off)
                findViewById<Button>(R.id.btn_toggle_search_mode)!!.text =
                    LanguageUtils.getString(this@SettingsPage, R.string.btn_toggle_search_page)
                findViewById<Button>(R.id.btn_switch_play_mode)!!.text =
                    LanguageUtils.getString(this@SettingsPage, R.string.btn_play_mode_format, LanguageUtils.getString(this@SettingsPage, R.string.play_mode_exclusive_strong))
                updateSearchBtnStyleButtonText()
                findViewById<Button>(R.id.btn_video_notify_progress)?.text =
                    LanguageUtils.getString(this@SettingsPage, R.string.btn_video_notify_progress_on)

                SpUtils.saveDialogBgColor(this, "#FFFFFF")
                SpUtils.saveDialogFontColor(this, "")
                SpUtils.saveDialogAlpha(this, 100)

                val white = Color.WHITE
                findViewById<SeekBar>(R.id.sb_dialog_bg_hue)!!.progress = 0
                findViewById<TextView>(R.id.tv_dialog_bg_preview)!!.text =
                    LanguageUtils.getString(this@SettingsPage, R.string.current_color, "#FFFFFF")
                findViewById<View>(R.id.view_dialog_bg_sample)!!.setBackgroundColor(white)

                findViewById<SeekBar>(R.id.sb_dialog_font_hue)!!.progress = 0
                findViewById<TextView>(R.id.tv_dialog_font_preview)!!.text =
                    LanguageUtils.getString(this@SettingsPage, R.string.current_dialog_font_global_format, "#FFFFFF")
                findViewById<View>(R.id.view_dialog_font_sample)!!.setBackgroundColor(white)

                findViewById<SeekBar>(R.id.sb_dialog_alpha)!!.progress = 100
                findViewById<TextView>(R.id.tv_dialog_alpha_preview)!!.text =
                    LanguageUtils.getString(this@SettingsPage, R.string.current_alpha, 100)

                applyBackground()
                ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.toast_settings_restored))
            }
            .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
            .also { showMaterialDialog(it) }
    }

    private fun showMaterialDialog(builder: AlertDialog.Builder): AlertDialog {
            return DialogHelper.createStyledDialog(activity, builder, boldItalicAllViews = true)
        }

    private val toggleDialogs by lazy {
        SettingsToggleDialogs(
            this,
            { showMaterialDialog(it) },
            onSearchModeChanged = { refreshBottomNav() },
            onClickFxChanged = {
                updateClickFxButtonText()
                (activity as? MainPagerActivity)?.refreshClickFx()
            },
            onSearchBtnStyleChanged = { updateSearchBtnStyleButtonText() }
        )
    }

    private val storageDialogs by lazy {
        StorageDialogs(this, layoutInflater, userFontColor) { showMaterialDialog(it) }
    }

    private val userFontColor: Int
            get() = try {
                Color.parseColor(SpUtils.getFontColor(this))
            } catch (e: Exception) {
                Color.WHITE
            }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return

        when (requestCode) {
            REQUEST_PICK_DEFAULT_BG -> {
                val uri = data.data ?: return
                val newPath = SettingsStore.copyDefaultBackground(this, uri)
                if (newPath != null) {
                    SpUtils.saveDefaultBackgroundPath(this, newPath)
                    applyBackground()
                    ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.toast_default_bg_set))
                }
            }
        }
    }

    private fun showSendNotificationDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
            background = null
        }
        val etTitle = EditText(this).apply {
            hint = LanguageUtils.getString(this@SettingsPage, R.string.hint_notification_title)
            setTextColor(userFontColor)
            setHintTextColor(Color.GRAY)
            background = resources.getDrawable(R.drawable.edittext_bg, null)
            setPadding(12, 12, 12, 12)
        }
        val etContent = EditText(this).apply {
            hint = LanguageUtils.getString(this@SettingsPage, R.string.hint_notification_content)
            setTextColor(userFontColor)
            setHintTextColor(Color.GRAY)
            minLines = 3
            gravity = Gravity.TOP
            background = resources.getDrawable(R.drawable.edittext_bg, null)
            setPadding(12, 12, 12, 12)
        }
        layout.addView(etTitle)
        layout.addView(etContent)

        showMaterialDialog(
            AlertDialog.Builder(this)
                .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.dialog_title_send_notification))
                .setView(layout)
                .setPositiveButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_ok)) { _, _ ->
                    val title = etTitle.text.toString().trim()
                        .ifEmpty { LanguageUtils.getString(this@SettingsPage, R.string.notification_default_title) }
                    val content = etContent.text.toString().trim()
                    if (content.isEmpty()) ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.toast_content_empty))
                    else sendCustomNotification(title, content)
                }
                .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
        )
    }
    private fun applyBoldItalicGlobally() {
        val typeface = Typeface.defaultFromStyle(Typeface.BOLD_ITALIC)
        DialogHelper.setTypefaceRecursive(pageView!!, typeface)
    }
    private fun sendCustomNotification(title: String, content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                "custom_user_channel",
                LanguageUtils.getString(this@SettingsPage, R.string.notification_default_title),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val builder: Notification.Builder = Notification.Builder(this, "custom_user_channel")
        var id: Int
        do {
            id = Random.nextInt(Int.MAX_VALUE)
        } while (id == 1001)
        builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        nm.notify(id, builder.build())
        ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.toast_notification_sent))
    }

    override fun onBackPressed(): Boolean = false
}
