package com.tilixibiesi.ui.page

import com.tilixibiesi.R
import com.tilixibiesi.bili.BiliCoverLoader
import com.tilixibiesi.bili.BiliLoginHelper
import com.tilixibiesi.data.BiliProfileStore
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.ui.MainPagerActivity
import com.tilixibiesi.ui.widget.LiquidGlassLayout
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.AppLog
import com.tilixibiesi.util.DialogHelper
import com.tilixibiesi.util.FontUtils
import com.tilixibiesi.util.ToastUtils

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * “我的”页：顶部 B站 头像与昵称，下面显示账号状态（已过期 / 已限流等），
 * 设置页只能从这里进入，所以本页同时是设置的唯一入口。
 */
class MePage(base: Context) : BasePage(base) {
    private val TAG = "MePage"

    private companion object {
        const val NAV_API = "https://api.bilibili.com/x/web-interface/nav"

        const val CODE_OK = 0
        const val CODE_NOT_LOGIN = BiliLoginHelper.CODE_NOT_LOGIN
        const val CODE_RATE_LIMITED = -412
        const val CODE_FORBIDDEN = -799
        const val CODE_NETWORK_ERROR = -1

        /** onResume 与 onPageShow 几乎同时触发，用它合并同一次进入的重复请求。 */
        const val DEDUP_INTERVAL_MS = 2_000L

        val COLOR_OK = 0xFF2E7D32.toInt()
        val COLOR_WARN = 0xFFEF6C00.toInt()
        val COLOR_ERROR = 0xFFE53935.toInt()
        val COLOR_NEUTRAL = 0xFF888888.toInt()

        val CARD_IDS = intArrayOf(
            R.id.card_me_profile,
            R.id.card_me_status,
            R.id.card_me_actions
        )
    }

    private var tvName: TextView? = null
    private var tvUid: TextView? = null
    private var tvStatus: TextView? = null
    private var tvSyncStatus: TextView? = null
    private var tvStatusDetail: TextView? = null
    private var ivAvatar: ImageView? = null
    private var btnLogin: Button? = null
    private var btnLogout: Button? = null
    private var btnMainSource: Button? = null

