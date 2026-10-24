package com.tilixibiesi.bili

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.tilixibiesi.util.PredictiveBack
import com.tilixibiesi.util.WindowUtils
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BiliLoginHelper {

    private const val TAG = "BiliLoginHelper"

    const val CODE_NOT_LOGIN = -101

    private const val LOGIN_URL = "https://passport.bilibili.com/login"
    private const val COOKIE_QUERY_URL = "https://bilibili.com"
    private const val NAV_API = "https://api.bilibili.com/x/web-interface/nav"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var overlay: FrameLayout? = null

    private var webView: WebView? = null

    private var activityRef: Activity? = null

    private var onLoginSuccess: (() -> Unit)? = null

    @Volatile
    private var reloginRequested = false

    @Volatile
    private var loggedInThisSession = false

    @Volatile
    private var storedCookieChecked = false

    @Volatile
    private var cookieCheckInFlight = false

    val isShowing: Boolean
        get() = webView != null

    fun isAuthInvalid(code: Int): Boolean = code == CODE_NOT_LOGIN

    fun handleApiCode(code: Int, cookie: String, activity: Activity? = null): Boolean {
        if (cookie.isEmpty() || !isAuthInvalid(code)) return false
        requestRelogin(activity)
        return true
    }

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

    fun requestRelogin(activity: Activity? = null) {
        mainHandler.post {
            val target = activity ?: ActivityTracker.current()
            if (target == null || target.isFinishing) {
                reloginRequested = true
                return@post
            }
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

    private fun showLoginSilently(activity: Activity) {
        if (isShowing || activityRef != null) return
        showLogin(activity)
    }
    fun flushPendingRequest(activity: Activity) {
        if (!reloginRequested || isShowing) return
        reloginRequested = false
        showLogin(activity)
    }

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
        PredictiveBack.register(
            activity,
            PredictiveBack.TAG_LOGIN,
            onInvoke = { closeLogin(extract = true) },
            onProgress = { progress ->
                container.scaleX = 1f - 0.06f * progress
                container.scaleY = 1f - 0.06f * progress
                container.alpha = 1f - 0.35f * progress
            },
            onCancel = {
                container.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
            }
        )
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.loadUrl(LOGIN_URL)
        WindowUtils.setFullScreen(activity)
    }

    fun closeLogin(extract: Boolean) {
        if (extract) extractCookie()
        teardown()
    }

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
        activity?.let { PredictiveBack.unregister(it, PredictiveBack.TAG_LOGIN) }

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

    /**
     * 校验并落地 Cookie。
     * 该方法会被 onPageFinished / closeLogin 这两个**主线程**回调触发，
     * 所以网络校验必须挪到 IO 线程——否则每次页面跳转都会阻塞 UI 最多 8 秒。
     */
    private fun extractCookie() {
        if (loggedInThisSession || cookieCheckInFlight) return
        val activity = activityRef ?: return
        val cookie = readWebViewCookie()
        if (cookie.isEmpty()) return
        cookieCheckInFlight = true
        AppExecutors.io.execute {
            val code = navCode(cookie)
            cookieCheckInFlight = false
            if (code != 0) {
                Log.d(TAG, "Cookie 校验未通过: code=$code")
                return@execute
            }
            mainHandler.post {
                // 期间用户可能已关闭登录窗
                if (activityRef === activity) finishLogin(activity, cookie)
            }
        }
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

    fun onActivityResumed(activity: Activity) {
        if (!isShowing) flushPendingRequest(activity)
    }

    fun handleBackPressed(): Boolean {
        if (!isShowing) return false
        closeLogin(extract = true)
        return true
    }
}
