package com.tilixibiesi.ui

import com.tilixibiesi.R

import android.content.Intent
import android.view.MotionEvent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import com.tilixibiesi.data.AppPermissionsBridge
import com.tilixibiesi.bili.BiliLoginHelper
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.ui.page.BasePage
import com.tilixibiesi.ui.page.PageHost
import com.tilixibiesi.ui.page.PlaylistPage
import com.tilixibiesi.ui.page.SearchPage
import com.tilixibiesi.ui.page.SettingsPage
import com.tilixibiesi.ui.page.SongsPage
import com.tilixibiesi.data.ClickFxManager
import com.tilixibiesi.data.AppBackgroundListener
import com.tilixibiesi.data.WebViewMetricsCleaner
import com.tilixibiesi.ui.widget.ClickFxOverlay
import com.tilixibiesi.ui.widget.HorizontalPager
import com.tilixibiesi.ui.widget.LiquidGlassLayout
import com.tilixibiesi.util.PredictiveBack
import com.tilixibiesi.util.WindowUtils

class MainPagerActivity : BaseActivity(), PageHost {

    companion object {
        const val PAGE_PLAYLIST = 0
        const val PAGE_SONGS = 1
        const val PAGE_SEARCH = 2
        const val PAGE_SETTINGS = 3

        const val EXTRA_TARGET_PAGE = "target_page"
        const val EXTRA_ADJUST_SEARCH_BUTTON = "adjust_search_button"
        const val TAG_CLICK_FX = "click_fx_overlay"
    }

    private lateinit var pager: HorizontalPager
    private lateinit var pages: Array<BasePage>
    private lateinit var floatingPlayerBar: View
    private var playerBarWanted = false
    private var videoFullscreen = false
    private var playerBarInset = 0
    private var fxOverlay: ClickFxOverlay? = null

