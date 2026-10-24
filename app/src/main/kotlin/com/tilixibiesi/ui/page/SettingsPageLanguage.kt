package com.tilixibiesi.ui.page

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SettingsStore
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.ToastUtils

import android.app.AlertDialog

internal fun SettingsPage.showLanguageDialog() {
    val page = this
    val languages = LanguageUtils.getSupportedLanguages(page)
    val currentLang = LanguageUtils.getLanguage(page)
    val names = languages.map { it.second }.toTypedArray()
    var checkedIndex = languages.indexOfFirst { it.first == currentLang }
    if (checkedIndex < 0) checkedIndex = 0

    page.showMaterialDialog(
        AlertDialog.Builder(page)
            .setTitle(LanguageUtils.getString(page, R.string.setting_language))
            .setSingleChoiceItems(names, checkedIndex) { dialog, which ->
                val selectedCode = languages[which].first
                dialog.dismiss()
                if (selectedCode == LanguageUtils.FOLLOW_SYSTEM || selectedCode == LanguageUtils.BUILTIN_LANG) {
                    SettingsStore.saveLanguage(selectedCode)
                    LanguageUtils.setAppLanguage(page.activity, selectedCode)
                    return@setSingleChoiceItems
                }
                if (!LanguageUtils.isLanguageInstalled(page, selectedCode)) {
                    page.showMaterialDialog(
                        AlertDialog.Builder(page)
                            .setTitle(LanguageUtils.getString(page, R.string.language_not_installed_title))
                            .setMessage(LanguageUtils.getString(page, R.string.language_not_installed_msg, languages[which].second))
                            .setPositiveButton(LanguageUtils.getString(page, R.string.btn_ok)) { _, _ ->
                                page.downloadAndApplyLanguage(selectedCode)
                            }
                            .setNegativeButton(LanguageUtils.getString(page, R.string.btn_cancel), null)
                    )
                } else if (LanguageUtils.needsUpdate(page, selectedCode)) {
                    page.showMaterialDialog(
                        AlertDialog.Builder(page)
                            .setTitle(LanguageUtils.getString(page, R.string.language_update_title))
                            .setMessage(LanguageUtils.getString(page, R.string.language_update_msg, languages[which].second))
                            .setPositiveButton(LanguageUtils.getString(page, R.string.btn_ok)) { _, _ ->
                                page.downloadAndApplyLanguage(selectedCode)
                            }
                            .setNegativeButton(LanguageUtils.getString(page, R.string.btn_cancel), null)
                    )
                } else {
                    SettingsStore.saveLanguage(selectedCode)
                    LanguageUtils.setAppLanguage(page.activity, selectedCode)
                }
            }
            .setNegativeButton(LanguageUtils.getString(page, R.string.btn_cancel), null)
    )
}

internal fun SettingsPage.downloadAndApplyLanguage(code: String) {
    val page = this
    ToastUtils.show(page, LanguageUtils.getString(page, R.string.language_downloading))
    AppExecutors.io.execute {
        val ok = LanguageUtils.downloadLanguage(page, code)
        page.runOnUiThread {
            if (page.activity.isDestroyed) return@runOnUiThread
            if (ok) {
                SettingsStore.saveLanguage(code)
                LanguageUtils.setAppLanguage(page.activity, code)
            } else {
                ToastUtils.show(page, LanguageUtils.getString(page, R.string.language_download_failed))
            }
        }
    }
}
