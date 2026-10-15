package com.tilixibiesi.bili

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.WebViewMetricsCleaner
import com.tilixibiesi.util.ActivityTracker
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.WindowUtils
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * B 站登录与「登录态失效」的统一入口。
 *
 * 以前登录 WebView 只存在于搜索页，长按 B 站按钮才能唤出；现在把三件事统一收在这里：
 *
 * 1. [ensureLoggedIn] / [showLogin]：在任何界面弹出同一个全屏登录覆盖层，
 *    登录成功后提取 SESSDATA / bili_jct 等写入本地 Cookie 文件；
 * 2. [handleApiCode] / [isAuthInvalid]：所有使用 Cookie 的接口只要返回 -101，
 *    统一走 [requestRelogin] 弹出登录页，不再各处重复判断；
 * 3. [flushPendingRequest]：请求发生时没有前台界面（如后台播放服务），
 *    先记下来，等界面回到前台再弹。
 */
object BiliLoginHelper {

    /** B 站「账号未登录 / 登录凭证已失效」的返回码。 */
    const val CODE_NOT_LOGIN = -101

    private const val LOGIN_URL = "https://passport.bilibili.com/login"
    private const val COOKIE_QUERY_URL = "https://bilibili.com"
    private const val NAV_API = "https://api.bilibili.com/x/web-interface/nav"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var overlay: FrameLayout? = null

    private var webView: WebView? = null

    private var activityRef: Activity? = null

    /** 登录成功后要接着执行的动作（例如用户点了下载但当时还没登录）。 */
    private var onLoginSuccess: (() -> Unit)? = null

    @Volatile
    private var reloginRequested = false

    @Volatile
    private var loggedInThisSession = false

    @Volatile
    private var storedCookieChecked = false

    /** 当前是否正展示登录页。 */
    val isShowing: Boolean
        get() = webView != null

    /** B 站接口返回码是否表示「未登录」。 */
    fun isAuthInvalid(code: Int): Boolean = code == CODE_NOT_LOGIN

    /**
     * 所有使用 Cookie 的接口统一的失效判定。
     *
     * 只有「本次请求确实带了 Cookie」且返回 [CODE_NOT_LOGIN] 时才触发重新登录：
     * 不带 Cookie 的匿名请求拿到 -101 属于正常情况（用户没开完整源），不该弹登录页。
     *
     * @return true 表示登录态已失效、调用方应放弃本次结果
     */
    fun handleApiCode(code: Int, cookie: String, activity: Activity? = null): Boolean {
        if (cookie.isEmpty() || !isAuthInvalid(code)) return false
        requestRelogin(activity)
        return true
    }

    /**
     * 启动时校验一次本地保存的 Cookie（每个进程只校验一次）。
     * 之后中途失效由各接口的 [handleApiCode] 兜住。
     */
    fun verifyStoredCookie(activity: Activity?) {
        if (storedCookieChecked) return
        val target = activity ?: ActivityTracker.current() ?: return
        if (!SpUtils.isFullBiliSource(target)) return
        val cookie = SpUtils.getBiliCookie(target)
        if (cookie.isEmpty()) return
        storedCookieChecked = true
        AppExecutors.io.execute {
            if (navCode(cookie) == CODE_NOT_LOGIN) requestRelogin(null)
        }
    }

    /** 登录态失效时的统一处理：提示 + 弹登录页。 */
    fun requestRelogin(activity: Activity? = null) {
        mainHandler.post {
            val target = activity ?: ActivityTracker.current()
            if (target == null || target.isFinishing) {
                // 没有可见界面（例如后台播放服务）：先记下请求，等界面回到前台再弹
                reloginRequested = true
                return@post
            }
            // 已经有登录页在前台（例如用户正在别的页面登录），不再重复弹
            if (isShowing || activityRef != null) return@post
            reloginRequested = false
            onLoginSuccess = null
            Toast.makeText(
                target,
                LanguageUtils.getString(target, R.string.login_expired),
                Toast.LENGTH_LONG
            ).show()
            showLogin(target)
        }
    }

    /**
     * 弹登录页但不提示（用于用户主动触发的流程）。
     */
    private fun showLoginSilently(activity: Activity) {
        if (isShowing || activityRef != null) return
        showLogin(activity)
    }
    /** 界面回到前台时调用：补弹之前在后台错过的登录请求。 */
    fun flushPendingRequest(activity: Activity) {
        if (!reloginRequested || isShowing) return
        reloginRequested = false
        showLogin(activity)
    }

    /**
     * 需要凭据的动作入口：已有 Cookie 就直接执行；没有就先登录，登录成功后再执行。
     * 必须在主线程调用。
     */
    fun ensureLoggedIn(activity: Activity?, onReady: () -> Unit) {
        val target = activity ?: ActivityTracker.current()
        if (target == null || target.isFinishing) return
        if (SpUtils.getBiliCookie(target).isNotEmpty()) {
            onReady()
            return
        }
        if (isShowing || activityRef != null) return
        onLoginSuccess = onReady
        showLoginSilently(target)
    }

