package com.tilixibiesi.data

import com.tilixibiesi.R
import com.tilixibiesi.util.AtomicFileWriter
import com.tilixibiesi.util.ContextUtils
import com.tilixibiesi.util.ToastUtils

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 设置的持久化：`system/Settings.json` 的读写，以及「默认背景」文件的落盘。
 *
 * 与 [SpUtils] 的分工：
 *  - [SpUtils] 面向运行时读写（每次改动即时生效）；
 *  - 本类面向「导出/导入一份完整设置」——保存把当前 Sp 快照写成 JSON，
 *    加载把 JSON 逐项写回 Sp；两者字段必须一一对应，改动时记得同步。
 *
 * 原先这五个方法与 [com.tilixibiesi.ui.page.SettingsPage] 的 UI 状态无关，
 * 只是借用页面当 Context，因此独立出来，便于单独阅读与复用。
 */
object SettingsStore {

    /** 设置快照文件（基于当前存储根） */
    private const val SETTINGS_JSON_PATH_REL = "system/Settings.json"

    /** 默认背景目录：卸载重装后仍存在，供 [com.tilixibiesi.util.BackgroundHelper] 优先读取 */
    private const val DEFAULT_BG_DIR_REL = "system/axeron/long/Android/default_bg/"

    /** 设置快照文件（可能尚不存在） */
    fun settingsFile(): File = StoragePaths.resolveRead(SETTINGS_JSON_PATH_REL)

    /** 删除设置快照（「恢复默认设置」用） */
    fun deleteSettingsFile() {
        runCatching { settingsFile().takeIf { it.exists() }?.delete() }
    }

    /**
     * 只更新快照里的语言字段。
     *
     * 语言切换是高频动作，若整份重写会把当前 UI 上尚未保存的其它改动一并带入，
     * 因此这里做「就地更新单个字段」。
     */
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

    /** 把当前 Sp 里的设置整份导出为 JSON 快照。 */
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

    /**
     * 从 JSON 快照逐项写回 Sp。
     *
     * @return 快照存在且解析成功为 true；不存在或损坏为 false（调用方据此决定是否提示）。
     */
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
                    // setAppLanguage 需要真实 Activity 来 recreate；页面只是 ContextWrapper
                    ContextUtils.unwrapActivity(context)?.let { LanguageUtils.setAppLanguage(it, savedLang) }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 把用户选中的图片复制为「默认背景」，并清掉旧的所有默认背景文件。
     *
     * 落盘文件名固定为 `default_background`（不带扩展名），
     * 由 [com.tilixibiesi.util.BackgroundHelper] 按前缀匹配读取，
     * 这样更换图片时无需关心格式变化。
     *
     * @return 新文件的绝对路径；失败返回 null
     */
    fun copyDefaultBackground(context: Context, srcUri: Uri): String? {
        return try {
            val dir = StoragePaths.resolveWrite(DEFAULT_BG_DIR_REL)
            if (!dir.exists() && !dir.mkdirs()) {
                ToastUtils.show(context, LanguageUtils.getString(context, R.string.toast_cannot_create_dir))
                return null
            }
            val inputStream = context.contentResolver.openInputStream(srcUri) ?: return null

            // 删除旧的所有默认背景文件（无论什么后缀）
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("default_background.") || file.name == "default_background") {
                    file.delete()
                }
            }

            // 保存新文件，去掉后缀
            val destFile = File(dir, "default_background")
            FileOutputStream(destFile).use { output ->
                inputStream.copyTo(output)
            }
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
