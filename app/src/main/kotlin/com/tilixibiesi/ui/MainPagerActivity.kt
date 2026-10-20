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
    /** 背景层与翻页控件共用同一层：背景不随页面滑动，各页根布局保持透明。 */
    private lateinit var contentLayer: FrameLayout

    /**
     * 只承载全局背景的那一层，位于翻页控件之下。
     *
     * 设置页卡片做液态玻璃时拿它当折射源：卡片自己也在页面里，
     * 若以翻页控件为源，捕获背景时会把卡片本身画进去。
     */
    lateinit var backgroundLayer: FrameLayout
        private set
    private lateinit var pages: Array<BasePage>
    private lateinit var floatingPlayerBar: View
    private lateinit var rootView: FrameLayout
    private var playerBarWanted = false
    private var videoFullscreen = false
    private var playerBarInset = 0

    /**
     * 悬浮控制栏当前占用的高度（含外边距）；不可见时为 0。
     *
     * 页面里那些「浮在最上层、不参与页面内边距」的控件（比如搜索按钮）
     * 需要靠它把自己抬到栏的上沿之上，否则会被栏压住。
     */
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

        // 背景铺在这一层，翻页控件是它的子 View：翻页只滑页面，背景原地不动。
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
        // 悬浮播放控制栏与翻页控件平级：跟着窗口，不跟着页面滑动
        floatingPlayerBar = layoutInflater.inflate(R.layout.layout_floating_player, root, false)
        (floatingPlayerBar as? LiquidGlassLayout)?.apply {
            backdropSource = contentLayer
            glassEnabled = SpUtils.getLiquidGlassEnabled(this@MainPagerActivity)
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
        // 跟手进度只有 API 34+ 才有，低版本这次往返没有任何收益，不必多拉起一个 Activity
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
        pages.forEach { page ->
            page.pageView?.let { applyPlayerBarInsetTo(it) }
            // 浮在页面之上、不吃内边距的控件（搜索按钮）要跟着栏的高度重新定位
            (page as? SongsPage)?.repositionSearchButton()
        }
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


    /**
     * 全局背景只铺一层：放在翻页控件下面，翻页时页面滑过去，背景原地不动。
     * 各页面根布局因此都是透明的，不再各自贴一张首尾接不上的图。
     */
    private fun applyBackground() {
        BackgroundHelper.applyBackground(this, backgroundLayer, SpUtils.getBackgroundAlpha(this))
    }

    /** 设置页改了背景图或透明度后立刻重铺，并让液态玻璃重新捕获。 */
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
        BackgroundHelper.setActive(backgroundLayer, false)
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
        // 清单里声明了 configChanges=orientation|screenSize，转屏不重建 Activity，
        // 页面视图树是 onCreate 那一刻按竖屏宽度建好的，之后没人重新量过：
        // 设置页的卡片就停在旧宽度上，内容被拉伸、卡片互相重叠。
        //
        // 这里必须用 forceLayout() 而不是 requestLayout()。requestLayout() 的传递依赖
        // 父级认为「尺寸可能变了」，而整条父链的测量规格仍来自旧的窗口尺寸；两者在
        // 同一次遍历里又会被合并成一个请求，最终什么都没变。forceLayout() 直接置位
        // PFLAG_FORCE_LAYOUT，让这棵子树在下一趟遍历里无条件重新测量，不看父级脸色。
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
        // 让位内边距要等悬浮栏按新宽度量完才有正确高度：
        // post 有可能赶在布局完成之前，这里挂一次性回调等到真正量完。
        awaitPlayerBarLayout()
    }

    /**
     * 等悬浮栏下一次布局完成后再重算让位内边距，只跑一次。
     *
     * 转屏后立即读 height 拿到的是旧值，按它算出来的内边距会把页面内容压在栏下面。
     */
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

    /**
     * 页面里嵌着的玻璃（设置页卡片等）也要丢掉按旧尺寸缓存的背景位图和圆角路径，
     * 并请求重新布局：转屏后宽度变了，它们必须按新宽度重新摆放。
     */
    private fun requestGlassReset(root: View) {
        if (root is LiquidGlassLayout) root.invalidateBackdrop()
        if (root !is ViewGroup) return
        for (i in 0 until root.childCount) requestGlassReset(root.getChildAt(i))
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
