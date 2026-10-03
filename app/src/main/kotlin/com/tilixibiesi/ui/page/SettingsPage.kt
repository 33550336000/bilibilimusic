package com.tilixibiesi.ui.page
import com.tilixibiesi.util.ToastUtils

import com.tilixibiesi.data.SettingsStore
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.FootprintUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.util.BackgroundHelper
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

/**
 * 设置页（原 SettingsActivity）。
 *
 * 与原先的差异：
 *  - 继承 [BasePage] 而非 Activity，作为 MainPagerActivity 内的一页存在；
 *  - 原本的 GestureDetector.onFling 右滑返回已交由 HorizontalPager 统一处理，
 *    页面自身不再持有手势检测器，也不再调用 overridePendingTransition；
 *  - 原来 Activity.dispatchTouchEvent 中「点击输入框外收起软键盘」的逻辑，
 *    改为挂在页面根 View 上的 OnTouchListener（无子 View 消费 DOWN 时回调，正好等价于点空白）；
 *  - 原来启动 MainActivity 进入「调整搜索按钮位置」的逻辑改为切到歌曲页并置位标志。
 */
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
    private lateinit var btnBlockedWords: Button
    private lateinit var sbFontColorHue: SeekBar
    private lateinit var tvFontColorPreview: TextView
    private lateinit var btnAdjustSearchButton: Button
    private lateinit var btnSearchBtnStyle: Button
    private lateinit var btnSaveAsDefault: Button
    private lateinit var btnAutoLoadDefault: Button

    // 该 OnTouchListener 仅做「点空白收键盘」且 return false 不消费事件，不属于点击处理，抑制无障碍提示
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_settings)
        // 布局 XML 的 @string 走 ResourcesImpl（绕过 LocalizedResources），
        // 页标题"设置"需按当前语言动态设置，切换语言后才会刷新为日语
        findViewById<TextView>(R.id.title_settings_page)?.text = LanguageUtils.getString(this@SettingsPage, R.string.settings_title)
        rootView = findViewById<View>(R.id.settings_root)!!
        // 原 Activity.dispatchTouchEvent 的等价实现：
        // 点击字体大小/颜色输入框以外的地方时，清除焦点并收起软键盘，避免光标滞留。
        // 挂在页面根 View 上——只有在没有子 View 消费该 ACTION_DOWN 时才回调，
        // 语义正好等价于"点到空白处"（点按钮时不会误清焦点）。
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
        // 折叠状态初始化放在 loadCurrentSettings/applyBackground 之后，
        // 避免 applySettingsToUI 重置了被收起的按钮可见性（如“调整搜索按钮位置”）
        setupCollapsibleSections()
    }

    /**
     * 让底部导航栏的增删立即生效。
     *
     * BaseActivity 把导航栏挂载在 onStart，开关变更后默认要等一次重建才可见；
     * 这里直接重跑宿主的挂载/移除逻辑，点完开关立刻看到结果。
     */
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

                        // 从关闭变为开启时，立即应用默认配置
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

        // ⭐ 底部导航栏开关：开启后在主活动底部显示导航按钮
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
                        // 立即增删导航栏，无需等到重建界面
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
            // 原实现会重新拉起 MainActivity 进入"调整搜索按钮位置"模式；
            // 现在歌曲页就在同一个 Activity 内，直接切页并置位一次性标志即可。
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

    /**
     * 视频通知进度条开关（默认开启）。
     *
     * 关掉后：通知不再带进度条，也不再有每秒一次的定时刷新，
     * 只剩标题与播放/暂停——即本功能上线前的行为。
     */
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
                        // 立即刷新正在挂出的通知：否则要等下一次状态变化
                        // （进度推进器 tick / 暂停 / 切集）才生效，用户会以为开关没用。
                        // 音乐与视频共用本开关，两个服务都要通知到。
                        VideoPlaybackService.refreshProgressSetting(this)
                        MusicPlayerService.refreshProgressSetting(this)
                    }
                    .setNegativeButton(LanguageUtils.getString(this@SettingsPage, R.string.btn_cancel), null)
            )
        }
    }

    // ===== 卡片折叠/展开功能 =====
    // 每张卡片的第一个子 View 是横向标题行（含标题 + 收起按钮）。
    // 收起：隐藏该行以外的所有直接子 View，只保留标题行，按钮文字变“恢复”。
    // 展开：恢复所有子 View，按钮文字变回“收起”。
    // 收起状态持久化：再次进入设置页时保持卡片收起/展开状态。
    private fun setupCollapsibleSections() {
        val collapseConfigs = listOf(
            R.id.title_section_display to R.id.btn_collapse_display,
            R.id.title_section_background to R.id.btn_collapse_background,
            R.id.title_section_music to R.id.btn_collapse_music,
            R.id.title_section_ui_nav to R.id.btn_collapse_ui_nav,
            R.id.title_section_tools to R.id.btn_collapse_tools,
            R.id.title_section_other to R.id.btn_collapse_other
        )

        // 读取已收起的卡片 id 集合（以字符串形式存储）
        val collapsedSet = SpUtils.getCollapsedSections(this).toMutableSet()
        val collapseButtons = mutableListOf<Button>()

        for ((cardId, buttonId) in collapseConfigs) {
            val card = findViewById<LinearLayout>(cardId) ?: continue
            val button = findViewById<Button>(buttonId) ?: continue
            val key = cardId.toString()

            // 布局 XML 的 @string 走 ResourcesImpl（绕过 LocalizedResources），
            // 收起按钮初始文字是内置中文；这里用代码按当前语言设为"收起"，
            // 使下方 collapsed 判断与"收起/恢复"切换在切换语言后始终一致
            button.text = LanguageUtils.getString(this@SettingsPage, R.string.btn_collapse)

            // 初始应用已保存的收起状态
            if (key in collapsedSet) {
                for (i in 1 until card.childCount) {
                    card.getChildAt(i).visibility = View.GONE
                }
                button.text = LanguageUtils.getString(this@SettingsPage, R.string.btn_restore_section)
            }

            button.setOnClickListener {
                val collapsed = button.text.toString() == LanguageUtils.getString(this@SettingsPage, R.string.btn_collapse)
                if (collapsed) {
                    // 收起：隐藏标题行以外的所有直接子 View
                    for (i in 1 until card.childCount) {
                        card.getChildAt(i).visibility = View.GONE
                    }
                    button.text = LanguageUtils.getString(this@SettingsPage, R.string.btn_restore_section)
                    collapsedSet.add(key)
                } else {
                    // 展开：恢复所有直接子 View
                    for (i in 1 until card.childCount) {
                        val child = card.getChildAt(i)
                        if (child.id == R.id.btn_adjust_search_button) {
                            // 该按钮的显隐由搜索模式决定，不能一律显示
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
    /** 底部导航栏按钮：标题 + 当前开关状态，状态文字不再夹带其他按钮的标题 */
    private fun updateBottomNavButtonText() {
        findViewById<Button>(R.id.btn_bottom_nav)?.text =
            if (SpUtils.getBottomNavEnabled(this))
                LanguageUtils.getString(this@SettingsPage, R.string.btn_bottom_nav_on)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.btn_bottom_nav_off)
    }

    /** 点击特效按钮：标题 + 开/关 */
    private fun updateClickFxButtonText() {
        findViewById<Button>(R.id.btn_toggle_click_fx)?.text = LanguageUtils.getString(
            this@SettingsPage, R.string.btn_toggle_click_fx,
            if (SpUtils.getClickFxEnabled(this))
                LanguageUtils.getString(this@SettingsPage, R.string.state_on)
            else
                LanguageUtils.getString(this@SettingsPage, R.string.state_off)
        )
    }

    /** “保存为默认设置”先弹确认框，确定后才写入 */
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
        BackgroundHelper.applyBackground(this, rootView, alpha)
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
                // 简体中文/跟随系统：内置，直接生效
                if (selectedCode == LanguageUtils.FOLLOW_SYSTEM || selectedCode == LanguageUtils.BUILTIN_LANG) {
                    SettingsStore.saveLanguage(selectedCode)
                    LanguageUtils.setAppLanguage(activity, selectedCode)
                    return@setSingleChoiceItems
                }
                // 其他语言：需检查是否已下载安装/是否有更新
                if (!LanguageUtils.isLanguageInstalled(this, selectedCode)) {
                    // 未安装：提示下载
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
                    // 已安装但有更新：提示更新（不强制）
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

    /** 下载指定语言资源并应用；成功则切换语言并重建，失败则提示 */
    private fun downloadAndApplyLanguage(code: String) {
        ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.language_downloading))
        Thread {
            val ok = LanguageUtils.downloadLanguage(this, code)
            runOnUiThread {
                if (ok) {
                    SettingsStore.saveLanguage(code)
                    LanguageUtils.setAppLanguage(activity, code)
                } else {
                    ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.language_download_failed))
                }
            }
        }.start()
    }
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
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        // 检测系统是否有文件选择器能处理该 Intent；没有则提示，避免崩溃
        val resolveInfo = packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )
        if (resolveInfo == null) {
            ToastUtils.show(this@SettingsPage, LanguageUtils.getString(this@SettingsPage, R.string.toast_no_file_picker))
            return
        }
        startActivityForResult(intent, REQUEST_PICK_DEFAULT_BG)
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
                // 恢复默认：进度条默认开启，与 SpUtils 的默认值保持一致
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

    /** 开关型确认弹窗：切换后按各自影响范围刷新 UI */
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

    /** 「数据与工具」卡片的三类文件对话框（缓存 / 屏蔽字 / 足迹） */
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
/** 将整个 Activity 视图树中的 TextView 设为粗斜体 */
    private fun applyBoldItalicGlobally() {
        val typeface = Typeface.defaultFromStyle(Typeface.BOLD_ITALIC)
        // 页面内没有 window，用 inflate 出来的页面根 View 代替 decorView
        DialogHelper.setTypefaceRecursive(pageView!!, typeface)
    }
    private fun sendCustomNotification(title: String, content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk 30：先建通知渠道，再用 2 参构造器
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

    /** 返回键：设置页自身不拦截，交回宿主（宿主会先回到歌曲页，再按才退出）。 */
    override fun onBackPressed(): Boolean = false
}
