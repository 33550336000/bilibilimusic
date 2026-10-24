package com.tilixibiesi.ui.page

import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SettingsStore
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.ToastUtils

import android.app.AlertDialog

/*
 * 设置页里的语言切换流程：选择语言、按需下载语言包、切换后重建界面。
 *
 * 拆出来的原因与通知那条链相同——它只依赖页面提供的对话框工厂，
 * 与其它设置项没有共享状态。
 */

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

/** 下载语言包并切换；失败时只提示，不改变当前语言。 */
internal fun SettingsPage.downloadAndApplyLanguage(code: String) {
    val page = this
    ToastUtils.show(page, LanguageUtils.getString(page, R.string.language_downloading))
    AppExecutors.io.execute {
        val ok = LanguageUtils.downloadLanguage(page, code)
        page.runOnUiThread {
            // 下载期间页面可能已经销毁（例如用户返回）
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