    /** 弹出全屏登录覆盖层；已经有登录页时直接返回。 */
    fun showLogin(activity: Activity) {
        if (isShowing) return
        val root = activity.window.decorView as? ViewGroup ?: return
        activityRef = activity
        loggedInThisSession = false

        val density = activity.resources.displayMetrics.density
        val container = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (3 * density).toInt(),
                Gravity.TOP
            )
        }

        val closeBtn = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = LanguageUtils.getString(activity, R.string.close_login_dialog)
            layoutParams = FrameLayout.LayoutParams(
                (48 * density).toInt(),
                (48 * density).toInt(),
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, (12 * density).toInt(), (12 * density).toInt(), 0) }
            setOnClickListener { closeLogin(extract = true) }
        }

        val wv = WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        configure(wv)
        attachWebViewClient(wv, progress)

        container.addView(wv)
        container.addView(progress)
        container.addView(closeBtn)
        root.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        overlay = container
        webView = wv
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.loadUrl(LOGIN_URL)
        WindowUtils.setFullScreen(activity)
    }

    /**
     * 关闭登录页。
     *
     * @param extract 是否在关闭前再尝试提取一次 Cookie（用户手动关闭时为 true）
     */
    fun closeLogin(extract: Boolean) {
        if (extract) extractCookie()
        teardown()
    }

    /** Activity 销毁时兜底清理，避免 WebView 泄漏。 */
    fun onActivityDestroyed(activity: Activity) {
        if (activityRef !== activity) return
        teardown()
    }

    private fun teardown() {
        val container = overlay
        val wv = webView
        val activity = activityRef
        overlay = null
        webView = null
        activityRef = null
        onLoginSuccess = null
        loggedInThisSession = false

        runCatching {
            wv?.stopLoading()
            wv?.webViewClient = WebViewClient()
            wv?.webChromeClient = null
            (wv?.parent as? ViewGroup)?.removeView(wv)
            wv?.destroy()
        }
        runCatching { (container?.parent as? ViewGroup)?.removeView(container) }
        activity?.let { WindowUtils.setFullScreen(it) }

        AppExecutors.io.execute {
            activity?.let { WebViewMetricsCleaner.purgeWebViewArtifacts(it) }
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString =
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36 Edg/120.0.0.0"
        }
    }

    private fun attachWebViewClient(wv: WebView, progress: ProgressBar) {
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                extractCookie()
                // 部分机型登录成功后不会跳转、只刷新页面：主动确认一次登录态
                if (!loggedInThisSession) verifyLoggedIn()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                progress.visibility = View.GONE
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }
        }
    }

    private fun verifyLoggedIn() {
        val activity = activityRef ?: return
        AppExecutors.io.execute {
            val cookie = readWebViewCookie()
            if (cookie.isEmpty()) return@execute
            if (navCode(cookie) == 0) mainHandler.post { finishLogin(activity, cookie) }
        }
    }

    private fun extractCookie() {
        if (loggedInThisSession) return
        val activity = activityRef ?: return
        val cookie = readWebViewCookie()
        if (cookie.isEmpty()) return
        if (navCode(cookie) != 0) return
        finishLogin(activity, cookie)
    }

    private fun finishLogin(activity: Activity, cookie: String) {
        if (loggedInThisSession) return
        loggedInThisSession = true
        SpUtils.saveBiliCookie(activity, cookie)
        Toast.makeText(
            activity,
            LanguageUtils.getString(activity, R.string.login_success),
            Toast.LENGTH_SHORT
        ).show()
        val callback = onLoginSuccess
        mainHandler.postDelayed({
            teardown()
            callback?.invoke()
        }, 400)
    }

    /** 只取登录必需且稳定的字段，避免把无关 Cookie 一起写进文件。 */
    private fun readWebViewCookie(): String {
        val raw = runCatching {
            CookieManager.getInstance().getCookie(COOKIE_QUERY_URL) ?: ""
        }.getOrDefault("")
        if (raw.isEmpty()) return ""
        val sessdata = extractValue(raw, "SESSDATA")
        val biliJct = extractValue(raw, "bili_jct")
        if (sessdata.isNullOrEmpty() || biliJct.isNullOrEmpty()) return ""
        return buildString {
            append("SESSDATA=").append(sessdata)
            append("; bili_jct=").append(biliJct)
            append("; DedeUserID=").append(extractValue(raw, "DedeUserID") ?: "")
            append("; DedeUserID__ckMd5=").append(extractValue(raw, "DedeUserID__ckMd5") ?: "")
            append("; sid=").append(extractValue(raw, "sid") ?: "")
        }
    }

    /** 调用 nav 接口，返回 B 站返回码；请求失败返回 -1。 */
    fun navCode(cookie: String): Int {
        if (cookie.isEmpty()) return -1
        return try {
            val conn = URL(NAV_API).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode != 200) return -1
                JSONObject(conn.inputStream.bufferedReader().readText()).optInt("code", -1)
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun extractValue(cookieString: String, key: String): String? =
        Regex("$key=([^;]*)").find(cookieString)?.groupValues?.get(1)?.trim()

    /** 由 BaseActivity 在 onResume 时调用：补弹后台错过的登录请求。 */
    fun onActivityResumed(activity: Activity) {
        if (!isShowing) flushPendingRequest(activity)
    }

    /**
     * 返回键：登录页正在展示时先关闭它。
     *
     * @return true 表示已消费本次返回键
     */
    fun handleBackPressed(): Boolean {
        if (!isShowing) return false
        closeLogin(extract = true)
        return true
    }
}
