package com.tilixibiesi.data
import com.tilixibiesi.R
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.AtomicFileWriter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
object LanguageUtils {

    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANGUAGE = "app_language"
    const val FOLLOW_SYSTEM = ""

    const val ACTION_LANGUAGE_UPDATED = "com.tilixibiesi.LANGUAGE_UPDATED"

    private const val LANG_BASE_URL = "https://language.tibao.dpdns.org/"

    const val LANG_DIR_REL = "system/axeron/long/Android/language/"

    @Volatile
    var currentLanguageMap: Map<String, String>? = null
        private set

    const val BUILTIN_LANG = "zh-CN"

    private val supportedLanguages = listOf(
        FOLLOW_SYSTEM to "跟随系统",
        BUILTIN_LANG to "简体中文",
        "zh-TW" to "繁體中文",
        "en" to "English",
        "ja" to "日本語",
        "ru" to "Русский",
        "fr" to "Français",
        "es" to "Español",
        "hi" to "हिन्दी",
        "pt" to "Português",
        "ko" to "한국어",
        "de" to "Deutsch",
        "it" to "Italiano",
        "vi" to "Tiếng Việt",
        "th" to "ไทย",
        "ar" to "العربية",
        "am" to "አማርኛ",
        "az" to "Azərbaycan dili",
        "be" to "беларуская мова",
        "bg" to "български език",
        "bn" to "বাংলা",
        "ca" to "Català",
        "cs" to "čeština",
        "da" to "Dansk",
        "et" to "eesti keel",
        "fa" to "فارسی",
        "fi" to "Suomi",
        "hr" to "Hrvatski",
        "hu" to "Magyar",
        "hy" to "Հայերեն",
        "ka" to "ქართული",
        "kk" to "қазақ тілі",
        "km" to "ខេមរភាសា",
        "kn" to "ಕನ್ನಡ",
        "mk" to "македонски јазик",
        "mr" to "मराठी",
        "ms" to "Bahasa Melayu",
        "my" to "ဗမာစာ",
        "nb" to "Norsk bokmål",
        "nl" to "Nederlands",
        "pa" to "ਪੰਜਾਬੀ",
        "pl" to "Język Polski",
        "ro" to "Română",
        "sk" to "slovenčina",
        "sl" to "slovenščina",
        "sr" to "српски језик",
        "sv" to "svenska",
        "sw" to "Kiswahili",
        "ta" to "தமிழ்",
        "te" to "తెలుగు",
        "tr" to "Türkçe",
        "uk" to "українська мова",
        "ur" to "اردو"
    )

    fun getSupportedLanguages(context: Context): List<Pair<String, String>> {
        return supportedLanguages
    }

    fun currentVersionCode(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    } catch (e: Exception) {
        0
    }

    fun getLanguageUrl(code: String, versionCode: Int): String =
        "${LANG_BASE_URL}${versionCode}/${code}.json"

    fun getLanguageFile(context: Context, code: String): File =
        File(StoragePaths.root(), "$LANG_DIR_REL$code.json")

    fun isLanguageInstalled(context: Context, code: String): Boolean {
        if (code == BUILTIN_LANG || code == FOLLOW_SYSTEM) return true
        return getLanguageFile(context, code).exists()
    }

    fun needsUpdate(context: Context, code: String): Boolean {
        if (code == BUILTIN_LANG || code == FOLLOW_SYSTEM) return false
        val file = getLanguageFile(context, code)
        if (!file.exists()) return true
        return try {
            JSONObject(file.readText()).optInt("versionCode", -1) != currentVersionCode(context)
        } catch (e: Exception) {
            true
        }
    }

    fun lookupOverlay(res: Resources, resId: Int): String? {
        val map = currentLanguageMap ?: return null
        val name = try { res.getResourceEntryName(resId) } catch (e: Exception) { return null }
        return map[name]
    }

    fun getString(context: Context, resId: Int, vararg formatArgs: Any?): String {
        val raw = lookupOverlay(context.resources, resId) ?: context.getString(resId)
        if (formatArgs.isEmpty()) return raw
        return try {
            String.format(Locale.getDefault(), raw, *formatArgs)
        } catch (e: Exception) {
            raw
        }
    }

