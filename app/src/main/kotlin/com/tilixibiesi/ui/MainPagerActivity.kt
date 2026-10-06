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
import com.tilixibiesi.util.WindowUtils

/**
 * 主容器 Activity：同一个 Window 内承载 4 个主页面，由 [HorizontalPager] 实现跟手翻页。
 *
 * 改造前：歌曲 / 搜索 / 播放列表 / 设置 是 4 个独立 Activity，
 * 靠 GestureDetector.onFling + overridePendingTransition 切换——手指抬起后才判定，
 * 只能播放固定时长动画，做不到「页面跟着手指实时移动」。
 *
 * 改造后：4 个页面是本 Activity 内的 4 个子 View，由 HorizontalPager 横向排列；
 * 触摸事件在容器内处理，页面随手指 1:1 位移，松手后按「位移 + 速度」双阈值吸附。
 */
class MainPagerActivity : BaseActivity(), PageHost {

    companion object {
        /** 页面索引（与底部导航栏顺序一致） */
        const val PAGE_PLAYLIST = 0
        const val PAGE_SONGS = 1
        const val PAGE_SEARCH = 2
        const val PAGE_SETTINGS = 3

        /** 通知栏点击返回时指定的目标页 */
        const val EXTRA_TARGET_PAGE = "target_page"
        /** 进入后直接开启悬浮按钮调整模式 */
        const val EXTRA_ADJUST_SEARCH_BUTTON = "adjust_search_button"
        /** 点击特效层的 tag，用于查找/移除 */
        const val TAG_CLICK_FX = "click_fx_overlay"
    }

    private lateinit var pager: HorizontalPager
    private lateinit var pages: Array<BasePage>
    /** 点击特效层（WebView 渲染），未开启或已退到后台时为 null */
    private var fxOverlay: ClickFxOverlay? = null

    /** 当前展示中的页索引 */
    private var currentPosition = PAGE_SONGS
    /** 是否已完成首次布局（避免布局前的回调误触生命周期） */
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 页面实例必须先于 pager 创建：pager 首次 layout 时就会 instantiateView
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

        // 全屏视频播放期间禁止发起翻页拖拽：
        // 播放器的拖动快进是横向手势，若不拦截，会被容器判成翻页，
        // 于是「拖进度条变成切页、视频界面看起来点不动」。
        pager = HorizontalPager(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            adapter = object : HorizontalPager.Adapter {
                override fun getCount(): Int = pages.size
                override fun instantiateView(position: Int): View {
                    val page = pages[position]
                    // performCreate 内部执行 onCreate（inflate 布局）
                    return page.performCreate(this@MainPagerActivity, null).also {
                        if (position == currentPosition) notifyPageShown(position)
                    }
                }
            }
            onPageChangeListener = { pos -> handlePageChanged(pos) }
            // 全屏视频播放期间禁止发起翻页拖拽。
            // 播放器的「拖动快进」是横向手势，与翻页判定完全同向；
            // 若不在此守卫，一次快进拖动会把整页滑走，看起来就是「视频界面点不动」。
            // 播放器自身已在容器上装了 OnTouchListener，事件按正常视图树分发即可，
            // 容器只要不抢，手势就能到达播放器（此处不再额外转发，避免同一次事件被处理两次）。
            dragGuard = { _, _ ->
                (pages.getOrNull(PAGE_SEARCH) as? SearchPage)?.isVideoFullscreenActive == true
            }
        }
        // 搜索按钮模式（search_mode=false）下跳过搜索页，使歌曲页左滑直达设置页
        applySearchPageAvailability()
        root.addView(pager)
        setContentView(root)
        // 必须在 setContentView 之后：setFullScreen 需要已创建的 DecorView
        WindowUtils.setFullScreen(this)

        val target = intent.getIntExtra(EXTRA_TARGET_PAGE, PAGE_SONGS).coerceIn(0, pages.size - 1)
        // 目标页在按钮模式下不可用时（例如通知栏指定了搜索页），退回歌曲页，
        // 否则 currentPosition 会与实际停留的页错位
        val safeTarget =
            if (target == PAGE_SEARCH && !SpUtils.getSearchMode(this)) PAGE_SONGS else target
        currentPosition = safeTarget
        // 布局尚未完成：此处只记录目标页，真正的生命周期回调在 layout 后触发
        pager.setCurrentPage(safeTarget, false)

