package com.tilixibiesi.data

import com.tilixibiesi.R
import com.tilixibiesi.util.AtomicFileWriter
import com.tilixibiesi.util.ContextUtils
import com.tilixibiesi.util.ToastUtils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object SettingsStore {

    private const val SETTINGS_JSON_PATH_REL = "system/Settings.json"

    private const val DEFAULT_BG_DIR_REL = "system/axeron/long/Android/default_bg/"

    fun settingsFile(): File = StoragePaths.resolveRead(SETTINGS_JSON_PATH_REL)

    fun deleteSettingsFile() {
        runCatching { settingsFile().takeIf { it.exists() }?.delete() }
    }

    fun saveLanguage(langCode: String) {
        try {
            val file = settingsFile()
            if (file.exists()) {
                val json = JSONObject(file.readText())
                json.put("language", langCode)
                AtomicFileWriter.writeText(file, json.toString())
            }
        } catch (_: Exception) {
        }
    }

    fun saveToFile(context: Context) {
        try {
            val json = JSONObject()
            json.put("font_color", SpUtils.getFontColor(context))
            json.put("font_size", SpUtils.getFontSize(context))
            json.put("background_alpha", SpUtils.getBackgroundAlpha(context))
            json.put("default_background_path", SpUtils.getDefaultBackgroundPath(context))
            json.put("volume_key_switch", SpUtils.getVolumeKeySwitch(context))
            json.put("search_mode", SpUtils.getSearchMode(context))
            json.put("auto_cache", SpUtils.isAutoCacheEnabled(context))
            json.put("search_btn_transparent_style", SpUtils.isSearchBtnTransparentStyle(context))
            json.put("dialog_bg_color", SpUtils.getDialogBgColor(context))
            json.put("dialog_font_color", SpUtils.getDialogFontColor(context))
            json.put("dialog_alpha", SpUtils.getDialogAlpha(context))
            json.put("audio_focus_mode", SpUtils.getAudioFocusMode(context))
            json.put("show_today_duration", SpUtils.isShowTodayDurationEnabled(context))
            json.put("video_notify_progress", SpUtils.isVideoNotifyProgressEnabled(context))
            json.put("language", LanguageUtils.getLanguage(context))

            val file = settingsFile()
            file.parentFile?.mkdirs()
            AtomicFileWriter.writeText(file, json.toString())
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_settings_saved))
        } catch (e: Exception) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_save_failed, e.message))
        }
    }

    fun loadFromFile(context: Context): Boolean {
        val file = settingsFile()
        if (!file.exists()) return false
        return try {
            val json = JSONObject(file.readText())
            if (json.has("font_color")) SpUtils.saveFontColor(context, json.getString("font_color"))
            if (json.has("font_size")) SpUtils.saveFontSize(context, json.getInt("font_size"))
            if (json.has("background_alpha")) SpUtils.saveBackgroundAlpha(context, json.getInt("background_alpha"))
            if (json.has("default_background_path")) SpUtils.saveDefaultBackgroundPath(context, json.getString("default_background_path"))
            if (json.has("volume_key_switch")) SpUtils.saveVolumeKeySwitch(context, json.getBoolean("volume_key_switch"))
            if (json.has("search_mode")) SpUtils.saveSearchMode(context, json.getBoolean("search_mode"))
            if (json.has("auto_cache")) SpUtils.setAutoCacheEnabled(context, json.getBoolean("auto_cache"))
            if (json.has("search_btn_transparent_style")) SpUtils.setSearchBtnTransparentStyle(context, json.getBoolean("search_btn_transparent_style"))
            if (json.has("dialog_bg_color")) SpUtils.saveDialogBgColor(context, json.getString("dialog_bg_color"))
            if (json.has("dialog_font_color")) SpUtils.saveDialogFontColor(context, json.getString("dialog_font_color"))
            if (json.has("dialog_alpha")) SpUtils.saveDialogAlpha(context, json.getInt("dialog_alpha"))
            if (json.has("audio_focus_mode")) SpUtils.saveAudioFocusMode(context, json.getInt("audio_focus_mode"))
            if (json.has("show_today_duration")) SpUtils.setShowTodayDurationEnabled(context, json.getBoolean("show_today_duration"))
            if (json.has("video_notify_progress")) SpUtils.setVideoNotifyProgressEnabled(context, json.getBoolean("video_notify_progress"))
            if (json.has("language")) {
                val savedLang = json.getString("language")
                if (savedLang != LanguageUtils.getLanguage(context)) {
                    val activity = ContextUtils.unwrapActivity(context)
                    if (activity != null) {
                        LanguageUtils.setAppLanguage(activity, savedLang)
                    } else {
                        // 启动时还没有 Activity，先只保存语言：
                        // 首个 Activity 的 attachBaseContext 会据此套用，无需重建
                        LanguageUtils.saveLanguage(context, savedLang)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun copyDefaultBackground(context: Context, srcUri: Uri): String? {
        return try {
            val dir = StoragePaths.resolveWrite(DEFAULT_BG_DIR_REL)
            if (!dir.exists() && !dir.mkdirs()) {
                ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_cannot_create_dir))
                return null
            }
            val inputStream = context.contentResolver.openInputStream(srcUri) ?: return null

            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("default_background.") || file.name == "default_background") {
                    file.delete()
                }
            }

            val destFile = File(dir, "default_background." + resolveExtension(context, srcUri))
            FileOutputStream(destFile).use { output ->
                inputStream.copyTo(output)
            }
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun resolveExtension(context: Context, srcUri: Uri): String {
        try {
            context.contentResolver.query(srcUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        val ext = name?.substringAfterLast('.', "")?.lowercase()
                        if (!ext.isNullOrEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) {
                            return ext
                        }
                    }
                }
        } catch (_: Exception) {
        }

        try {
            val mime = context.contentResolver.getType(srcUri)
            if (!mime.isNullOrEmpty()) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                if (!ext.isNullOrEmpty()) return ext.lowercase()
            }
        } catch (_: Exception) {
        }

        return "img"
    }
}
