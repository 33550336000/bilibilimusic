package com.tilixibiesi.data

import com.tilixibiesi.R
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.AtomicFileWriter
import com.tilixibiesi.util.ContextUtils
import com.tilixibiesi.util.ToastUtils

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

    private fun putStringSet(json: JSONObject, key: String, values: Set<String>) {
        val arr = org.json.JSONArray()
        values.forEach { arr.put(it) }
        json.put(key, arr)
    }

    private fun getStringSet(json: JSONObject, key: String): Set<String>? {
        return runCatching {
            val arr = json.getJSONArray(key)
            buildSet {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotEmpty()) add(v)
                }
            }
        }.getOrNull()
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
            json.put("bili_audio_quality", SpUtils.getBiliAudioQuality(context))
            json.put("show_today_duration", SpUtils.isShowTodayDurationEnabled(context))
            json.put("video_notify_progress", SpUtils.isVideoNotifyProgressEnabled(context))
            json.put("language", LanguageUtils.getLanguage(context))

            json.put("bottom_nav", SpUtils.getBottomNavEnabled(context))
            json.put("liquid_glass", SpUtils.getLiquidGlassEnabled(context))
            json.put("liquid_glass_cards", SpUtils.getLiquidGlassCardsEnabled(context))
            json.put("click_fx", SpUtils.getClickFxEnabled(context))
            json.put("main_add_button", SpUtils.isMainAddButtonVisible(context))
            json.put("main_delete_button", SpUtils.isMainDeleteButtonVisible(context))
            json.put("full_bili_source", SpUtils.isFullBiliSource(context))
            json.put("danmaku", SpUtils.isDanmakuEnabled(context))
            json.put("play_mode", SpUtils.getPlayMode(context))
            json.put("search_btn_x", SpUtils.getSearchButtonX(context).toDouble())
            json.put("search_btn_y", SpUtils.getSearchButtonY(context).toDouble())
            putStringSet(json, "collapsed_sections", SpUtils.getCollapsedSections(context))

            val file = settingsFile()
            file.parentFile?.mkdirs()
            AtomicFileWriter.writeText(file, json.toString())
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_settings_saved))
        } catch (e: Exception) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_save_failed, e.message))
        }
    }

    fun loadFromFile(context: Context): Boolean {
        val json = readSettingsJson() ?: return false
        return applyJson(context, json)
    }

    fun loadFromFileAsync(context: Context, onDone: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        AppExecutors.io.execute {
            val json = readSettingsJson()
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val ok = if (json == null) false else applyJson(appContext, json)
                onDone?.invoke(ok)
            }
        }
    }

    private fun readSettingsJson(): JSONObject? {
        val file = settingsFile()
        if (!file.exists()) return null
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun applyJson(context: Context, json: JSONObject): Boolean {
        return try {
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
            if (json.has("bili_audio_quality")) SpUtils.saveBiliAudioQuality(context, json.getInt("bili_audio_quality"))
            if (json.has("show_today_duration")) SpUtils.setShowTodayDurationEnabled(context, json.getBoolean("show_today_duration"))
            if (json.has("video_notify_progress")) SpUtils.setVideoNotifyProgressEnabled(context, json.getBoolean("video_notify_progress"))

            if (json.has("bottom_nav")) SpUtils.setBottomNavEnabled(context, json.getBoolean("bottom_nav"))
            if (json.has("liquid_glass")) SpUtils.setLiquidGlassEnabled(context, json.getBoolean("liquid_glass"))
            if (json.has("liquid_glass_cards")) SpUtils.setLiquidGlassCardsEnabled(context, json.getBoolean("liquid_glass_cards"))
            if (json.has("click_fx")) SpUtils.setClickFxEnabled(context, json.getBoolean("click_fx"))
            if (json.has("main_add_button")) SpUtils.setMainAddButtonVisible(context, json.getBoolean("main_add_button"))
            if (json.has("main_delete_button")) SpUtils.setMainDeleteButtonVisible(context, json.getBoolean("main_delete_button"))
            if (json.has("full_bili_source")) SpUtils.setFullBiliSource(context, json.getBoolean("full_bili_source"))
            if (json.has("danmaku")) SpUtils.setDanmakuEnabled(context, json.getBoolean("danmaku"))
            if (json.has("play_mode")) SpUtils.savePlayMode(context, json.getInt("play_mode"))
            if (json.has("search_btn_x") && json.has("search_btn_y")) {
                SpUtils.saveSearchButtonPosition(
                    context,
                    json.getDouble("search_btn_x").toFloat(),
                    json.getDouble("search_btn_y").toFloat()
                )
            }
            getStringSet(json, "collapsed_sections")?.let {
                SpUtils.setCollapsedSections(context, it)
            }

            if (json.has("language")) {
                val savedLang = json.getString("language")
                if (savedLang != LanguageUtils.getLanguage(context)) {
                    val activity = ContextUtils.unwrapActivity(context)
                    if (activity != null) {
                        LanguageUtils.setAppLanguage(activity, savedLang)
                    } else {
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
