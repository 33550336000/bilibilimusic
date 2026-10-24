package com.tilixibiesi.ui.page

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.util.ToastUtils

import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/*
 * 设置页的纯界面构件：可折叠卡片、浮动标签输入框、以及「点一下弹确认框再切换」
 * 的通用开关按钮。
 *
 * 这三块都不涉及具体的设置项语义，只处理视图与交互模板，
 * 从 SettingsPage 拆出来可以显著缩小主文件的体积。
 */

/**
 * 六个设置卡片支持折叠，折叠状态记在 SharedPreferences 里。
 *
 * 每个卡片保留标题行（第 0 个子 View），其余子 View 一起隐藏。
 * 展开时要特殊处理「调整搜索按钮」，它是否可见取决于搜索模式，不能一律设成 VISIBLE。
 */
internal fun SettingsPage.setupCollapsibleSections() {
    val collapseConfigs = collapseConfigs()

    val collapsedSet = SpUtils.getCollapsedSections(this).toMutableSet()

    for ((cardId, buttonId) in collapseConfigs) {
        val card = findViewById<LinearLayout>(cardId) ?: continue
        val button = findViewById<Button>(buttonId) ?: continue
        val key = cardId.toString()

        button.text = LanguageUtils.getString(this, R.string.btn_collapse)

        if (key in collapsedSet) {
            hideCardChildren(card)
            button.text = LanguageUtils.getString(this, R.string.btn_restore_section)
        }

        button.setOnClickListener {
            val collapsed =
                button.text.toString() == LanguageUtils.getString(this, R.string.btn_collapse)
            if (collapsed) {
                hideCardChildren(card)
                button.text = LanguageUtils.getString(this, R.string.btn_restore_section)
                collapsedSet.add(key)
            } else {
                showCardChildren(card)
                button.text = LanguageUtils.getString(this, R.string.btn_collapse)
                collapsedSet.remove(key)
            }
            SpUtils.setCollapsedSections(this, collapsedSet)
        }
    }
}

/**
 * 按已保存的折叠状态重新套用一遍。
 *
 * 为什么需要这个：设置页的读取是异步的（[SettingsStore.loadFromFileAsync]），
 * 回调可能在 [setupCollapsibleSections] 之后才回来，而它内部的
 * `applySettingsToUI` 会无条件把 `btn_adjust_search_button` 设成 VISIBLE，
 * 于是折叠卡片里就冒出这一个按钮。这里在异步回调之后重新套用折叠状态，
 * 让「折叠」始终是最后生效的那一步。
 */
internal fun SettingsPage.reapplyCollapsedSections() {
    val collapsedSet = SpUtils.getCollapsedSections(this)
    for ((cardId, buttonId) in collapseConfigs()) {
        val card = findViewById<LinearLayout>(cardId) ?: continue
        val button = findViewById<Button>(buttonId) ?: continue
        if (cardId.toString() in collapsedSet) {
            hideCardChildren(card)
            button.text = LanguageUtils.getString(this, R.string.btn_restore_section)
        }
    }
}

private fun collapseConfigs() = listOf(
    R.id.title_section_display to R.id.btn_collapse_display,
    R.id.title_section_background to R.id.btn_collapse_background,
    R.id.title_section_music to R.id.btn_collapse_music,
    R.id.title_section_ui_nav to R.id.btn_collapse_ui_nav,
    R.id.title_section_tools to R.id.btn_collapse_tools,
    R.id.title_section_other to R.id.btn_collapse_other
)

/** 收起卡片：保留标题行（第 0 个子 View），其余一律 GONE。 */
private fun hideCardChildren(card: LinearLayout) {
    for (i in 1 until card.childCount) {
        card.getChildAt(i).visibility = View.GONE
    }
}

/** 展开卡片：全部恢复可见，「调整搜索按钮」仍按搜索模式决定。 */
private fun showCardChildren(card: LinearLayout) {
    for (i in 1 until card.childCount) {
        val child = card.getChildAt(i)
        if (child.id == R.id.btn_adjust_search_button) {
            child.visibility =
                if (SpUtils.getSearchMode(card.context)) View.GONE else View.VISIBLE
        } else {
            child.visibility = View.VISIBLE
        }
    }
}

/** 输入框获得焦点或已有内容时，标签上浮缩小并变色。 */
internal fun SettingsPage.setupFloatingLabel(
    container: FrameLayout,
    label: TextView,
    editText: EditText
) {
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
    editText.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { update() }
    })
    update(animate = false)
}

/**
 * 「界面与导航」里只切换显示状态的按钮。
 *
 * 点一下弹确认框，确认后写入设置、刷新按钮文字，再回调让界面立刻生效；
 * 与底栏、控制栏那些开关保持同一套交互，避免误触。参数全是资源 id 与读写闭包，
 * 三个按钮共用一份逻辑。
 */
internal fun SettingsPage.setupToggleButton(
    buttonId: Int,
    titleRes: Int,
    onLabelRes: Int,
    offLabelRes: Int,
    onMessageRes: Int,
    offMessageRes: Int,
    enabledToastRes: Int,
    disabledToastRes: Int,
    isEnabled: () -> Boolean,
    setEnabled: (Boolean) -> Unit,
    onChanged: () -> Unit = {}
) {
    val button = findViewById<Button>(buttonId) ?: return

    fun refreshLabel() {
        button.text = LanguageUtils.getString(
            this,
            if (isEnabled()) onLabelRes else offLabelRes
        )
    }
    refreshLabel()

    button.setOnClickListener {
        val current = isEnabled()
        showMaterialDialog(
            AlertDialog.Builder(this)
                .setTitle(LanguageUtils.getString(this, titleRes))
                .setMessage(getString(if (current) offMessageRes else onMessageRes))
                .setPositiveButton(LanguageUtils.getString(this, R.string.btn_ok)) { _, _ ->
                    setEnabled(!current)
                    refreshLabel()
                    onChanged()
                    ToastUtils.show(
                        this,
                        LanguageUtils.getString(
                            this,
                            if (current) disabledToastRes else enabledToastRes
                        )
                    )
                }
                .setNegativeButton(LanguageUtils.getString(this, R.string.btn_cancel), null)
        )
    }
}