    private var currentPosition = PAGE_SONGS
    private var started = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pages = arrayOf(
            PlaylistPage(this),
            SongsPage(this),
            SearchPage(this),
            SettingsPage(this)
        )

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        pager = HorizontalPager(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            adapter = object : HorizontalPager.Adapter {
                override fun getCount(): Int = pages.size
                override fun instantiateView(position: Int): View {
                    val page = pages[position]
                    return page.performCreate(this@MainPagerActivity, null).also { view ->
                        if (playerBarInset > 0) applyPlayerBarInsetTo(view)
                        if (position == currentPosition) notifyPageShown(position)
                    }
                }
            }
            onPageChangeListener = { pos -> handlePageChanged(pos) }
            onScrollStateChanged = { _ -> invalidateGlassBackdrop() }
            dragGuard = { _, _ ->
                (pages.getOrNull(PAGE_SEARCH) as? SearchPage)?.isVideoFullscreenActive == true
            }
        }
        applySearchPageAvailability()
        root.addView(pager)
        // 悬浮播放控制栏与翻页控件平级：跟着窗口，不跟着页面滑动
        floatingPlayerBar = layoutInflater.inflate(R.layout.layout_floating_player, root, false)
        (floatingPlayerBar as? LiquidGlassLayout)?.apply {
            backdropSource = pager
            glassEnabled = SpUtils.getLiquidGlassEnabled(this@MainPagerActivity)
        }
        floatingPlayerBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPlayerBarInset()
        }
        root.addView(floatingPlayerBar)
        setContentView(root)
        WindowUtils.setFullScreen(this)

        val target = intent.getIntExtra(EXTRA_TARGET_PAGE, PAGE_SONGS).coerceIn(0, pages.size - 1)
        val safeTarget =
            if (target == PAGE_SEARCH && !SpUtils.getSearchMode(this)) PAGE_SONGS else target
        currentPosition = safeTarget
        pager.setCurrentPage(safeTarget, false)

        applyClickFxOverlay()

        AppPermissionsBridge.request(this)

        primeBackAnimation()
    }

    /**
     * 冷启动后让窗口补一次 pause→resume。
     *
     * 系统的返回动画控制器要等窗口完成一次真正的生命周期往返才会接管它；
     * 在那之前注册的返回回调虽然收得到，但 progress 恒为 0、touchX 也不更新，
     * 表现为「能返回却没有跟手预演」——这正是「离开主活动再回来才生效」的原因。
     *
     * 这里拉起一个全透明、无转场的空 Activity 并让它立刻结束，
     * 借这一次往返把窗口预热好，用户看不到任何中间态。
     */
    private fun primeBackAnimation() {
        window.decorView.post {
            startActivity(Intent(this, BackGesturePrimerActivity::class.java))
            overridePendingTransition(0, 0)
        }
    }

    private fun applyClickFxOverlay() {
        val decor = window.decorView as? ViewGroup ?: return
        val existing = decor.findViewWithTag<View>(TAG_CLICK_FX) as? ClickFxOverlay
        if (existing != null) {
            decor.removeView(existing)
            WebViewMetricsCleaner.removeBackgroundListener(backgroundFxReleaser)
            fxOverlay = null
        }
        if (!SpUtils.getClickFxEnabled(this)) return
        val overlay = ClickFxOverlay(this).apply { tag = TAG_CLICK_FX }
        decor.addView(overlay)
        fxOverlay = overlay
        WebViewMetricsCleaner.addBackgroundListener(backgroundFxReleaser)
        if (ClickFxManager.isReady()) {
            overlay.load()
        } else {
            ClickFxManager.ensureDownloaded { ok ->
                if (ok) runOnUiThread { fxOverlay?.loadAgain() }
            }
        }
    }

    private val backgroundFxReleaser = AppBackgroundListener {
        fxOverlay?.releaseForBackground()
        fxOverlay = null
    }

    fun refreshClickFx() {
        applyClickFxOverlay()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        fxOverlay?.feed(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        started = true
        pages.getOrNull(currentPosition)?.let { page ->
            if (page.created) notifyPageShown(currentPosition)
        }
    }


    private fun notifyPageShown(position: Int) {
        val page = pages.getOrNull(position) ?: return
        if (!page.created) return
        page.onPageShow()
        updateNavHighlight()
        if (started) {
            page.onResume()
            pages.forEachIndexed { i, p ->
                if (i != position && p.created) p.onPause()
            }
        }
    }

    /**
     * 悬浮栏压在所有页面之上，把它的高度（含外边距）补成每个页面根布局的下内边距，
     * 页面内容就停在栏的上沿，四个页面都不用各自处理。
     */
    private fun applyPlayerBarInset() {
        val inset = if (floatingPlayerBar.visibility == View.VISIBLE) {
            val margin = (floatingPlayerBar.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.bottomMargin ?: 0
            floatingPlayerBar.height + margin
        } else {
            0
        }
        if (inset == playerBarInset) return
        playerBarInset = inset
        pages.forEach { page -> page.pageView?.let { applyPlayerBarInsetTo(it) } }
    }

    /**
     * 把悬浮栏占的高度让给页面里那个可滚动容器，而不是页面根。
     *
     * 打在页面根上的话，根的子 View 都是 match_parent，可用高度会被直接扣掉一截，
     * 内容就在悬浮栏上沿被「截断」。打在列表/ScrollView 上并关掉 clipToPadding，
     * 内容仍能滚到悬浮栏底下，只是最后一屏会留出这段空白，滚到底不会压住悬浮栏。
     */
    private fun applyPlayerBarInsetTo(pageRoot: View) {
        val scrollable = findScrollContainer(pageRoot) ?: pageRoot
        if (scrollable.paddingBottom == playerBarInset) return
        scrollable.setPadding(
            scrollable.paddingLeft,
            scrollable.paddingTop,
            scrollable.paddingRight,
            playerBarInset
        )
        // clipToPadding 是 ViewGroup 的属性（ScrollView / ListView 都有）
        if (scrollable !== pageRoot) (scrollable as? ViewGroup)?.clipToPadding = false
    }

    /** 深度优先找页面里第一个可滚动容器（列表或 ScrollView）。 */
    private fun findScrollContainer(root: View): View? {
        if (root is android.widget.AbsListView || root is android.widget.ScrollView) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            findScrollContainer(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 背后内容动过（翻页、返回预览、页面切换）时，让玻璃立刻重新捕获。 */
    fun invalidateGlassBackdrop() {
        (floatingPlayerBar as? LiquidGlassLayout)?.refreshBackdrop()
    }

    /** 设置页切换液态玻璃后，让悬浮栏立刻按新设置重绘。 */
    fun refreshFloatingPlayerBar() {
        (floatingPlayerBar as? LiquidGlassLayout)?.glassEnabled =
            SpUtils.getLiquidGlassEnabled(this)
    }

    /** 播放状态驱动的显隐；全屏视频期间另行压制，退出全屏自动恢复。 */
    fun setFloatingPlayerBarVisible(visible: Boolean) {
        playerBarWanted = visible
        updateFloatingPlayerBar()
    }

    fun setVideoFullscreen(active: Boolean) {
        videoFullscreen = active
        updateFloatingPlayerBar()
    }

    private fun updateFloatingPlayerBar() {
        val visible = playerBarWanted && !videoFullscreen
        if ((floatingPlayerBar.visibility == View.VISIBLE) != visible) {
            floatingPlayerBar.visibility = if (visible) View.VISIBLE else View.GONE
        }
        applyPlayerBarInset()
    }

    private fun handlePageChanged(position: Int) {
        val oldIndex = currentPosition
        if (oldIndex == position) return
        currentPosition = position

        pages.getOrNull(oldIndex)?.takeIf { it.created }?.onPageHide()
        notifyPageShown(position)

        if (position != PAGE_SEARCH && !isPageAvailable(PAGE_SEARCH)) {
            pager.requestClearTemporaryVisible(PAGE_SEARCH)
        }
    }

    fun updateNavHighlight() {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val nav = content.findViewWithTag<View>(BaseActivity.TAG_BOTTOM_NAV) as? ViewGroup
            ?: return
        val highlight = android.graphics.Color.parseColor("#00A0FF")
        for (i in 0 until nav.childCount) {
            val item = nav.getChildAt(i) as? ViewGroup ?: continue
            val highlighted = (item.tag as? Int) == currentPosition
            val color = if (highlighted) highlight else android.graphics.Color.WHITE
            (item.getTag(R.id.nav_icon_view) as? ImageView)?.setColorFilter(color)
            (item.getTag(R.id.nav_text_view) as? android.widget.TextView)?.setTextColor(color)
        }
    }


    override fun onResume() {
        super.onResume()
        WindowUtils.setFullScreen(this)
        pages.getOrNull(currentPosition)?.takeIf { it.created }?.onResume()
        applyClickFxOverlay()
        // 整个生命周期只真正注册一次：重复注册会重置系统的返回动画状态。
        // 「当前页要不要预演」放在回调内部判断，而不是靠反复注销/注册切换。
        PredictiveBack.register(
            this,
            PredictiveBack.TAG_PAGER,
            onInvoke = {
                // 登录覆盖层在最上层，先关它
                if (BiliLoginHelper.handleBackPressed()) return@register
                if (handleBackPressed()) return@register
                if (currentPosition == PAGE_SONGS) {
                    // 停在歌曲页：返回就是退出应用
                    finish()
                } else {
                    // 预演已经把内容滑到一半，返回被别的层消费了就滑回去
                    pager.pageScrollX(currentPosition)?.let { pager.animateScrollBackTo(it) }
                }
            },
            onProgress = { if (currentPosition != PAGE_SONGS) onBackProgressPreview(it) },
            onCancel = { if (currentPosition != PAGE_SONGS) onBackCancelPreview() }
        )
    }

    override fun onPause() {
        pages.getOrNull(currentPosition)?.takeIf { it.created }?.onPause()
        fxOverlay?.pause()
        super.onPause()
    }

    /**
     * 跟手预演：从当前页滑向歌曲页。
     *
     * 翻页控件里本来就并排着歌曲页，滑过去就露出了它；用缩放/淡出只会露出
     * 窗口底色（黑色），看不出「回到哪一页」。
     * 与 BaseActivity 注册的返回回调共用同一次注册，避免两个回调互相顶掉。
     */
    private fun onBackProgressPreview(progress: Float) {
        if (currentPosition == PAGE_SONGS) return
        val from = pager.pageScrollX(currentPosition) ?: return
        val to = pager.pageScrollX(PAGE_SONGS) ?: return
        invalidateGlassBackdrop()
        pager.previewScrollTo((from + (to - from) * progress).toInt())
    }

    private fun onBackCancelPreview() {
        if (currentPosition == PAGE_SONGS) return
        pager.pageScrollX(currentPosition)?.let { pager.animateScrollBackTo(it) }
    }

    override fun onDestroy() {
        PredictiveBack.unregister(this, PredictiveBack.TAG_PAGER)
        pages.forEach { if (it.created) it.onDestroy() }
        WebViewMetricsCleaner.removeBackgroundListener(backgroundFxReleaser)
        fxOverlay?.pause()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pages.forEach { if (it.created) it.onNewIntent(intent) }

        intent.getIntExtra(EXTRA_TARGET_PAGE, -1).takeIf { it in 0..pages.size - 1 }
            ?.let { setPage(it, false) }
        if (intent.getBooleanExtra(EXTRA_ADJUST_SEARCH_BUTTON, false)) {
            (pages.getOrNull(PAGE_SONGS) as? SongsPage)?.enterAdjustModeFromHost()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pages.forEach { if (it.created) it.onConfigurationChanged(newConfig) }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 登录覆盖层在最上层，返回键先关它
        if (BiliLoginHelper.handleBackPressed()) return
        if (handleBackPressed()) return
        super.onBackPressed()
    }

    /**
     * 三层返回逻辑：播放页浮层 → 页内返回 → 非主页回主页。
     *
     * 抽出来是为了让 [onBackPressed]（旧路径）和预测性返回回调（新路径）共用，
     * targetSdk >= 36 后系统只走后者。
     *
     * @return true 表示已消费，不需要再退出 Activity
     */
    private fun handleBackPressed(): Boolean {
        if ((pages.getOrNull(PAGE_SONGS) as? SongsPage)?.handleNowPlayingBack() == true) return true
        val page = pages.getOrNull(currentPosition)
        if (page?.created == true && page.onBackPressed()) return true
        if (currentPosition != PAGE_SONGS) {
            setPage(PAGE_SONGS, true)
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val page = pages.getOrNull(currentPosition)
        if (page?.created == true && page.onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pendingResultTarget?.let { target ->
            pendingResultTarget = null
            target.onActivityResult(requestCode, resultCode, data)
            return
        }
        pages.forEach { if (it.created) it.onActivityResult(requestCode, resultCode, data) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pages.forEach {
            if (it.created) it.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }


    private var pendingResultTarget: BasePage? = null

    override fun setPage(position: Int, smooth: Boolean) {
        val target = position.coerceIn(0, pages.size - 1)
        if (target == PAGE_SEARCH && !isPageAvailable(PAGE_SEARCH)) {
            pager.markTemporaryVisible(PAGE_SEARCH, takeFocus = false)
        }
        pager.setCurrentPage(target, smooth)
        if (target != currentPosition) handlePageChanged(target)
    }

    override fun startActivityForResult(page: BasePage, intent: Intent, requestCode: Int) {
        pendingResultTarget = page
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode)
    }

    fun refreshBottomNav() {
        pager.clearTemporaryVisible(PAGE_SEARCH)
        pager.clearPendingTemporaryClear()
        applySearchPageAvailability()
        pager.refreshPages()
        removeExistingBottomNav()
        setupBottomNav()
        updateNavHighlight()
    }

    private fun removeExistingBottomNav() {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing = content.findViewWithTag<View>(BaseActivity.TAG_BOTTOM_NAV) ?: return
        val navHeightPx = (BaseActivity.NAV_HEIGHT_DP * resources.displayMetrics.density).toInt()
        content.removeView(existing)
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            child.setPadding(
                child.paddingLeft,
                child.paddingTop,
                child.paddingRight,
                (child.paddingBottom - navHeightPx).coerceAtLeast(0)
            )
        }
    }

    private fun isPageAvailable(page: Int): Boolean =
        page != PAGE_SEARCH || SpUtils.getSearchMode(this)


    private fun applySearchPageAvailability() {
        pager.pageAvailability = { page -> page != PAGE_SEARCH || SpUtils.getSearchMode(this) }
    }
}
