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

    /** 广播：启动时检查发现当前语言资源已下载/更新，界面需重建以应用新语言 */
    const val ACTION_LANGUAGE_UPDATED = "com.tilixibiesi.LANGUAGE_UPDATED"

    /** 语言资源托管服务器根域名（子域名） */
    private const val LANG_BASE_URL = "https://language.tibao.dpdns.org/"

    /** 语言 JSON 下载后的相对存储路径（基于 StoragePaths.root()） */
    const val LANG_DIR_REL = "system/axeron/long/Android/language/"

    /** 当前语言已加载的字符串覆盖映射（资源名 -> 翻译文本）；切换/下载后刷新 */
    @Volatile
    var currentLanguageMap: Map<String, String>? = null
        private set

    /** 内置语言（简体中文），始终可用，无需下载 */
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

    /** 当前 App 的 versionCode，用于版本校验 */
    fun currentVersionCode(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    } catch (e: Exception) {
        0
    }

    /** 指定语言码对应的 JSON 下载 URL：language.tibao.dpdns.org/<versionCode>/<code>.json */
    fun getLanguageUrl(code: String, versionCode: Int): String =
        "${LANG_BASE_URL}${versionCode}/${code}.json"

    /** 指定语言码的本地 JSON 文件 */
    fun getLanguageFile(context: Context, code: String): File =
        File(StoragePaths.root(), "$LANG_DIR_REL$code.json")

    /** 该语言是否已下载安装（JSON 文件存在） */
    fun isLanguageInstalled(context: Context, code: String): Boolean {
        if (code == BUILTIN_LANG || code == FOLLOW_SYSTEM) return true
        return getLanguageFile(context, code).exists()
    }

    /** 已安装语言的 JSON 内 versionCode 与当前 App 是否一致（不一致表示有更新） */
    fun needsUpdate(context: Context, code: String): Boolean {
        if (code == BUILTIN_LANG || code == FOLLOW_SYSTEM) return false
        val file = getLanguageFile(context, code)
        if (!file.exists()) return true // 未安装视为需要下载
        return try {
            JSONObject(file.readText()).optInt("versionCode", -1) != currentVersionCode(context)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 根据资源 id 在当前语言覆盖映射中查找翻译文本。
     * 供 [LocalizedResources] 覆写的 getText/getString 调用（无格式化参数）。
     */
    fun lookupOverlay(res: Resources, resId: Int): String? {
        val map = currentLanguageMap ?: return null
        val name = try { res.getResourceEntryName(resId) } catch (e: Exception) { return null }
        return map[name]
    }

    /**
     * 获取指定资源的本地化字符串（含格式化参数）。
     * 用于代码中带格式化参数 getString(R.string.xxx, args...) 的定向替换调用。
     * 先查当前语言 JSON 覆盖，未命中回退内置简体中文，再应用格式化参数。
     */
    fun getString(context: Context, resId: Int, vararg formatArgs: Any?): String {
        val raw = lookupOverlay(context.resources, resId) ?: context.getString(resId)
        if (formatArgs.isEmpty()) return raw
        return try {
            String.format(Locale.getDefault(), raw, *formatArgs)
        } catch (e: Exception) {
            raw
        }
    }

    /**
     * 下载指定语言的 JSON 并存入本地。
     * 校验下载内容中的 versionCode 是否与当前 App 一致（不一致仍保存，是否采用由调用方决定）。
     *
     * @return 成功返回 true；失败返回 false
     */
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
                // 简单校验是 JSON 且含 languageCode 字段
                val obj = JSONObject(jsonStr)
                if (!obj.has("strings")) return false
                val file = getLanguageFile(context, code)
                file.parentFile?.mkdirs()
                // 原子落盘：语言包是整份覆写，半截 JSON 会让该语言此后一直加载失败，
                // 而 refreshLanguageMap 解析失败只会退化成空映射，用户看到的是整片空白文案。
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

    /**
     * 根据当前语言刷新内存中的覆盖映射。
     * 内置简体中文清空映射（使用内置资源）；其他语言从已下载 JSON 加载。
     * 「跟随系统」会先解析系统语言，若为受支持且已下载的语言则加载其 JSON。
     */
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

    /**
     * 当前实际用于显示的语言码。
     * - 用户明确选择了某语言：直接返回该语言码；
     * - 「跟随系统」：解析系统 Locale，映射为受支持语言；
     *   若该语言不是内置简体中文且对应 JSON 未下载，则回退内置简体中文。
     */
    fun getEffectiveLanguage(context: Context): String {
        val pref = getLanguage(context)
        if (pref != FOLLOW_SYSTEM) return pref
        val sys = resolveSystemLanguageCode() ?: return BUILTIN_LANG
        if (sys == BUILTIN_LANG) return BUILTIN_LANG
        return if (isLanguageInstalled(context, sys)) sys else BUILTIN_LANG
    }

    /**
     * 应用启动时检查「当前语言」的资源是否需要下载/更新，需要则静默下载并在完成后切换。
     *
     * - 只检查当前语言（用户明确选择的语言，或「跟随系统」解析出的系统语言），不遍历其他语言；
     * - 不弹窗、不需要用户确认：未安装（如英文系统用户首次启动）或有新版本时自动下载；
     * - 下载成功后刷新覆盖映射并发送 [ACTION_LANGUAGE_UPDATED]，已在显示的界面据此重建。
     *
     * 内置简体中文无需检查；系统语言不受支持时不下载。
     */
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

    /**
     * 启动时「当前语言」对应的语言码，用于检查更新。
     * - 用户明确选择某语言：返回该语言码；
     * - 「跟随系统」：返回系统 Locale 解析出的语言码；不受支持时返回 null（不下载）。
     */
    private fun resolveCurrentLanguageCode(context: Context): String? {
        val pref = getLanguage(context)
        if (pref != FOLLOW_SYSTEM) return pref
        return resolveSystemLanguageCode()
    }

    /** 把系统 Locale 映射为本应用支持的语言码；系统语言不受支持时返回 null。 */
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

    /**
     * 取真实系统 Locale。
     * 使用 Resources.getSystem()，避免用户曾切换过应用内语言后
     * context.resources.configuration 已被应用 Locale 覆盖，导致「跟随系统」解析错误。
     */
    private fun systemLocale(): Locale? {
        return try {
            Resources.getSystem().configuration.locales[0]
        } catch (e: Exception) {
            null
        }
    }

    /** 判断是否为繁体中文（优先看脚本 Hant，其次看地区 TW/HK/MO）。 */
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

    /**
     * 返回包装了指定 Locale 的 Context。
     * 用于 Application.attachBaseContext()。
     * 由于已删除内置多语言资源，locale 仅影响格式化/排序等区域行为，
     * 界面文案由 currentLanguageMap 覆盖。
     *
     * 「跟随系统」时：若系统语言受支持且已下载对应 JSON，则按该语言显示；
     * 否则回退内置简体中文（此时不改变 Locale，保持系统默认）。
     */
    fun wrapContext(context: Context): Context {
        // 每次进入界面时按当前语言刷新覆盖映射，保证重启后 JSON 翻译仍生效
        refreshLanguageMap(context)
        val effective = getEffectiveLanguage(context)
        if (effective == BUILTIN_LANG) {
            // 内置简体中文：使用系统默认 Locale，无需资源包装
            return context
        }
        val locale = Locale.forLanguageTag(effective)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        val wrapped = context.createConfigurationContext(config)
        // 关键：必须让 mBase.getResources() 也走 LocalizedResources 的 JSON 覆盖，
        // 否则布局 XML 里的 @string 引用在 inflate 时用的是普通 Resources，
        // 会因缺少内置多语言资源而回退到中文，与代码 LanguageUtils.getString 不一致。
        return LocalizedContext(wrapped)
    }

    /**
     * 让 mBase.getResources() 也返回 LocalizedResources，
     * 使代码经 base context 取资源时同样走 JSON 覆盖。
     */
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
/**
 * 切换语言并重建当前 Activity（不杀进程）
 */
fun setAppLanguage(activity: Activity, languageCode: String) {
    saveLanguage(activity, languageCode)
    activity.recreate()
}
}
