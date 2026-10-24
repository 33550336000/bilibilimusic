package com.tilixibiesi.ui.page
import com.tilixibiesi.util.ToastUtils

import com.tilixibiesi.data.SettingsStore
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.FootprintUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.util.DialogHelper
import com.tilixibiesi.util.FontUtils
import com.tilixibiesi.ui.widget.HsvColorPicker
import com.tilixibiesi.ui.MainPagerActivity
import com.tilixibiesi.ui.SettingsToggleDialogs
import com.tilixibiesi.ui.StorageDialogs
import com.tilixibiesi.ui.widget.LiquidGlassLayout
import com.tilixibiesi.R
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.service.VideoPlaybackService

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import java.util.Locale
import android.graphics.Typeface

class SettingsPage(base: Context) : BasePage(base) {
    companion object {
        /** 与 card_background_semi 的 32dp 圆角对齐，切换玻璃时视觉不跳。 */
        private const val CARD_CORNER_DP = 32f
        private const val CARD_BLUR_DP = 5f

        private val CARD_IDS = intArrayOf(
            R.id.title_section_display,
            R.id.title_section_background,
            R.id.title_section_music,
            R.id.title_section_ui_nav,
            R.id.title_section_tools,
            R.id.title_section_other
        )
    }

    private lateinit var btnAutoCache: Button
    private lateinit var etFontColor: EditText
    private lateinit var sbFontSize: SeekBar
    private lateinit var tvFontSizePreview: TextView
    private lateinit var etFontSizeInput: EditText
    private lateinit var sbBackgroundAlpha: SeekBar
    private lateinit var tvAlphaValue: TextView
    private lateinit var btnRestoreDefault: Button
    private lateinit var rootView: View
    private lateinit var tvFontColorPreview: TextView
    private lateinit var fontColorPicker: HsvColorPicker
    private lateinit var dialogBgColorPicker: HsvColorPicker
    private lateinit var dialogFontColorPicker: HsvColorPicker
    private var etFontColorSyncing = false
    private lateinit var btnAdjustSearchButton: Button
    private lateinit var btnSearchBtnStyle: Button
    private lateinit var btnSaveAsDefault: Button
    private lateinit var btnAutoLoadDefault: Button

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_settings)
        findViewById<TextView>(R.id.title_settings_page)?.text = LanguageUtils.getString(this@SettingsPage, R.string.settings_title)
        rootView = findViewById<View>(R.id.settings_root)!!
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
        applyCardGlass()
        setupCollapsibleSections()
    }

    /**
     * 六个设置卡片改用液态玻璃，折射源是主页那层全局背景。
     *
     * 用单独的「液态玻璃卡片」开关，不再跟着控制栏那个开关走。
     * 玻璃不可用（API < 33 或着色器编译失败）时整段跳过：卡片保持 XML 里的
     * `card_background_semi`，与改动前完全一致。
     */
    private fun applyCardGlass() {
        val host = activity as? MainPagerActivity ?: return
        val enabled = SpUtils.getLiquidGlassCardsEnabled(this) && LiquidGlassLayout.isAvailable()
        for (id in CARD_IDS) {
            val card = findViewById<LiquidGlassLayout>(id) ?: continue
            if (!enabled) {
                // 关掉玻璃后交还给半透明卡片底色：solidWhenDisabled 必须关，
                // 否则 onDraw 会自己铺一层不透明白底，把 card_background_semi 盖掉。
                card.solidWhenDisabled = false
                card.background = resources.getDrawable(R.drawable.card_background_semi, null)
                card.glassEnabled = false
                continue
            }
            // 底色交还给玻璃自己画，否则会盖住折射结果
            card.background = null
            card.solidWhenDisabled = false
            card.tiltEnabled = false
            // 卡片在 ScrollView 里：手指按下去多半是要滑动，不是要按这张卡。
            // 开着按压会在每次滑动时先亮一下再灭，读起来像误触。
            card.pressEnabled = false
            card.cornerRadiusDp = CARD_CORNER_DP
            card.blurRadiusDp = CARD_BLUR_DP
            card.backdropSource = host.backgroundLayer
            card.glassEnabled = true
        }
        // 控制栏的玻璃抓的是整层 contentLayer（含设置页），卡片外观变了要重抓一次，
        // 否则开关前后那一小块在控制栏里还是旧画面。
        host.invalidateGlassBackdrop()
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
        initFontSection()
        initDefaultSettingsSection()
        initDialogStyleSection()
        initSwitchSection()
        initSliderSection()
        initVideoNotifyProgressButton()
    }

    private fun initFontSection() {
        val containerFontColor = findViewById<FrameLayout>(R.id.container_font_color)!!
        etFontColor = findViewById<EditText>(R.id.et_font_color)!!
        val labelFontColor = findViewById<TextView>(R.id.label_font_color)!!
        setupFloatingLabel(containerFontColor, labelFontColor, etFontColor)
        val containerFontSize = findViewById<FrameLayout>(R.id.container_font_size)!!
        etFontSizeInput = findViewById<EditText>(R.id.et_font_size_input)!!
        val labelFontSize = findViewById<TextView>(R.id.label_font_size)!!
        setupFloatingLabel(containerFontSize, labelFontSize, etFontSizeInput)

        val sbFontColorHue = findViewById<SeekBar>(R.id.sb_font_color_hue)!!
        tvFontColorPreview = findViewById<TextView>(R.id.tv_font_color_preview)!!
        fontColorPicker = HsvColorPicker(
            sbFontColorHue,
            findViewById(R.id.sb_font_color_saturation)!!,
            findViewById(R.id.sb_font_color_value)!!,
            resources.displayMetrics.density * 18f
        )
        fontColorPicker.onColorChanged = { color ->
            applyPickedFontColor(color)
        }

        etFontColor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (etFontColorSyncing) return
                val text = s.toString().trim()
                if (text.isEmpty()) return
                try {
                    val color = Color.parseColor(text)
                    // 输入框可以填任意颜色（含纯黑/纯白/灰色）：同步回三个滑块
                    etFontColorSyncing = true
                    fontColorPicker.setColor(color)
                    etFontColorSyncing = false
                    applyPickedFontColor(color, syncInput = false)
                } catch (_: Exception) {
                    etFontColorSyncing = false
                }
            }
        })

        sbFontSize = findViewById<SeekBar>(R.id.sb_font_size)!!
        tvFontSizePreview = findViewById<TextView>(R.id.tv_font_size_preview)!!
        sbBackgroundAlpha = findViewById<SeekBar>(R.id.sb_background_alpha)!!
        tvAlphaValue = findViewById<TextView>(R.id.tv_alpha_value)!!
        findViewById<Button>(R.id.btn_select_default_background)!!.setOnClickListener { pickDefaultBackground() }
        btnRestoreDefault = findViewById<Button>(R.id.btn_restore_default)!!
        btnAdjustSearchButton = findViewById<Button>(R.id.btn_adjust_search_button)!!
        btnSearchBtnStyle = findViewById<Button>(R.id.btn_search_btn_style)!!
        findViewById<Button>(R.id.btn_blocked_words)!!.setOnClickListener { storageDialogs.showBlockedWordsManager() }
    }

    private fun initDefaultSettingsSection() {
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
                            SettingsStore.loadFromFileAsync(this@SettingsPage) {
                                // 加载设置可能触发语言切换并重建 Activity，
                                // 那时本页所属的 Activity 已销毁，再刷 UI 没有意义
                                if (activity.isDestroyed || pageView == null) return@loadFromFileAsync
                                applySettingsToUI()
                                // 同上：applySettingsToUI 会把「调整搜索按钮」点亮，
                                // 收起的卡片里不该出现它，这里补一次折叠状态
                                reapplyCollapsedSections()
                                applyBackground()
                            }
                        }

                        ToastUtils.show(this@SettingsPage, 
                            if (!current) LanguageUtils.getString(this@SettingsPage, R.string.toast_auto_load_default_enabled)
                            else LanguageUtils.getString(this@SettingsPage, R.string.toast_auto_load_default_disabled)
                        )
                    }
                    .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
            )
        }
    }

    private fun initDialogStyleSection() {
        val sbDialogBgHue = findViewById<SeekBar>(R.id.sb_dialog_bg_hue)!!
        val tvDialogBgPreview = findViewById<TextView>(R.id.tv_dialog_bg_preview)!!
        val viewDialogBgSample = findViewById<View>(R.id.view_dialog_bg_sample)!!

        val sbDialogFontHue = findViewById<SeekBar>(R.id.sb_dialog_font_hue)!!
        val tvDialogFontPreview = findViewById<TextView>(R.id.tv_dialog_font_preview)!!
        val viewDialogFontSample = findViewById<View>(R.id.view_dialog_font_sample)!!
        val btnDialogFontFollowGlobal = findViewById<Button>(R.id.btn_dialog_font_follow_global)!!

        val sbDialogAlpha = findViewById<SeekBar>(R.id.sb_dialog_alpha)!!
        val tvDialogAlphaPreview = findViewById<TextView>(R.id.tv_dialog_alpha_preview)!!

        dialogBgColorPicker = HsvColorPicker(
            sbDialogBgHue,
            findViewById(R.id.sb_dialog_bg_saturation)!!,
            findViewById(R.id.sb_dialog_bg_value)!!,
            resources.displayMetrics.density * 18f
        )
        dialogBgColorPicker.onColorChanged = { color ->
            val hex = String.format("#%06X", 0xFFFFFF and color)
            tvDialogBgPreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_color, hex)
            viewDialogBgSample.setBackgroundColor(color)
            SpUtils.saveDialogBgColor(this@SettingsPage, hex)
        }

        dialogFontColorPicker = HsvColorPicker(
            sbDialogFontHue,
            findViewById(R.id.sb_dialog_font_saturation)!!,
            findViewById(R.id.sb_dialog_font_value)!!,
            resources.displayMetrics.density * 18f
        )
        dialogFontColorPicker.onColorChanged = { color ->
            val hex = String.format("#%06X", 0xFFFFFF and color)
            tvDialogFontPreview.text = LanguageUtils.getString(this@SettingsPage, R.string.current_color, hex)
            viewDialogFontSample.setBackgroundColor(color)
            SpUtils.saveDialogFontColor(this@SettingsPage, hex)
        }

        btnDialogFontFollowGlobal.setOnClickListener {
            SpUtils.saveDialogFontColor(this@SettingsPage, "")
            val globalColor = FontUtils.color(this@SettingsPage)
            dialogFontColorPicker.setColor(globalColor)
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
    }

    private fun initSwitchSection() {
        // 「音乐与播放」卡片里的第一项：B站音乐播放音质
        val btnBiliAudioQuality = findViewById<Button>(R.id.btn_bili_audio_quality)!!
        toggleDialogs.updateBiliAudioQualityButtonText(btnBiliAudioQuality)
        btnBiliAudioQuality.setOnClickListener {
            toggleDialogs.showBiliAudioQualityDialog(btnBiliAudioQuality)
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

        val btnLiquidGlass = findViewById<Button>(R.id.btn_liquid_glass)!!
        updateLiquidGlassButtonText()
        btnLiquidGlass.setOnClickListener {
            val current = SpUtils.getLiquidGlassEnabled(this)
            val message = if (current)
                LanguageUtils.getString(this@SettingsPage, R.string.toast_liquid_glass_disabled)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.toast_liquid_glass_enabled)
            // showMaterialDialog 内部已经 create().show()，这里不能再套一层，
            // 否则同一个 builder 会被弹两次（曾经就是这个按钮一次弹两个框）。
            showMaterialDialog(
                AlertDialog.Builder(this)
                    .setTitle(LanguageUtils.getString(this@SettingsPage, R.string.dialog_title_liquid_glass))
                    .setMessage(getString(if (current)
                        R.string.dialog_msg_liquid_glass_off
                    else
                        R.string.dialog_msg_liquid_glass_on))
                    .setPositiveButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_ok)) { _, _ ->
                        SpUtils.setLiquidGlassEnabled(this, !current)
                        updateLiquidGlassButtonText()
                        (activity as? MainPagerActivity)?.refreshFloatingPlayerBar()
                        ToastUtils.show(this@SettingsPage, message)
                    }
                    .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
            )
        }

        // 设置页卡片的玻璃单独控制，不再跟着上面那个控制栏开关走
        setupToggleButton(
            buttonId = R.id.btn_liquid_glass_cards,
            titleRes = R.string.btn_liquid_glass_cards,
            onLabelRes = R.string.btn_liquid_glass_cards_on,
            offLabelRes = R.string.btn_liquid_glass_cards_off,
            onMessageRes = R.string.dialog_msg_liquid_glass_cards_on,
            offMessageRes = R.string.dialog_msg_liquid_glass_cards_off,
            enabledToastRes = R.string.toast_liquid_glass_cards_enabled,
            disabledToastRes = R.string.toast_liquid_glass_cards_disabled,
            isEnabled = { SpUtils.getLiquidGlassCardsEnabled(this) },
            setEnabled = { SpUtils.setLiquidGlassCardsEnabled(this, it) },
            onChanged = { applyCardGlass() }
        )

        // 主页面歌曲列表每行右侧的两个按钮：+（加入歌单）与 ×（删除）
        setupToggleButton(
            buttonId = R.id.btn_main_add_button,
            titleRes = R.string.btn_main_add_button,
            onLabelRes = R.string.btn_main_add_button_on,
            offLabelRes = R.string.btn_main_add_button_off,
            onMessageRes = R.string.dialog_msg_main_add_button_on,
            offMessageRes = R.string.dialog_msg_main_add_button_off,
            enabledToastRes = R.string.toast_main_add_button_enabled,
            disabledToastRes = R.string.toast_main_add_button_disabled,
            isEnabled = { SpUtils.isMainAddButtonVisible(this) },
            setEnabled = { SpUtils.setMainAddButtonVisible(this, it) }
        )

        setupToggleButton(
            buttonId = R.id.btn_main_delete_button,
            titleRes = R.string.btn_main_delete_button,
            onLabelRes = R.string.btn_main_delete_button_on,
            offLabelRes = R.string.btn_main_delete_button_off,
            onMessageRes = R.string.dialog_msg_main_delete_button_on,
            offMessageRes = R.string.dialog_msg_main_delete_button_off,
            enabledToastRes = R.string.toast_main_delete_button_enabled,
            disabledToastRes = R.string.toast_main_delete_button_disabled,
            isEnabled = { SpUtils.isMainDeleteButtonVisible(this) },
            setEnabled = { SpUtils.setMainDeleteButtonVisible(this, it) }
        )

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

        // 「数据与功能」卡片里的第一项：完整B站源开关
        val btnFullBiliSource = findViewById<Button>(R.id.btn_full_bili_source)!!
        toggleDialogs.updateFullBiliSourceButtonText(btnFullBiliSource)
        btnFullBiliSource.setOnClickListener {
            toggleDialogs.showFullBiliSourceDialog(btnFullBiliSource)
        }

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
    }

    private fun initSliderSection() {
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
                tvAlphaValue.text = LanguageUtils.getString(this@SettingsPage, R.string.current_brightness, progress)
                if (fromUser) {
                    SpUtils.saveBackgroundAlpha(this@SettingsPage, progress)
                    applyBackground()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })


        btnRestoreDefault.setOnClickListener { restoreDefaultSettings() }
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

    internal fun applyBackground() {
        (activity as? MainPagerActivity)?.refreshBackground()
    }

    /** 包装字体色同步标记，避免拆出去的文件直接摸私有字段却漏掉复位。 */
    internal fun beginFontColorSync() {
        etFontColorSyncing = true
    }

    internal fun endFontColorSync() {
        etFontColorSyncing = false
    }

    /**
     * 应用一个已确定的字体颜色：刷新预览/输入框、保存设置。
     *
     * @param syncInput 是否需要把颜色回写到输入框（来自滑块时为 true；
     *   来自输入框本身时为 false，否则会把用户正在编辑的文本改掉）
     */
    private fun applyPickedFontColor(color: Int, syncInput: Boolean = true) {
        val hex = String.format("#%06X", 0xFFFFFF and color)
        tvFontColorPreview.text =
            LanguageUtils.getString(this@SettingsPage, R.string.current_color, hex)
        if (syncInput) {
            etFontColorSyncing = true
            etFontColor.setText(hex)
            etFontColor.setTextColor(color)
            etFontColorSyncing = false
        }
        SpUtils.saveFontColor(this@SettingsPage, hex)
    }

    private fun loadCurrentSettings() {
        if (SpUtils.isAutoLoadDefaultEnabled(this)) {
            // 读盘放后台，回到主线程后再刷 UI，避免创建设置页时阻塞主线程
            SettingsStore.loadFromFileAsync(this) {
                applySettingsToUI()
                // applySettingsToUI 会无条件设置「调整搜索按钮」的可见性，
                // 而本回调可能晚于 setupCollapsibleSections 执行，
                // 届时会把已收起的卡片里那个按钮重新点亮。这里补一次折叠状态。
                reapplyCollapsedSections()
            }
        } else {
            applySettingsToUI()
        }
    }

    internal fun updateSearchBtnStyleButtonText() {
        btnSearchBtnStyle.text = if (SpUtils.isSearchBtnTransparentStyle(this))
            LanguageUtils.getString(this@SettingsPage, R.string.btn_search_style_restore)
        else
            LanguageUtils.getString(this@SettingsPage, R.string.btn_search_style_transparent)
    }

    /** 供拆出去的扩展文件刷新「完整B站源」按钮文案。 */
    internal fun refreshFullBiliSourceLabel(btn: Button) {
        toggleDialogs.updateFullBiliSourceButtonText(btn)
    }

    /** 供拆出去的扩展文件刷新「播放音质」按钮文案。 */
    internal fun refreshBiliAudioQualityLabel(btn: Button) {
        toggleDialogs.updateBiliAudioQualityButtonText(btn)
    }

    // 取色器是页内私有控件，拆出去的文件通过这三个桥接方法回填颜色
    internal fun applyFontColorToPicker(color: Int) {
        fontColorPicker.setColor(color)
    }

    internal fun applyDialogBgColorToPicker(color: Int) {
        dialogBgColorPicker.setColor(color)
    }

    internal fun applyDialogFontColorToPicker(color: Int) {
        dialogFontColorPicker.setColor(color)
    }

    /** 统一风格的对话框；拆到 SettingsPageNotification 的扩展函数也会用到。 */
    internal fun showMaterialDialog(builder: AlertDialog.Builder): AlertDialog {
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

    /** 当前生效的字体颜色；设置页自身与拆出去的扩展文件共用。 */
    internal val userFontColor: Int
        get() = FontUtils.color(this)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // 选择默认背景的处理链已拆到 SettingsPageActions.kt
        if (handlePickDefaultBackgroundResult(requestCode, resultCode, data)) return
    }

    private fun applyBoldItalicGlobally() {
        val typeface = Typeface.defaultFromStyle(Typeface.BOLD_ITALIC)
        DialogHelper.setTypefaceRecursive(pageView!!, typeface)
    }
    override fun onBackPressed(): Boolean = false
}