    fun downloadLanguage(context: Context, code: String): Boolean {
        return try {
            val url = URL(getLanguageUrl(code, currentVersionCode(context)))
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode !in 200..299) return false
                val jsonStr = conn.inputStream.bufferedReader().readText()
                val obj = JSONObject(jsonStr)
                if (!obj.has("strings")) return false
                val file = getLanguageFile(context, code)
                file.parentFile?.mkdirs()
                AtomicFileWriter.writeText(file, jsonStr)
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun refreshLanguageMap(context: Context) {
        val lang = getEffectiveLanguage(context)
        if (lang == BUILTIN_LANG) {
            currentLanguageMap = null
            return
        }
        val file = getLanguageFile(context, lang)
        currentLanguageMap = try {
            val obj = JSONObject(file.readText())
            val strings = obj.optJSONObject("strings")
            if (strings == null) null
            else {
                val m = HashMap<String, String>()
                val it = strings.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    m[k] = strings.optString(k)
                }
                m
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getEffectiveLanguage(context: Context): String {
        val pref = getLanguage(context)
        if (pref != FOLLOW_SYSTEM) return pref
        val sys = resolveSystemLanguageCode() ?: return BUILTIN_LANG
        if (sys == BUILTIN_LANG) return BUILTIN_LANG
        return if (isLanguageInstalled(context, sys)) sys else BUILTIN_LANG
    }

    fun checkCurrentLanguageOnStartup(context: Context) {
        AppExecutors.io.execute {
            try {
                val code = resolveCurrentLanguageCode(context) ?: return@execute
                if (code == BUILTIN_LANG) return@execute
                if (!needsUpdate(context, code)) return@execute
                if (downloadLanguage(context, code)) {
                    refreshLanguageMap(context)
                    context.sendBroadcast(Intent(ACTION_LANGUAGE_UPDATED))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun resolveCurrentLanguageCode(context: Context): String? {
        val pref = getLanguage(context)
        if (pref != FOLLOW_SYSTEM) return pref
        return resolveSystemLanguageCode()
    }

    private fun resolveSystemLanguageCode(): String? {
        val locale = systemLocale() ?: return null
        return when (locale.language.lowercase(Locale.ROOT)) {
            "zh" -> if (isTraditionalChinese(locale)) "zh-TW" else BUILTIN_LANG
            "en" -> "en"
            "ja" -> "ja"
            "ru" -> "ru"
            "fr" -> "fr"
            "es" -> "es"
            "hi" -> "hi"
            "pt" -> "pt"
            "ko" -> "ko"
            "de" -> "de"
            "it" -> "it"
            "vi" -> "vi"
            "th" -> "th"
            "ar" -> "ar"
            "am" -> "am"
            "az" -> "az"
            "be" -> "be"
            "bg" -> "bg"
            "bn" -> "bn"
            "ca" -> "ca"
            "cs" -> "cs"
            "da" -> "da"
            "et" -> "et"
            "fa" -> "fa"
            "fi" -> "fi"
            "hr" -> "hr"
            "hu" -> "hu"
            "hy" -> "hy"
            "ka" -> "ka"
            "kk" -> "kk"
            "km" -> "km"
            "kn" -> "kn"
            "mk" -> "mk"
            "mr" -> "mr"
            "ms" -> "ms"
            "my" -> "my"
            "nb" -> "nb"
            "nl" -> "nl"
            "pa" -> "pa"
            "pl" -> "pl"
            "ro" -> "ro"
            "sk" -> "sk"
            "sl" -> "sl"
            "sr" -> "sr"
            "sv" -> "sv"
            "sw" -> "sw"
            "ta" -> "ta"
            "te" -> "te"
            "tr" -> "tr"
            "uk" -> "uk"
            "ur" -> "ur"
            else -> null
        }
    }

    private fun systemLocale(): Locale? {
        return try {
            Resources.getSystem().configuration.locales[0]
        } catch (e: Exception) {
            null
        }
    }

    private fun isTraditionalChinese(locale: Locale): Boolean {
        val script = locale.script
        if (!script.isNullOrEmpty()) return script.equals("Hant", ignoreCase = true)
        val country = locale.country.uppercase(Locale.ROOT)
        return country == "TW" || country == "HK" || country == "MO"
    }

    fun saveLanguage(context: Context, languageCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, languageCode)
            .apply()
        refreshLanguageMap(context)
    }

    fun getLanguage(context: Context): String {
        val lang = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, FOLLOW_SYSTEM) ?: FOLLOW_SYSTEM
        return lang
    }

    fun wrapContext(context: Context): Context {
        refreshLanguageMap(context)
        val effective = getEffectiveLanguage(context)
        if (effective == BUILTIN_LANG) {
            return context
        }
        val locale = Locale.forLanguageTag(effective)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        val wrapped = context.createConfigurationContext(config)
        return LocalizedContext(wrapped)
    }

    private class LocalizedContext(base: Context) : ContextWrapper(base) {
        private var localized: Resources? = null
        override fun getResources(): Resources {
            var res = localized
            if (res == null) {
                res = LocalizedResources(super.getResources())
                localized = res
            }
            return res
        }
    }
fun setAppLanguage(activity: Activity, languageCode: String) {
    saveLanguage(activity, languageCode)
    activity.recreate()
}
}