    private var statusChecked = false
    private var statusLoading = false
    private var lastCheckAt = 0L
    /** Cookie 被服务端判为失效时为 true，点登录按钮需要先清掉死 Cookie。 */
    private var expired = false
    /** 本页缓存的位置信息，用于判断网络结果是否需要落盘。 */
    private var cachedProfile: BiliProfileStore.Profile? = null
    /** 当前头像对应的地址，避免同一张头像重复解码/下载。 */
    private var shownAvatarUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_me)
        tvName = findViewById(R.id.tv_me_name)
        tvUid = findViewById(R.id.tv_me_uid)
        tvStatus = findViewById(R.id.tv_me_status)
        tvSyncStatus = findViewById(R.id.tv_me_sync_status)
        tvStatusDetail = findViewById(R.id.tv_me_status_detail)
        ivAvatar = findViewById(R.id.iv_me_avatar)
        btnLogin = findViewById(R.id.btn_me_login)
        btnLogout = findViewById(R.id.btn_me_logout)

        findViewById<TextView>(R.id.title_me_page)?.text =
            LanguageUtils.getString(this, R.string.me_title)

        btnLogin?.setOnClickListener { startLogin() }
        btnLogout?.setOnClickListener { confirmLogout() }
        findViewById<Button>(R.id.btn_me_reauth)?.setOnClickListener { startReauth() }
        findViewById<Button>(R.id.btn_me_settings)?.setOnClickListener { openSettings() }
        btnMainSource = findViewById(R.id.btn_me_main_source)
        updateMainSourceLabel()
        btnMainSource?.setOnClickListener { showMainSourceDialog() }

        applyCardGlass()
        if (SpUtils.getBiliCookie(this).isEmpty()) {
            renderLoggedOut()
        } else {
            // 先用本地缓存铺满界面，网络结果回来再按需覆盖，避免昵称/头像位置空一截。
            bindFromCache()
            showStatusChecking(clearDetail = true)
        }
    }

    /**
     * 先用本地缓存渲染身份信息与头像，网络结果回来后再比对。
     * 本地文件缺失（首次登录/升级上来）时保持“检查中”占位，等网络补齐。
     */
    private fun bindFromCache() {
        val profile = BiliProfileStore.load()
        cachedProfile = profile
        if (profile == null || !profile.isComplete) {
            tvName?.visibility = View.INVISIBLE
            tvUid?.visibility = View.GONE
            btnLogin?.visibility = View.GONE
            btnLogout?.visibility = View.GONE
            return
        }
        renderLoggedIn(profile.uname, profile.face, profile.mid)
    }

    override fun onPageShow() {
        refreshAccount(force = false)
        updateMainSourceLabel()
    }

    override fun onResume() {
        refreshAccount(force = false)
    }

    /**
     * 进入本页就拉一次最新账号信息，与本地缓存比对：
     * 一致 → 继续用本地文件（头像不解码网络图）；不一致 → 用网络结果覆盖缓存并重绘。
     * 每次进入都要比对，所以这里只用一个很短的窗口消掉 onResume/onPageShow 的重复请求。
     */
    private fun refreshAccount(force: Boolean) {
        val cookie = SpUtils.getBiliCookie(this)
        if (cookie.isEmpty()) {
            statusChecked = true
            expired = false
            renderLoggedOut()
            return
        }
        if (statusLoading) return
        val fresh = statusChecked && System.currentTimeMillis() - lastCheckAt < DEDUP_INTERVAL_MS
        if (!force && fresh) return
        statusChecked = true
        statusLoading = true
        if (tvStatus?.text.isNullOrEmpty() || tvStatus?.text ==
            LanguageUtils.getString(this, R.string.me_status_checking)
        ) {
            showStatusChecking(clearDetail = false)
        }

        AppExecutors.io.execute {
            val nav = fetchNav(cookie)
            // 只有身份信息真的变了才写文件，避免每次进页都刷一遍磁盘。
            val sameAsCache = nav.code == CODE_OK && !nav.uname.isNullOrEmpty() &&
                cachedProfile?.let {
                    it.isComplete && it.mid == nav.mid &&
                        it.uname == nav.uname && it.face == nav.face.orEmpty()
                } == true
            if (nav.code == CODE_OK && !nav.uname.isNullOrEmpty() && !sameAsCache) {
                BiliProfileStore.saveIdentity(nav.mid, nav.uname, nav.face.orEmpty())
                cachedProfile = BiliProfileStore.load()
            }
            runOnUiThread {
                statusLoading = false
                lastCheckAt = System.currentTimeMillis()
                if (pageView == null) return@runOnUiThread
                // 与缓存一致时头像保持本地文件，不去重新下载。
                renderNav(nav, forceAvatar = !sameAsCache)
            }
        }
    }

    private fun fetchNav(cookie: String): NavResult = try {
        val conn = URL(NAV_API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val httpCode = conn.responseCode
            when (httpCode) {
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val code = json.optInt("code", CODE_NETWORK_ERROR)
                    val data = json.optJSONObject("data")
                    NavResult(
                        code,
                        data?.optString("uname")?.takeIf { it.isNotEmpty() },
                        data?.optString("face")?.takeIf { it.isNotEmpty() },
                        data?.optLong("mid", 0L) ?: 0L
                    )
                }
                412 -> NavResult(CODE_RATE_LIMITED, null, null, 0L)
                else -> NavResult(CODE_NETWORK_ERROR, null, null, 0L)
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    } catch (_: Exception) {
        AppLog.d(TAG, "拉取导航信息失败")
        NavResult(CODE_NETWORK_ERROR, null, null, 0L)
    }

    private fun showStatusChecking(clearDetail: Boolean) {
        tvStatus?.text = LanguageUtils.getString(this, R.string.me_status_checking)
        tvStatus?.setTextColor(COLOR_NEUTRAL)
        if (clearDetail) tvStatusDetail?.text = ""
        bindSyncStatus()
    }

    private fun renderNav(nav: NavResult, forceAvatar: Boolean) {
        val name = nav.uname
        if (nav.code == CODE_OK && !name.isNullOrEmpty()) {
            expired = false
            renderLoggedIn(name, nav.face, nav.mid, forceAvatar)
        } else {
            expired = nav.code == CODE_NOT_LOGIN
            renderLoggedOut()
        }
        bindStatus(nav.code)
    }

    private fun renderLoggedIn(
        name: String,
        face: String?,
        mid: Long,
        forceAvatar: Boolean = false
    ) {
        tvName?.text = name
        tvName?.visibility = View.VISIBLE
        tvName?.setTextColor(FontUtils.color(this))
        if (mid > 0L) {
            tvUid?.text = LanguageUtils.getString(this, R.string.me_uid_format, mid.toString())
            tvUid?.visibility = View.VISIBLE
        } else {
            tvUid?.visibility = View.GONE
        }
        btnLogin?.visibility = View.GONE
        btnLogout?.visibility = View.VISIBLE
        findViewById<Button>(R.id.btn_me_reauth)?.visibility = View.VISIBLE
        showAvatar(face, forceAvatar)
    }

    private fun renderLoggedOut() {
        tvName?.text = LanguageUtils.getString(this, R.string.me_not_logged_in)
        tvName?.visibility = View.VISIBLE
        tvName?.setTextColor(FontUtils.color(this))
        tvUid?.visibility = View.GONE
        btnLogin?.visibility = View.VISIBLE
        btnLogin?.text = LanguageUtils.getString(
            this,
            if (expired) R.string.me_relogin else R.string.me_login
        )
        btnLogout?.visibility = View.GONE
        findViewById<Button>(R.id.btn_me_reauth)?.visibility = View.GONE
        ivAvatar?.setImageResource(R.drawable.ic_me_avatar)
        shownAvatarUrl = null
        bindStatus(null)
    }

    /** [code] 为 null 表示还没校验过（没有 Cookie），只展示未登录提示。 */
    private fun bindStatus(code: Int?) {
        val status = tvStatus ?: return
        val detail = tvStatusDetail
        bindSyncStatus()
        when (code) {
            null -> {
                status.text = LanguageUtils.getString(this, R.string.me_not_logged_in)
                status.setTextColor(COLOR_NEUTRAL)
                detail?.text =
                    LanguageUtils.getString(this, R.string.me_status_not_logged_in_detail)
            }
            CODE_OK -> {
                status.text = LanguageUtils.getString(this, R.string.me_status_ok)
                status.setTextColor(COLOR_OK)
                detail?.text = LanguageUtils.getString(this, R.string.me_status_ok_detail)
            }
            CODE_NOT_LOGIN -> {
                status.text = LanguageUtils.getString(this, R.string.me_status_expired)
                status.setTextColor(COLOR_ERROR)
                detail?.text = LanguageUtils.getString(this, R.string.me_status_expired_detail)
            }
            CODE_RATE_LIMITED, CODE_FORBIDDEN -> {
                status.text = LanguageUtils.getString(this, R.string.me_status_limited)
                status.setTextColor(COLOR_WARN)
                detail?.text = LanguageUtils.getString(this, R.string.me_status_limited_detail)
            }
            CODE_NETWORK_ERROR -> {
                status.text = LanguageUtils.getString(this, R.string.me_status_offline)
                status.setTextColor(COLOR_NEUTRAL)
                detail?.text = LanguageUtils.getString(this, R.string.me_status_offline_detail)
            }
            else -> {
                status.text = LanguageUtils.getString(this, R.string.me_status_unknown, code)
                status.setTextColor(COLOR_NEUTRAL)
                detail?.text =
                    LanguageUtils.getString(this, R.string.me_status_unknown_detail, code)
            }
        }
    }

    /**
     * “最近同步”展示的是**最近一次登录成功的时间**，不是最近一次调用业务接口的时间。
     * 时间跟着账号信息一起存在 cookie 目录里，未登录时整行隐藏。
     */
    private fun bindSyncStatus() {
        val tv = tvSyncStatus ?: return
        if (SpUtils.getBiliCookie(this).isEmpty()) {
            tv.visibility = View.GONE
            return
        }
        tv.visibility = View.VISIBLE
        val loginAt = BiliProfileStore.loginTime()
        if (loginAt <= 0L) {
            tv.text = LanguageUtils.getString(this, R.string.bili_sync_idle)
            tv.setTextColor(COLOR_NEUTRAL)
            return
        }
        val time = DateUtils.formatDateTime(
            this,
            loginAt,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
        )
        tv.text = LanguageUtils.getString(this, R.string.bili_sync_ok, time)
        tv.setTextColor(COLOR_OK)
    }

    /**
     * 头像取用顺序：本地文件（且地址未变）→ 网络。
     * [face] 与本地记录的地址一致时直接解本地图，只有换了头像才重新下载并覆盖缓存。
     */
    private fun showAvatar(face: String?, forceDownload: Boolean) {
        val view = ivAvatar ?: return
        val url = face.orEmpty()
        if (url.isEmpty()) {
            view.setImageResource(R.drawable.ic_me_avatar)
            shownAvatarUrl = null
            return
        }
        if (!forceDownload && url == shownAvatarUrl) return
        if (!forceDownload && url == cachedProfile?.face) {
            val local = BiliProfileStore.loadAvatar()
            if (local != null) {
                view.setImageBitmap(local)
                shownAvatarUrl = url
                return
            }
        }
        view.setImageResource(R.drawable.ic_me_avatar)
        BiliCoverLoader.fetch(url) { bitmap ->
            if (pageView == null) return@fetch
            if (bitmap == null) return@fetch
            ivAvatar?.setImageBitmap(bitmap)
            shownAvatarUrl = url
            AppExecutors.io.execute { BiliProfileStore.saveAvatar(bitmap) }
        }
    }

    private fun startLogin() {
        // Cookie 已失效时先清掉，否则 ensureLoggedIn 看到有 Cookie 会直接回调，登录窗根本不弹。
        if (expired) SpUtils.saveBiliCookie(this, "")
        BiliLoginHelper.ensureLoggedIn(activity) {
            runOnUiThread {
                statusChecked = false
                refreshAccount(force = true)
            }
        }
    }

    /** 已登录状态下的“重新认证”：无条件重开登录窗，用于凭证过期或账号切换。 */
    private fun startReauth() {
        BiliLoginHelper.reauthenticate(activity) {
            runOnUiThread {
                statusChecked = false
                refreshAccount(force = true)
            }
        }
    }

    private fun confirmLogout() {
        DialogHelper.createStyledDialog(
            activity,
            AlertDialog.Builder(this)
                .setTitle(LanguageUtils.getString(this, R.string.me_logout_title))
                .setMessage(LanguageUtils.getString(this, R.string.me_logout_message))
                .setPositiveButton(LanguageUtils.getString(this, R.string.btn_ok)) { _, _ ->
                    SpUtils.saveBiliCookie(this, "")
                    BiliProfileStore.clear()
                    cachedProfile = null
                    statusChecked = true
                    expired = false
                    renderLoggedOut()
                    ToastUtils.show(this, LanguageUtils.getString(this, R.string.me_logout_done))
                }
                .setNegativeButton(LanguageUtils.getString(this, R.string.btn_cancel), null)
        )
    }

    private fun openSettings() {
        (activity as? MainPagerActivity)?.openSettingsPage()
    }

    private fun updateMainSourceLabel() {
        val biliZone = SpUtils.isMainSourceBiliZone(this)
        btnMainSource?.text = LanguageUtils.getString(
            this,
            if (biliZone) R.string.btn_me_main_source_zone else R.string.btn_me_main_source_builtin
        )
    }

    /** 卡片最下方按钮：在主页面「B站音乐分区」与旧的「内置歌曲列表」之间二选一。 */
    private fun showMainSourceDialog() {
        val biliZone = SpUtils.isMainSourceBiliZone(this)
        DialogHelper.createStyledDialog(
            activity,
            AlertDialog.Builder(this)
                .setTitle(LanguageUtils.getString(this, R.string.dialog_title_me_main_source))
                .setMessage(
                    LanguageUtils.getString(
                        this,
                        if (biliZone) R.string.dialog_msg_me_main_source_to_builtin
                        else R.string.dialog_msg_me_main_source_to_zone
                    )
                )
                .setPositiveButton(LanguageUtils.getString(this, R.string.btn_ok)) { _, _ ->
                    val target = !biliZone
                    SpUtils.setMainSourceBiliZone(this, target)
                    updateMainSourceLabel()
                    (activity as? MainPagerActivity)?.onMainSourceChanged()
                    ToastUtils.show(
                        this,
                        LanguageUtils.getString(
                            this,
                            if (target) R.string.toast_me_main_source_zone
                            else R.string.toast_me_main_source_builtin
                        )
                    )
                }
                .setNegativeButton(LanguageUtils.getString(this, R.string.btn_cancel), null)
        )
    }

    private fun applyCardGlass() {
        val host = activity as? MainPagerActivity ?: return
        val enabled = SpUtils.getLiquidGlassCardsEnabled(this) && LiquidGlassLayout.isAvailable()
        for (id in CARD_IDS) {
            val card = findViewById<LiquidGlassLayout>(id) ?: continue
            if (!enabled) {
                card.solidWhenDisabled = false
                card.background = resources.getDrawable(R.drawable.card_background_semi, null)
                card.glassEnabled = false
                continue
            }
            card.background = null
            card.solidWhenDisabled = false
            card.tiltEnabled = false
            card.pressEnabled = false
            card.cornerRadiusDp = 32f
            card.blurRadiusDp = 5f
            card.backdropSource = host.backgroundLayer
            card.glassEnabled = true
        }
        host.invalidateGlassBackdrop()
    }

    private data class NavResult(
        val code: Int,
        val uname: String?,
        val face: String?,
        val mid: Long
    )
}
