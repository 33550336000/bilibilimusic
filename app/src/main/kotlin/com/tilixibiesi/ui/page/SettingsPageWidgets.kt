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

private fun hideCardChildren(card: LinearLayout) {
    for (i in 1 until card.childCount) {
        card.getChildAt(i).visibility = View.GONE
    }
}

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
