package com.tilixibiesi.ui

import com.tilixibiesi.R

import android.content.Intent
import android.view.MotionEvent
import android.content.res.Configuration
import android.os.Build
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
import com.tilixibiesi.util.BackgroundHelper
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
    private lateinit var contentLayer: FrameLayout

    lateinit var backgroundLayer: FrameLayout
        private set
    private lateinit var pages: Array<BasePage>
    private lateinit var floatingPlayerBar: View
    private lateinit var rootView: FrameLayout
    private var playerBarWanted = false
    private var videoFullscreen = false
    private var playerBarInset = 0

    val floatingPlayerBarInset: Int get() = playerBarInset
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
        rootView = root

        contentLayer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        backgroundLayer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
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
        contentLayer.addView(backgroundLayer)
        contentLayer.addView(pager)
        root.addView(contentLayer)
        floatingPlayerBar = layoutInflater.inflate(R.layout.layout_floating_player, root, false)
        (floatingPlayerBar as? LiquidGlassLayout)?.apply {
            backdropSource = contentLayer
            glassEnabled = SpUtils.getLiquidGlassEnabled(this@MainPagerActivity)
            pressEnabled = true
        }
        floatingPlayerBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPlayerBarInset()
        }
        root.addView(floatingPlayerBar)
        setContentView(root)
        WindowUtils.setFullScreen(this)
        applyBackground()

        val target = intent.getIntExtra(EXTRA_TARGET_PAGE, PAGE_SONGS).coerceIn(0, pages.size - 1)
        val safeTarget =
            if (target == PAGE_SEARCH && !SpUtils.getSearchMode(this)) PAGE_SONGS else target
        currentPosition = safeTarget
        pager.setCurrentPage(safeTarget, false)

        applyClickFxOverlay()

        AppPermissionsBridge.request(this)

        primeBackAnimation()
    }

    private fun primeBackAnimation() {
        if (Build.VERSION.SDK_INT < 34) return
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
        pages.forEach { page ->
            page.pageView?.let { applyPlayerBarInsetTo(it) }
            (page as? SongsPage)?.repositionSearchButton()
        }
    }

    private fun applyPlayerBarInsetTo(pageRoot: View) {
        val scrollable = findScrollContainer(pageRoot) ?: pageRoot
        if (scrollable.paddingBottom == playerBarInset) return
        scrollable.setPadding(
            scrollable.paddingLeft,
            scrollable.paddingTop,
            scrollable.paddingRight,
            playerBarInset
        )
        if (scrollable !== pageRoot) (scrollable as? ViewGroup)?.clipToPadding = false
    }

    private fun findScrollContainer(root: View): View? {
        if (root is android.widget.AbsListView || root is android.widget.ScrollView) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            findScrollContainer(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    fun invalidateGlassBackdrop() {
        (floatingPlayerBar as? LiquidGlassLayout)?.refreshBackdrop()
    }

    fun refreshFloatingPlayerBar() {
        (floatingPlayerBar as? LiquidGlassLayout)?.glassEnabled =
            SpUtils.getLiquidGlassEnabled(this)
    }

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


    private fun applyBackground() {
        BackgroundHelper.applyBackground(this, backgroundLayer, SpUtils.getBackgroundAlpha(this))
    }

    fun refreshBackground() {
        applyBackground()
        invalidateGlassBackdrop()
    }

    override fun onResume() {
        super.onResume()
        WindowUtils.setFullScreen(this)
        applyBackground()
        BackgroundHelper.setActive(backgroundLayer, true)
        pages.getOrNull(currentPosition)?.takeIf { it.created }?.onResume()
        applyClickFxOverlay()
        PredictiveBack.register(
            this,
            PredictiveBack.TAG_PAGER,
            onInvoke = {
                if (BiliLoginHelper.handleBackPressed()) return@register
                if (handleBackPressed()) return@register
                if (currentPosition == PAGE_SONGS) {
                    finish()
                } else {
                    pager.pageScrollX(currentPosition)?.let { pager.animateScrollBackTo(it) }
                }
            },
            onProgress = { if (currentPosition != PAGE_SONGS) onBackProgressPreview(it) },
            onCancel = { if (currentPosition != PAGE_SONGS) onBackCancelPreview() }
        )
    }

    override fun onPause() {
        pages.getOrNull(currentPosition)?.takeIf { it.created }?.onPause()
        BackgroundHelper.setActive(backgroundLayer, false)
        fxOverlay?.pause()
        super.onPause()
    }

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
        BackgroundHelper.release(backgroundLayer)
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
        rootView.forceLayout()
        contentLayer.forceLayout()
        pager.forceLayout()
        backgroundLayer.forceLayout()
        floatingPlayerBar.forceLayout()
        pages.forEach { page ->
            page.pageView?.let { view ->
                view.forceLayout()
                requestGlassReset(view)
            }
        }
        awaitPlayerBarLayout()
    }

    private fun awaitPlayerBarLayout() {
        floatingPlayerBar.addOnLayoutChangeListener(
            object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    v.removeOnLayoutChangeListener(this)
                    if (isFinishing || isDestroyed) return
                    applyPlayerBarInset()
                    invalidateGlassBackdrop()
                }
            }
        )
    }

    private fun requestGlassReset(root: View) {
        if (root is LiquidGlassLayout) root.invalidateBackdrop()
        if (root !is ViewGroup) return
        for (i in 0 until root.childCount) requestGlassReset(root.getChildAt(i))
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (BiliLoginHelper.handleBackPressed()) return
        if (handleBackPressed()) return
        super.onBackPressed()
    }

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
