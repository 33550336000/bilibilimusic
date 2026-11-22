package com.tilixibiesi.bili

import com.tilixibiesi.data.SpUtils

import android.content.Context

/**
 * B站搜索类接口的限流状态。
 *
 * 背景：主页面下拉刷新失败时会显示“刷新失败”，但“我的”页只看 `x/web-interface/nav`，
 * 那个接口对限流几乎不敏感，于是出现“首页被限流、我的页仍显示正常”的矛盾。
 *
 * 这里把限流当成一个独立状态维护，判定只看两个时间戳：
 * 最近一次确认被限流 [limitedAt] 与最近一次搜索请求成功 [okAt]，
 * **nav 的成功不参与清除**，只有搜索侧的成功才说明限流解除。
 *
 * 解除方式按优先级：
 * 1. 被动恢复：任何搜索请求成功即 [markOk]；
 * 2. 主动探测：状态有效期内允许“我的”页发一次轻量探测请求，
 *    失败则 [markProbeFailed] 把下次探测间隔翻倍（1→2→4…封顶 10 分钟），
 *    避免探测本身延长限流窗口；
 * 3. 超时兜底：[isLimited] 超过 [TTL_MS] 自动失效，回落到 nav 的结论，
 *    不会出现“永久限流”假象。
 */
object BiliRateLimit {

    /** 状态有效期：最后一次确认限流后这么久仍未解除，就回落到 nav 结论。 */
    private const val TTL_MS = 30 * 60 * 1000L

    /** 首次探测间隔（同时也是新的限流事件发生后允许立刻探测一次）。 */
    private const val BASE_PROBE_DELAY_MS = 60 * 1000L

    /** 探测退避上限。 */
    private const val MAX_PROBE_DELAY_MS = 10 * 60 * 1000L

    /** 落盘节流：状态未发生变化时，最多这么写一次磁盘。 */
    private const val PERSIST_THROTTLE_MS = 60 * 1000L

    @Volatile
    private var limitedAt = 0L

    @Volatile
    private var okAt = 0L

    @Volatile
    private var nextProbeAt = 0L

    @Volatile
    private var probeBackoffMs = BASE_PROBE_DELAY_MS

    @Volatile
    private var loaded = false

    private var appContext: Context? = null

    /** 进程启动时调用一次，用于跨进程重启保留状态。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        val context = appContext ?: return
        loaded = true
        limitedAt = SpUtils.getBiliRateLimitedAt(context)
        okAt = SpUtils.getBiliRateLimitOkAt(context)
        nextProbeAt = SpUtils.getBiliRateLimitNextProbeAt(context)
        probeBackoffMs = SpUtils.getBiliRateLimitBackoffMs(context)
    }

    private fun persist() {
        val context = appContext ?: return
        SpUtils.saveBiliRateLimitState(context, limitedAt, okAt, nextProbeAt, probeBackoffMs)
    }

    /** 被动发现限流（请求异常/业务 code 表明被限制）。允许随后立刻探测一次。 */
    fun markLimited() {
        ensureLoaded()
        val now = System.currentTimeMillis()
        // 连续失败时不必每次都落盘：状态没变就不写，减少磁盘 IO。
        val changed = limitedAt <= okAt || now - limitedAt > PERSIST_THROTTLE_MS
        limitedAt = now
        nextProbeAt = now
        probeBackoffMs = BASE_PROBE_DELAY_MS
        if (changed) persist()
    }

    /** 探测仍未通过：续期并按退避拉长下次探测时间。 */
    fun markProbeFailed() {
        ensureLoaded()
        val now = System.currentTimeMillis()
        limitedAt = now
        nextProbeAt = now + probeBackoffMs
        probeBackoffMs = (probeBackoffMs * 2).coerceAtMost(MAX_PROBE_DELAY_MS)
        persist()
    }

    /** 搜索请求成功：限流解除。 */
    fun markOk() {
        ensureLoaded()
        val now = System.currentTimeMillis()
        // 正常浏览时每个分页成功都会走到这里，只在状态真的发生变化时落盘。
        val changed = limitedAt > okAt || now - okAt > PERSIST_THROTTLE_MS
        okAt = now
        nextProbeAt = 0L
        probeBackoffMs = BASE_PROBE_DELAY_MS
        if (changed) persist()
    }

    fun isLimited(): Boolean {
        ensureLoaded()
        if (limitedAt <= okAt) return false
        return System.currentTimeMillis() - limitedAt < TTL_MS
    }

    fun shouldProbeNow(): Boolean =
        isLimited() && System.currentTimeMillis() >= nextProbeAt

    /** 业务错误码是否属于限流/风控。 */
    fun isRateLimitCode(code: Int): Boolean = code == -412 || code == -799 || code == -352

    /** 请求异常文案是否说明被限流：HttpURLConnection 对 412 等状态码只会给一句 IOException。 */
    fun isRateLimitMessage(message: String?): Boolean {
        if (message.isNullOrEmpty()) return false
        return message.contains("412") || message.contains("403") || message.contains("-412")
    }
}
