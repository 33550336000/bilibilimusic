package com.tilixibiesi.data
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.util.AppExecutors

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
object SpUtils {
    private const val PREFS_NAME = "MusicPlayerPrefs"
    private const val KEY_MUSIC_LIST = "music_list"
    private const val KEY_CURRENT_MUSIC_NAME = "current_music_name"
    private const val KEY_IS_PLAYING = "is_playing"
    private const val KEY_PLAY_MODE = "play_mode"
    private const val KEY_FONT_COLOR = "font_color"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_BACKGROUND_ALPHA = "background_alpha"
    private const val KEY_AUDIO_FOCUS_MODE = "audio_focus_mode"
    private const val KEY_DEFAULT_BACKGROUND = "default_background_path"
    private const val KEY_SEARCH_BTN_STYLE = "search_btn_style"
    private const val KEY_AUTO_CACHE = "auto_cache"
    private const val KEY_FULL_BILI_SOURCE = "full_bili_source"
    private const val KEY_SHOW_TODAY_DURATION = "show_today_duration"
    private const val KEY_BILI_COOKIE = "bili_cookie"
    private const val KEY_DIALOG_BG_COLOR = "dialog_bg_color"
    private const val KEY_DIALOG_FONT_COLOR = "dialog_font_color"
    private const val KEY_DIALOG_ALPHA = "dialog_alpha"
    private const val KEY_AUTO_LOAD_DEFAULT = "auto_load_default"
    private const val KEY_BOTTOM_NAV_ENABLED = "bottom_nav_enabled"

    private const val KEY_CLICK_FX_ENABLED = "click_fx_enabled"
    private const val KEY_COLLAPSED_SECTIONS = "collapsed_sections"
    private const val KEY_STORAGE_ROOT = "storage_root"
    private const val KEY_DANMAKU_ENABLED = "danmaku_enabled"
    private const val KEY_VIDEO_NOTIFY_PROGRESS = "video_notify_progress"
    const val AUDIO_FOCUS_CALL_LEVEL = 0
    const val AUDIO_FOCUS_FULL_EXCLUSIVE = 1
    const val AUDIO_FOCUS_TRANSIENT = 2