        // 点击特效层：加在 DecorView 顶层，全局覆盖 4 个页面（含全屏视频）
        applyClickFxOverlay()

        AppPermissionsBridge.request(this)
    }

    /**
     * 点击特效层的装载/卸载。
     *
     * 放在 DecorView 顶层而非 content 内：这样它天然位于所有页面之上，
     * 且不受各页面布局、分页容器滚动的影响。
     */
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
        // 应用整体退到后台时销毁这个 WebView（省内存 + 让后台清理追得上，详见
        // ClickFxOverlay.releaseForBackground）。回到前台由 onResume 重建。
        WebViewMetricsCleaner.addBackgroundListener(backgroundFxReleaser)
        // 本地文件就绪才加载；否则先静默下载，下载完成后自动加载并应用
        if (ClickFxManager.isReady()) {
            overlay.load()
        } else {
            ClickFxManager.ensureDownloaded { ok ->
                if (ok) runOnUiThread { fxOverlay?.loadAgain() }
            }
        }
    }

    /**
     * 后台销毁特效层。
     *
     * 用字段持有同一个实例：Application 里注册的回调不随 Activity 销毁注销，
     * 若每次 applyClickFxOverlay 都新建 lambda，会不断累积泄漏的 Activity 引用。
     */
    private val backgroundFxReleaser = AppBackgroundListener {
        fxOverlay?.releaseForBackground()
        fxOverlay = null
    }

    /** 供设置页切换开关后立即生效 */
    fun refreshClickFx() {
        applyClickFxOverlay()
    }

    /**
     * 旁路采集触摸给特效层。
     *
     * 必须在 super 之前调用且**不影响返回值**：特效是纯装饰，
     * 原有分发链路（分页拖拽、SeekBar、全屏视频手势）一律照旧。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        fxOverlay?.feed(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        started = true
        // 首次布局后，补触发当前页的展示回调
        pages.getOrNull(currentPosition)?.let { page ->
            if (page.created) notifyPageShown(currentPosition)
        }
    }

    // ==================== 页面生命周期 ====================

    private fun notifyPageShown(position: Int) {
        val page = pages.getOrNull(position) ?: return
        if (!page.created) return
        page.onPageShow()
        updateNavHighlight()
        if (started) {
            page.onResume()
            // 同一 Activity 内只有当前页处于前台
            pages.forEachIndexed { i, p ->
                if (i != position && p.created) p.onPause()
            }
        }
    }

    private fun handlePageChanged(position: Int) {
        val oldIndex = currentPosition
        if (oldIndex == position) return
        currentPosition = position

        pages.getOrNull(oldIndex)?.takeIf { it.created }?.onPageHide()
        notifyPageShown(position)

        // 离开搜索页且它本不该占位（按钮模式）：收回临时可见标记，
        // 让它重新从页序中移除，歌曲页左滑再次直达设置页。
        // 用 request 版：翻页动画进行中时等落位后再收回，
        // 否则槽位中途前移会让动画滚到没有页面的空白区（表现为落位后纯黑）。
        if (position != PAGE_SEARCH && !isPageAvailable(PAGE_SEARCH)) {
            pager.requestClearTemporaryVisible(PAGE_SEARCH)
        }
    }

    /**
     * 底部导航栏高亮：把当前页对应的导航项（图标 + 文字）设为蓝色，其余白色。
     * 由 BaseActivity.setupBottomNav 以 MainPagerActivity 身份添加，tag 存了页索引。
     */
    fun updateNavHighlight() {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val nav = content.findViewWithTag<View>(BaseActivity.TAG_BOTTOM_NAV) as? ViewGroup
            ?: return
        val highlight = android.graphics.Color.parseColor("#00A0FF")
        for (i in 0 until nav.childCount) {
            val item = nav.getChildAt(i) as? ViewGroup ?: continue
            val highlighted = (item.tag as? Int) == currentPosition
            val color = if (highlighted) highlight else android.graphics.Color.WHITE
            // 图标着色 + 文字换色，两者保持一致的高亮语义
            (item.getTag(R.id.nav_icon_view) as? ImageView)?.setColorFilter(color)
            (item.getTag(R.id.nav_text_view) as? android.widget.TextView)?.setTextColor(color)
        }
    }

    // ==================== Activity 生命周期转发 ====================

    override fun onResume() {
        super.onResume()
        WindowUtils.setFullScreen(this)
        pages.getOrNull(currentPosition)?.takeIf { it.created }?.onResume()
        // 回到前台：特效层必须显式恢复。
        //
        // 退到后台时它已被销毁（releaseForBackground：停渲染 + 让后台清理追得上），
        // 所以这里一律重建，而不是区分「软恢复 / 硬重置」：
        // 松手即来的快速切换重建一个 WebView 的代价远小于留一个渲染进程常驻。
        applyClickFxOverlay()
    }

    override fun onPause() {
        pages.getOrNull(currentPosition)?.takeIf { it.created }?.onPause()
        // 特效层在后台必须停摆：否则 rAF 持续渲染白耗 GPU。
        // 整个应用退到后台时会更彻底——由 backgroundFxReleaser 直接销毁它。
        fxOverlay?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        pages.forEach { if (it.created) it.onDestroy() }
        // 注销后台回调，避免 Application 持有已销毁 Activity 的引用
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

    override fun onBackPressed() {
        // 「正在播放」覆盖层优先：它盖在所有页面之上，返回键必须先收它，
        // 否则一次返回会越过它直接把主页面切走/退出应用。
        if ((pages.getOrNull(PAGE_SONGS) as? SongsPage)?.handleNowPlayingBack() == true) return
        val page = pages.getOrNull(currentPosition)
        if (page?.created == true && page.onBackPressed()) return
        // 非歌曲页：先回到歌曲页；已在歌曲页才真正退出
        if (currentPosition != PAGE_SONGS) {
            setPage(PAGE_SONGS, true)
            return
        }
        super.onBackPressed()
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

    // ==================== PageHost 实现 ====================

    /** 记录哪个页面发起了 startActivityForResult，结果回到该页 */
    private var pendingResultTarget: BasePage? = null

    override fun setPage(position: Int, smooth: Boolean) {
        val target = position.coerceIn(0, pages.size - 1)
        // 按钮模式下搜索页不可用（不占显示位），但「悬浮搜索按钮」必须能进去：
        // 这里临时放行，让该页重新回到页序中，否则跳转会被可用性规则吞掉。
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

    /** 当前生效语言，供页面查询 */
    /** 底部导航栏是否启用（页面据此预留底部空间） */
    /**
     * 让底部导航栏的增删立即生效。
     *
     * BaseActivity 把导航栏挂在 onStart 上，因此改动开关后默认要等一次重建才可见；
     * 这里在开关切换的当下直接重跑一次挂载/移除逻辑，无需切语言或重启。
     */
    fun refreshBottomNav() {
        // 重新应用可见性规则并让容器重排（当前页若被隐藏会自动落到可用页）。
        // 先收回搜索页的临时可见标记：切回页面模式后由可用性规则接管即可，
        // 否则该标记会一直留着，导致按钮模式下隐藏搜索页的开关失效。
        pager.clearTemporaryVisible(PAGE_SEARCH)
        pager.clearPendingTemporaryClear()
        applySearchPageAvailability()
        pager.refreshPages()
        // 导航栏可能因搜索模式变化而增减按钮：先移除旧的，再按最新状态重建。
        // BaseActivity.setupBottomNav 对已存在的导航栏会直接 return，
        // 因此必须先彻底移除，否则切回页面模式后「搜索」项不会重新出现。
        removeExistingBottomNav()
        setupBottomNav()
        updateNavHighlight()
    }

    /** 移除已存在的底部导航栏，并还原内容区的底部内边距 */
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

    // ==================== 搜索按钮模式：跳过搜索页 ====================

    /**
     * 搜索按钮模式（search_mode = false）下搜索页对用户不可达，
     * 因此让容器彻底不显示它：歌曲页左滑直达设置页，中间不存在空转的一页。
     *
     * 采用「页面实例保留 + 视图 GONE + 不占显示位」的方式：
     * 既让被隐藏的页完全不可见，又不会销毁重建页面（列表滚动位置、播放状态都保留）。
     */
    private fun applySearchPageAvailability() {
        pager.pageAvailability = { page -> page != PAGE_SEARCH || SpUtils.getSearchMode(this) }
    }
}