    private fun getSp(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveMusicList(context: Context, list: List<MusicBean>) {
        if (list.isEmpty()) return
        try {
            val jsonArray = JSONArray()
            for (bean in list) {
                val json = JSONObject()
                json.put("musicName", bean.musicName)
                json.put("musicUrl", bean.musicUrl)
                json.put("localPath", bean.localPath ?: "")
                json.put("isDownloaded", bean.isDownloaded)
                json.put("isPlaying", bean.isPlaying)
                jsonArray.put(json)
            }
            getSp(context).edit().putString(KEY_MUSIC_LIST, jsonArray.toString()).apply()
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getMusicList(context: Context): List<MusicBean> {
        val list = mutableListOf<MusicBean>()
        try {
            val jsonStr = getSp(context).getString(KEY_MUSIC_LIST, "") ?: ""
            if (jsonStr.isEmpty()) return list
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val bean = MusicBean(json.getString("musicName"), json.getString("musicUrl"))
                bean.localPath = json.getString("localPath").ifEmpty { null }
                bean.isDownloaded = json.getBoolean("isDownloaded")
                bean.isPlaying = json.getBoolean("isPlaying")
                list.add(bean)
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    fun savePlayState(context: Context, musicName: String?, isPlaying: Boolean) {
        getSp(context).edit()
            .putString(KEY_CURRENT_MUSIC_NAME, musicName)
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .apply()
    }

    fun getCurrentMusicName(context: Context): String? {
        return getSp(context).getString(KEY_CURRENT_MUSIC_NAME, null)
    }


    fun savePlayMode(context: Context, mode: Int) {
        getSp(context).edit().putInt(KEY_PLAY_MODE, mode).apply()
    }
    fun getPlayMode(context: Context): Int =
        getSp(context).getInt(KEY_PLAY_MODE, PlayMode.SINGLE_LOOP.ordinal)

    fun saveFontColor(context: Context, color: String) {
        getSp(context).edit().putString(KEY_FONT_COLOR, color).apply()
    }
    fun getFontColor(context: Context): String =
        getSp(context).getString(KEY_FONT_COLOR, "#1A1B21") ?: "#FFFFFF"

    fun saveFontSize(context: Context, size: Int) {
        getSp(context).edit().putInt(KEY_FONT_SIZE, size).apply()
    }
    fun getFontSize(context: Context): Int =
        getSp(context).getInt(KEY_FONT_SIZE, 15)

    fun saveBackgroundAlpha(context: Context, alpha: Int) {
        getSp(context).edit().putInt(KEY_BACKGROUND_ALPHA, alpha).apply()
    }
    fun getBackgroundAlpha(context: Context): Int =
        getSp(context).getInt(KEY_BACKGROUND_ALPHA, 100)

    fun getBottomNavEnabled(context: Context): Boolean =
        getSp(context).getBoolean(KEY_BOTTOM_NAV_ENABLED, false)
    fun setBottomNavEnabled(context: Context, enabled: Boolean) {
        getSp(context).edit().putBoolean(KEY_BOTTOM_NAV_ENABLED, enabled).apply()
    }

    fun getClickFxEnabled(context: Context): Boolean =
        getSp(context).getBoolean(KEY_CLICK_FX_ENABLED, false)
    fun setClickFxEnabled(context: Context, enabled: Boolean) {
        getSp(context).edit().putBoolean(KEY_CLICK_FX_ENABLED, enabled).apply()
    }

    fun getCollapsedSections(context: Context): Set<String> =
        getSp(context).getStringSet(KEY_COLLAPSED_SECTIONS, emptySet()) ?: emptySet()
    fun setCollapsedSections(context: Context, sections: Set<String>) {
        getSp(context).edit().putStringSet(KEY_COLLAPSED_SECTIONS, sections).apply()
    }

    fun saveStorageRoot(context: Context, root: String) {
        getSp(context).edit().putString(KEY_STORAGE_ROOT, root).apply()
    }
    fun getStorageRoot(context: Context): String? =
        getSp(context).getString(KEY_STORAGE_ROOT, null)

    fun clearAll(context: Context) {
        val defaultBg = getDefaultBackgroundPath(context)
        getSp(context).edit().clear().apply()
        if (defaultBg.isNotEmpty()) saveDefaultBackgroundPath(context, defaultBg)
    }

    fun getSearchMode(context: Context): Boolean =
        getSp(context).getBoolean("search_mode", true)
    fun saveSearchMode(context: Context, enableSearchPage: Boolean) {
        getSp(context).edit().putBoolean("search_mode", enableSearchPage).apply()
    }

    fun getVolumeKeySwitch(context: Context): Boolean =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("volume_key_switch", false)
    fun saveVolumeKeySwitch(context: Context, enabled: Boolean) {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("volume_key_switch", enabled).apply()
    }

    fun saveSearchButtonPosition(context: Context, x: Float, y: Float) {
        getSp(context).edit().putFloat("search_btn_x", x).putFloat("search_btn_y", y).apply()
    }
    fun getSearchButtonX(context: Context): Float =
        getSp(context).getFloat("search_btn_x", -1f)
    fun getSearchButtonY(context: Context): Float =
        getSp(context).getFloat("search_btn_y", -1f)

    fun saveDefaultBackgroundPath(context: Context, path: String) {
        getSp(context).edit().putString(KEY_DEFAULT_BACKGROUND, path).apply()
    }
    fun getDefaultBackgroundPath(context: Context): String =
        getSp(context).getString(KEY_DEFAULT_BACKGROUND, "") ?: ""

    fun getAudioFocusMode(context: Context): Int =
        getSp(context).getInt(KEY_AUDIO_FOCUS_MODE, AUDIO_FOCUS_CALL_LEVEL)
    fun saveAudioFocusMode(context: Context, mode: Int) {
        getSp(context).edit().putInt(KEY_AUDIO_FOCUS_MODE, mode).apply()
    }

    fun isSearchBtnTransparentStyle(context: Context): Boolean =
        getSp(context).getBoolean(KEY_SEARCH_BTN_STYLE, false)
    fun setSearchBtnTransparentStyle(context: Context, transparent: Boolean) {
        getSp(context).edit().putBoolean(KEY_SEARCH_BTN_STYLE, transparent).apply()
    }

    fun isAutoCacheEnabled(context: Context): Boolean =
        getSp(context).getBoolean(KEY_AUTO_CACHE, true)
    fun setAutoCacheEnabled(context: Context, enabled: Boolean) {
        getSp(context).edit().putBoolean(KEY_AUTO_CACHE, enabled).apply()
    }

    fun isFullBiliSource(context: Context): Boolean =
        getSp(context).getBoolean(KEY_FULL_BILI_SOURCE, false)
    fun setFullBiliSource(context: Context, enabled: Boolean) {
        getSp(context).edit().putBoolean(KEY_FULL_BILI_SOURCE, enabled).apply()
    }

    fun getBiliCookie(context: Context): String =
        getSp(context).getString(KEY_BILI_COOKIE, "") ?: ""
    fun saveBiliCookie(context: Context, cookie: String) {
        getSp(context).edit().putString(KEY_BILI_COOKIE, cookie).apply()
    }

    enum class PlayMode { SEQUENCE, SINGLE_LOOP, RANDOM }

    fun saveDialogBgColor(context: Context, color: String) {
        getSp(context).edit().putString(KEY_DIALOG_BG_COLOR, color).apply()
    }
    fun getDialogBgColor(context: Context): String =
        getSp(context).getString(KEY_DIALOG_BG_COLOR, "#FFFFFF") ?: "#FFFFFF"

    fun isShowTodayDurationEnabled(context: Context): Boolean =
        getSp(context).getBoolean(KEY_SHOW_TODAY_DURATION, false)
    fun setShowTodayDurationEnabled(context: Context, enabled: Boolean) {
        getSp(context).edit().putBoolean(KEY_SHOW_TODAY_DURATION, enabled).apply()
    }

    fun isDanmakuEnabled(context: Context): Boolean =
        getSp(context).getBoolean(KEY_DANMAKU_ENABLED, true)

    fun setDanmakuEnabled(context: Context, enabled: Boolean) {
        getSp(context).edit().putBoolean(KEY_DANMAKU_ENABLED, enabled).apply()
    }

    fun isVideoNotifyProgressEnabled(context: Context): Boolean =
        getSp(context).getBoolean(KEY_VIDEO_NOTIFY_PROGRESS, true)

    fun setVideoNotifyProgressEnabled(context: Context, enabled: Boolean) {
        getSp(context).edit().putBoolean(KEY_VIDEO_NOTIFY_PROGRESS, enabled).apply()
    }

    fun isAutoLoadDefaultEnabled(context: Context): Boolean =
        getSp(context).getBoolean(KEY_AUTO_LOAD_DEFAULT, true)

    fun setAutoLoadDefaultEnabled(context: Context, enabled: Boolean) {
        getSp(context).edit().putBoolean(KEY_AUTO_LOAD_DEFAULT, enabled).apply()
    }

    fun saveDialogFontColor(context: Context, color: String) {
        getSp(context).edit().putString(KEY_DIALOG_FONT_COLOR, color).apply()
    }
    fun getDialogFontColor(context: Context): String =
        getSp(context).getString(KEY_DIALOG_FONT_COLOR, "") ?: ""

    fun saveDialogAlpha(context: Context, alpha: Int) {
        getSp(context).edit().putInt(KEY_DIALOG_ALPHA, alpha).apply()
    }
    fun getDialogAlpha(context: Context): Int =
        getSp(context).getInt(KEY_DIALOG_ALPHA, 100)
    fun clearCacheFilesOnly(context: Context) {
        AppExecutors.io.execute {
            WebViewMetricsCleaner.purgeWebViewArtifacts(context.applicationContext)
        }
  }
}
