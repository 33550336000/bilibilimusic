package com.tilixibiesi.ui.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import android.widget.SeekBar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 零依赖横向分页容器（跟手拖拽 + 双阈值吸附）。
 *
 * 与原本「GestureDetector.onFling + overridePendingTransition」的本质区别：
 *   - 手指移动时页面 1:1 实时位移，可中途反向拖拽；
 *   - 松手后按「位移距离 + 甩动速度」判定吸附到目标页或回弹；
 *   - 剩余位移由 OverScroller 补完，而不是播放一段固定时长的入场/出场动画。
 *
 * 实现方式为 ViewGroup + scrollTo：子页按索引从左到右排列，通过移动视口实现翻页，
 * 与 ViewPager 内部机制一致，因此子 View 调用 requestDisallowInterceptTouchEvent
 * 时容器会自动让行，天然兼容嵌套滚动。
 *
 * 无任何第三方依赖，仅使用平台 API（minSdk 30）。
 */
class HorizontalPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /** 页面数据源：提供页数与「按需创建页面视图」的能力。 */
    interface Adapter {
        fun getCount(): Int
        /** 首次需要展示该页时调用；创建出的 View 由容器长期持有（保留滚动位置等状态）。 */
        fun instantiateView(position: Int): View
    }

    companion object {
        const val SCROLL_STATE_IDLE = 0
        const val SCROLL_STATE_DRAGGING = 1
        const val SCROLL_STATE_SETTLING = 2

        /** 慢速松手时的位移判定阈值（占页宽比例） */
        private const val DISTANCE_THRESHOLD_RATIO = 0.35f
        /** 边缘阻尼系数：越界拖拽时实际位移的折扣，制造橡皮筋手感 */
        private const val EDGE_RESISTANCE = 0.45f
        /** 横向意图需达到纵向的 2 倍才判定为翻页（避免列表竖向滚动误触） */
        private const val AXIS_DOMINANCE = 0.5f

        /**
         * 触发翻页的最小甩动速度（dp/s）。
         *
         * 刻意不用 ViewConfiguration.scaledMinimumFlingVelocity（仅约 50dp/s，
         * 几乎任何轻微滑动都会翻页，手感过于灵敏、容易误触）。
         * 与 androidx ViewPager 的 MIN_FLING_VELOCITY 一致取 400dp/s。
         */
        private const val MIN_FLING_VELOCITY_DP = 400f

        /**
         * 吸附动画时长（毫秒）——固定值。
         *
         * 用户需求：希望每次切换节奏完全一致、可预测，且不拖沓。
         * 因此取消按松手速度动态计算时长的方式（那种做法虽然理论上手感最优，
         * 但慢速松手时会明显偏慢），统一使用这一固定时长。
         * 想更快就调小这个值（建议 160~260 区间）。
         */
        private const val SETTLE_DURATION = 200
        /**
         * 减速收尾系数：配合下面的插值器使用。
         * 固定时长若配线性插值，终点速度不为 0，会「撞墙式」急停；
         * 用减速插值把末速平滑收敛到 0，既保持总时长恒定，又观感顺滑。
         */
        private const val SETTLE_DECELERATION = 1.5f
        /** 判定「吸附动画已基本到位」的距离，用于允许用户半途接住页面 */
        private const val CLOSE_ENOUGH_DP = 2f
    }

    /** 翻页监听：目标页确定时触发（动画开始即触发，与 ViewPager 一致） */
    var onPageChangeListener: ((position: Int) -> Unit)? = null
    var onScrollStateChanged: ((state: Int) -> Unit)? = null

    /**
     * 拖拽守卫：返回 true 表示「该落点禁止发起翻页拖拽」，让页面内控件优先吃掉手势。
     * 参数为屏幕坐标（对应 MotionEvent 的 rawX / rawY），便于与 getGlobalVisibleRect 比较。
     * 容器已自动识别 SeekBar 与可横向滚动的子 View，此回调供页面追加自定义区域。
     */
    var dragGuard: ((rawX: Float, rawY: Float) -> Boolean)? = null

    var adapter: Adapter? = null
        set(value) {
            field = value
            views.clear()
            removeAllViews()
            scroller.abortAnimation()
            temporaryVisible.clear()
            cancelPendingClear()
            rebuildVisibleOrder()
            currentPage = currentPage.coerceIn(0, lastIndex())
            requestLayout()
        }

    /** 当前页索引 */
    var currentPage: Int = 0
        private set

    /**
     * 页面可用性判定：返回 false 的页**完全不占位**（既不显示也不参与滑动）。
     *
     * 与「吸附时跳过」的区别：被排除的页会被设为 GONE 并从页序中移除，
     * 因此拖拽过程中根本看不到它，不会有「跨越两页」的观感；
     * 但页面实例仍保留（不销毁重建），列表滚动位置与播放状态不丢失。
     *
     * 用于「搜索按钮模式」下彻底隐藏搜索页。
     *
     * 赋值即生效：setter 会立即重建页序，因此无需调用方额外记着刷新。
     * （早期版本要求手动调用 refreshPages()，一旦遗漏就会出现
     *  「页仍占槽位但视图已 GONE」的不一致，滑动到该槽位时是纯黑页面。）
     */
    var pageAvailability: ((page: Int) -> Boolean)? = null
        set(value) {
            field = value
            rebuildVisibleOrder()
        }

    /**
     * 重新应用可用性规则并立即重排；当前页若被隐藏则落到最近的可用页。
     *
     * pageAvailability 的 setter 已会重建页序，本方法用于还需要
     * 「同步视图可见性 + 重新布局」的场合（如运行时切换搜索模式）。
     */
    fun refreshPages() {
        rebuildVisibleOrder()
        if (visibleOrder.indexOf(currentPage) == -1) {
            val fallback = visibleOrder.minByOrNull { abs(it - currentPage) } ?: currentPage
            currentPage = fallback
            onPageChangeListener?.invoke(fallback)
        }
        // 被排除的页彻底不显示
        for (i in 0 until views.size()) {
            val p = views.keyAt(i)
            views.valueAt(i).visibility = if (isEnabled(p)) VISIBLE else GONE
        }
        pendingPage = currentPage
        requestLayout()
        invalidate()
    }

    /** 当前可见（未被排除）的页索引，按显示顺序 */
    private var visibleOrder: MutableList<Int> = mutableListOf()

    private fun rebuildVisibleOrder() {
        visibleOrder = (0 until pageCount).filter { isEnabled(it) }.toMutableList()
        // 页序变化后必须立刻重排：仅仅改顺序而不 layout，
        // 会导致相邻页之间留出空洞（隐藏页仍占据一段滚动范围）。
        if (pageWidthPx > 0) requestLayout()
    }

    /** 页在「显示序」中的位置；不可见返回 -1 */
    private fun slotOf(page: Int): Int = visibleOrder.indexOf(page)

    /**
     * 临时可见页：即便被 pageAvailability 排除，也会进入页序并可跳转/滑动到达。
     *
     * 背景：可用性规则是「静态」的（例如按钮模式下搜索页整页隐藏），
     * 但总有一条临时的进入路径（点悬浮搜索按钮）。若不放行，
     * setCurrentPage(SEARCH) 会被 Availability 判为不可达而静默失败，
     * 表现为「点了搜索按钮没有任何反应」。
     *
     * 设计取舍：放行后该页在页序中恢复占位，用户仍可左右滑动到它；
     * 退出该页时由宿主调用 clearTemporaryVisible() 收回占位。
     * 这样既保证入口始终可用，又不必为「临时显示」单独维护一套两套页序。
     */
    private val temporaryVisible = mutableSetOf<Int>()

    /**
     * 把某页设为「本次临时可见」：重新回到页序、可跳转、可滑动到达，并重排一次。
     *
     * 幂等且安全：已在页序中时不会重复插入，只会把意图记录下来。
     *
     * @param takeFocus true 时以动画方式切到该页；false 仅让它重新占位（保留当前位置）
     * @return 是否真的改变了占位状态
     */
    fun markTemporaryVisible(page: Int, takeFocus: Boolean): Boolean {
        if (page !in 0 until pageCount) return false
        val changed = page !in temporaryVisible
        temporaryVisible.add(page)
        rebuildVisibleOrder()
        applyViewVisibility()
        if (pageWidthPx > 0) {
            requestLayout()
            if (takeFocus && isEnabled(page)) setCurrentPage(page, true)
        } else {
            // 尚未测量：记下意图，onLayout 时统一落实
            if (takeFocus) pendingPage = page
        }
        invalidate()
        return changed
    }

    /**
     * 请求收回某页的临时可见标记；若正处于吸附动画中，则等动画结束后再收回。
     *
     * 为什么必须延后：动画目标偏移是按「动画开始时的页序」算出来的，
     * 若中途移除一页，后续页槽位前移而动画仍滚向旧偏移，落位处没有页面 → 纯黑。
     */
    fun requestClearTemporaryVisible(page: Int) {
        if (page !in temporaryVisible) return
        // 该页正被显示（用户又滑回来了）：作废上一次待执行的收回请求
        if (page == currentPage) {
            cancelPendingClear()
            return
        }
        // 拖拽中与吸附中都延后：
        // 注意 onPageChangeListener 是在 setScrollState(SETTLING) 之前触发的，
        // 因此松手那一刻状态仍是 DRAGGING，必须一并延后，否则会立即移除而踩坑。
        if (scrollState == SCROLL_STATE_IDLE) {
            postClearTemporary(page)
        } else {
            pendingClearTemporary = page
        }
    }

    /**
     * 把「收回占位」排到下一个消息执行。
     *
     * 为什么不能直接同步执行：本方法最终由 settleFinished() → computeScroll() 调用，
     * 而 computeScroll 发生在**绘制阶段**。同步移除会让 scrollX 立刻跳到新槽位，
     * 但子页面要等下一次遍历才重新摆放 —— 中间那一帧视口正好对着已被 GONE 的页，
     * 于是「黑屏闪一下」。挪出绘制阶段后，「重排 + 滚动补偿」在同一遍历内完成，无中间帧。
     */
    private fun postClearTemporary(page: Int) {
        cancelPendingClear()
        val r = Runnable {
            pendingClearRunnable = null
            if (page !in temporaryVisible) return@Runnable
            // 执行时可能又处于拖拽中：交回 settleFinished 重新排队
            if (scrollState != SCROLL_STATE_IDLE) {
                pendingClearTemporary = page
                return@Runnable
            }
            if (page == currentPage) return@Runnable
            clearTemporaryVisible(page)
        }
        pendingClearRunnable = r
        post(r)
    }

    private fun cancelPendingClear() {
        pendingClearRunnable?.let { removeCallbacks(it) }
        pendingClearRunnable = null
        pendingClearTemporary = null
    }

    /** 丢弃尚未执行的「延后收回」（如切换模式时）：由调用方决定按新规则直接重排 */
    fun clearPendingTemporaryClear() = cancelPendingClear()

    /** 按当前可用性规则同步已有页面的可见性（不改当前页、不重排选页） */
    private fun applyViewVisibility() {
        for (i in 0 until views.size()) {
            val p = views.keyAt(i)
            views.valueAt(i).visibility = if (isEnabled(p)) VISIBLE else GONE
        }
        ensurePages(currentPage)
    }

    /**
     * 收回某页的临时可见标记（页面实例与状态仍保留，只是不再占显示位）。
     *
     * 关键：移除一页会让后续所有页的槽位前移一位，必须同步修正 scrollX，
     * 否则视口仍停在旧的偏移上，而那里已经没有页面 → 表现为「预览正常、落位后纯黑」。
     * 因此这里按「当前页的新槽位」重新对齐；若正处于吸附动画中则交给
     * settleFinished 处理（那一刻再对齐，避免打断动画）。
     */
    fun clearTemporaryVisible(page: Int): Boolean {
        if (page !in temporaryVisible) return false
        temporaryVisible.remove(page)
        rebuildVisibleOrder()
        applyViewVisibility()
        val w = pageWidthPx
        if (w > 0 && scrollState != SCROLL_STATE_SETTLING) {
            val slot = slotOf(currentPage).coerceAtLeast(0)
            val aligned = slot * w
            if (aligned != scrollX) {
                scroller.abortAnimation()
                scrollTo(aligned, scrollY)
            }
        }
        requestLayout()
        invalidate()
        return true
    }

    private fun visibleCount(): Int = visibleOrder.size

    /** 显示序 -> 页索引 */
    private fun pageAtSlot(slot: Int): Int = visibleOrder.getOrElse(slot) { currentPage }

    // ==================== 内部状态 ====================

    /**
     * 吸附动画：固定时长 + 减速插值。
     *
     * 用减速而非线性：固定时长下线性插值的终点速度不为 0，会「撞墙式」急停；
     * 减速插值让末速平滑收敛到 0，总时长仍恒定，观感干净。
     */
    private val scroller = OverScroller(context, DecelerateInterpolator(SETTLE_DECELERATION))

    private val touchSlop: Int
    private val minFlingVelocity: Int
    private val maxFlingVelocity: Int
    private val closeEnough: Int
    private var velocityTracker: VelocityTracker? = null

    /** 已实例化的页面视图（按页索引稀疏存放） */
    private val views = SparseArray<View>()

    private var pageWidthPx = 0
    private var isDragging = false
    /** 本次手势已被守卫/纵向判断拒绝：抬手前不再接管 */
    private var dragRejected = false
    private var lastMotionX = 0f
    private var lastMotionY = 0f
    private var downX = 0f
    private var downY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var scrollState = SCROLL_STATE_IDLE
    /** setCurrentPage 早于首次 layout 时暂存目标页 */
    private var pendingPage: Int? = null
    /** 是否处于 onLayout 中：此期间新增子页需就地测量摆放，否则首帧空白 */
    private var insideLayout = false
    /** 待收回的临时可见页：吸附动画结束后才真正移除（动画中移除会导致落位空白） */
    private var pendingClearTemporary: Int? = null
    /** 已排入消息队列的收回任务（用于取消，避免重复/过期执行） */
    private var pendingClearRunnable: Runnable? = null
    private val hitRect = Rect()

    init {
        val vc = ViewConfiguration.get(context)
        touchSlop = vc.scaledPagingTouchSlop.takeIf { it > 0 } ?: vc.scaledTouchSlop
        // 触发翻页的甩动阈值：不用系统 minimumFlingVelocity（约 50dp/s，过于灵敏），
        // 与 ViewPager 一致取 400dp/s，避免轻微滑动就翻页
        minFlingVelocity = (MIN_FLING_VELOCITY_DP * resources.displayMetrics.density).roundToInt()
        maxFlingVelocity = vc.scaledMaximumFlingVelocity
        closeEnough = (CLOSE_ENOUGH_DP * resources.displayMetrics.density).roundToInt()
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
    }

    private val pageCount: Int get() = adapter?.getCount() ?: 0
    private fun lastIndex() = (pageCount - 1).coerceAtLeast(0)

    private fun isEnabled(page: Int): Boolean =
        page in temporaryVisible || (pageAvailability?.invoke(page) ?: true)

    /**
     * 保证可见页序与当前 pageCount / 可用性规则一致。
     *
     * 比「仅在为空时构建」更稳：页数变化或可用性规则变更后，
     * 旧的页序会成为脏数据（例如隐藏某页后它仍占槽位，导致该槽位没有 View → 纯黑）。
     * 这里按「构建时的页数」做失效判断，任何顺序下都能自愈。
     */
    private fun ensureOrder() {
        if (pageCount <= 0) return
        if (visibleOrder.size != (0 until pageCount).count { isEnabled(it) }) {
            rebuildVisibleOrder()
        }
    }

    // ==================== 页面视图管理 ====================

    /**
     * 确保目标页及其在「显示序」上的左右相邻页已创建并挂载（惰性创建，长期保留）。
     *
     * 按显示序取相邻：隐藏模式下才能正确预建真正会看到的邻居；
     * 被隐藏的页不会在这里被创建（即便曾被创建过也保持 GONE）。
     */
    private fun ensurePages(position: Int) {
        if (position < 0 || position >= pageCount) return
        val width = pageWidthPx
        if (width <= 0) return
        ensureOrder()
        val slot = slotOf(position)
        if (slot < 0) return
        var added = false
        for (s in (slot - 1)..(slot + 1)) {
            if (s < 0 || s >= visibleCount()) continue
            val p = pageAtSlot(s)
            if (views.get(p) != null) continue
            val v = adapter?.instantiateView(p) ?: continue
            v.visibility = if (isEnabled(p)) VISIBLE else GONE
            views.put(p, v)
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            added = true
        }
        if (added && insideLayout) {
            // layout 过程中新增子页：就地测量摆放，避免等到下一帧才显示
            val wSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            val hSpec = MeasureSpec.makeMeasureSpec(
                height - paddingTop - paddingBottom, MeasureSpec.EXACTLY
            )
            val contentHeight = height - paddingTop - paddingBottom
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE || child.measuredWidth != 0) continue
                child.measure(wSpec, hSpec)
                val slotIdx = slotOf(indexOfChildInPage(child))
                if (slotIdx < 0) continue
                val left = paddingLeft + slotIdx * width
                child.layout(left, paddingTop, left + width, paddingTop + contentHeight)
            }
        }
    }

    // ==================== 测量与布局 ====================

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        )
        val childWidth = measuredWidth - paddingLeft - paddingRight
        if (childWidth <= 0) return
        pageWidthPx = childWidth

        val wSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec(
            measuredHeight - paddingTop - paddingBottom, MeasureSpec.EXACTLY
        )
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(wSpec, hSpec)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 避免已排队的收回任务在视图分离后仍执行
        pendingClearRunnable?.let { removeCallbacks(it) }
        pendingClearRunnable = null
        pendingClearTemporary = null
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        val contentHeight = b - t - paddingTop - paddingBottom
        if (width <= 0) return

        val widthChanged = width != pageWidthPx
        pageWidthPx = width

        insideLayout = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            // 按「显示序」排布：被排除的页不占slot，因此相邻可见页彼此紧贴
            val slot = slotOf(indexOfChildInPage(child))
            if (slot < 0) continue
            val childLeft = paddingLeft + slot * width
            child.layout(
                childLeft, paddingTop,
                childLeft + width, paddingTop + contentHeight
            )
        }

        // 首次布局或尺寸变化（旋转）后，保持停留在当前页对应的偏移
        if (widthChanged || changed) {
            ensureOrder()
            val target = pendingPage ?: currentPage
            pendingPage = null
            scroller.abortAnimation()
            scrollTo(slotOf(target).coerceAtLeast(0) * width, scrollY)
            if (target != currentPage) {
                currentPage = target
                onPageChangeListener?.invoke(target)
            }
        }

        ensurePages(currentPage)
        insideLayout = false

        ensureOrder()
        // 兜底：当前页已被隐藏（如刚切换为按钮模式），落到最近的可用页，
        // 否则会停留在一个没有任何 View 的槽位上，表现为纯黑页面
        if (slotOf(currentPage) < 0) {
            currentPage = visibleOrder.minByOrNull { abs(it - currentPage) } ?: currentPage
            onPageChangeListener?.invoke(currentPage)
        }
        if (scrollState == SCROLL_STATE_IDLE) {
            scrollTo(slotOf(currentPage).coerceAtLeast(0) * width, scrollY)
        }
    }

    /** 反查子 View 对应的页索引（以 SparseArray 记录为准，与 addView 顺序无关） */
    private fun indexOfChildInPage(child: View): Int {
        for (i in 0 until views.size()) {
            if (views.valueAt(i) === child) return views.keyAt(i)
        }
        return indexOfChild(child).coerceAtLeast(0)
    }

    // ==================== 滚动驱动 ====================

    override fun computeScroll() {
        if (!scroller.isFinished && scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            // 每一活动帧都必须续帧，否则吸附动画会停在半途
            if (scrollState == SCROLL_STATE_SETTLING) postInvalidateOnAnimation()
        } else if (scrollState == SCROLL_STATE_SETTLING) {
            settleFinished()
        }
    }

    private fun settleFinished() {
        scroller.abortAnimation()
        setScrollState(SCROLL_STATE_IDLE)
        ensureOrder()
        val w = pageWidthPx
        val landedSlot = if (w > 0) (scrollX.toFloat() / w).roundToInt() else 0
        val landed = pageAtSlot(landedSlot.coerceIn(0, (visibleCount() - 1).coerceAtLeast(0)))
        if (landed != currentPage) {
            currentPage = landed
            onPageChangeListener?.invoke(landed)
        }
        // 动画结束、页序可能已变化：按当前页的新槽位重新对齐，
        // 修正「槽位前移但视口未跟随」导致的落位空白
        if (w > 0) {
            val aligned = slotOf(currentPage).coerceAtLeast(0) * w
            if (aligned != scrollX) scrollTo(aligned, scrollY)
        }
        ensurePages(currentPage)
        // 此刻再收回临时占位：动画已结束，重新对齐不会打断观感。
        // 守卫：若用户又滑回了该页（它正被显示），本次收回作废，避免把正在看的页抽走。
        val pendingPage = pendingClearTemporary
        if (pendingPage != null) {
            pendingClearTemporary = null
            if (pendingPage != currentPage) postClearTemporary(pendingPage)
        }
    }

    private fun setScrollState(state: Int) {
        if (scrollState == state) return
        scrollState = state
        onScrollStateChanged?.invoke(state)
        // 兜底：吸附动画并非总经 computeScroll 结束（例如被 prepareDrag 直接判定为到位），
        // 这里在回到 IDLE 时补一次排队的收回，避免临时页一直占位。
        if (state == SCROLL_STATE_IDLE) {
            pendingClearTemporary?.let { page ->
                pendingClearTemporary = null
                if (page != currentPage) postClearTemporary(page)
            }
        }
    }

    /**
     * 切换到指定页。
     * @param position 目标页索引
     * @param smooth true 播放吸附动画；false 立即跳转
     */
    fun setCurrentPage(position: Int, smooth: Boolean = true) {
        ensureOrder()
        val target = position.coerceIn(0, lastIndex())
        if (target == currentPage && scrollState == SCROLL_STATE_IDLE) return

        // 目标页被隐藏：不可跳转，保持原位
        if (!isEnabled(target)) return

        if (pageWidthPx <= 0) {
            // 尚未完成布局：记录意图，onLayout 时落实
            pendingPage = target
            currentPage = target
            onPageChangeListener?.invoke(target)
            return
        }
        ensurePages(target)
        currentPage = target
        onPageChangeListener?.invoke(target)

        val dest = slotOf(target).coerceAtLeast(0) * pageWidthPx
        if (!smooth || dest == scrollX) {
            scroller.abortAnimation()
            scrollTo(dest, scrollY)
            setScrollState(SCROLL_STATE_IDLE)
            return
        }
        scroller.abortAnimation()
        setScrollState(SCROLL_STATE_SETTLING)
        scroller.startScroll(scrollX, scrollY, dest - scrollX, 0, SETTLE_DURATION)
        postInvalidateOnAnimation()
    }

    // ==================== 触摸处理 ====================

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (pageCount <= 1) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                prepareDrag(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragRejected) return false
                if (isDragging) return true
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                val dx = ev.x - lastMotionX
                val dy = ev.y - lastMotionY
                when {
                    abs(dx) > touchSlop && abs(dx) * AXIS_DOMINANCE > abs(dy) -> {
                        if (isDragBlocked(ev.rawX, ev.rawY, ev.x, ev.y, dx)) {
                            dragRejected = true
                            return false
                        }
                        // 判定为横向翻页：接管本次手势，子 View 会自动收到 ACTION_CANCEL
                        isDragging = true
                        setScrollState(SCROLL_STATE_DRAGGING)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        lastMotionX = downX + if (dx > 0) touchSlop else -touchSlop
                        return true
                    }
                    abs(dy) > touchSlop && abs(dy) > abs(dx) -> {
                        // 明确是纵向滑动（列表滚动），本次手势不再接管
                        dragRejected = true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> onSecondaryPointerUp(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 已被接管的手势不会再走到这里（事件直接进 onTouchEvent）；
                // 未接管时必须返回 false，否则子 View 收不到抬起事件。
                endDrag()
                return false
            }
        }
        return isDragging
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (pageCount <= 1) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                prepareDrag(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragRejected) return false
                if (!isDragging) {
                    val dx = ev.x - lastMotionX
                    val dy = ev.y - lastMotionY
                    when {
                        abs(dx) > touchSlop && abs(dx) * AXIS_DOMINANCE > abs(dy) -> {
                            if (isDragBlocked(ev.rawX, ev.rawY, ev.x, ev.y, dx)) {
                                dragRejected = true
                                return false
                            }
                            isDragging = true
                            setScrollState(SCROLL_STATE_DRAGGING)
                            parent?.requestDisallowInterceptTouchEvent(true)
                            lastMotionX = downX + if (dx > 0) touchSlop else -touchSlop
                        }
                        abs(dy) > touchSlop && abs(dy) > abs(dx) -> {
                            dragRejected = true
                            return false
                        }
                    }
                }
                if (isDragging) {
                    // 反向移动视口：手指右移 → 视口左移 → 看到左侧页面
                    val raw = scrollX - (ev.x - lastMotionX)
                    lastMotionX = ev.x
                    lastMotionY = ev.y
                    scrollTo(applyEdgeResistance(raw).roundToInt(), scrollY)
                    velocityTracker?.addMovement(ev)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> onSecondaryPointerUp(ev)
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    velocityTracker?.addMovement(ev)
                    velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val vx = currentVelocityX()
                    settleToTargetPage(vx)
                    endDrag()
                    return true
                }
                endDrag()
            }
            MotionEvent.ACTION_CANCEL -> {
                // 被系统/父容器夺走手势：不带初速地回到当前页
                if (isDragging) setCurrentPage(currentPage, true)
                endDrag()
            }
        }
        return false
    }

    /**
     * 新按下：重置手势状态。
     * 注意不无条件 abortAnimation：若吸附动画已基本到位（差值小于 closeEnough），
     * 直接结束它，让用户能顺滑地「接住」正在回位的页面而不产生跳动。
     */
    private fun prepareDrag(ev: MotionEvent) {
        if (scrollState == SCROLL_STATE_SETTLING) {
            scroller.computeScrollOffset()
            if (abs(scroller.finalX - scroller.currX) > closeEnough) {
                scroller.abortAnimation()
            } else {
                settleFinished()
            }
        } else {
            scroller.abortAnimation()
        }
        isDragging = false
        dragRejected = false
        downX = ev.x
        downY = ev.y
        lastMotionX = ev.x
        lastMotionY = ev.y
        activePointerId = ev.getPointerId(0)
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun currentVelocityX(): Float {
        val vt = velocityTracker ?: return 0f
        return if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
            vt.getXVelocity(activePointerId)
        } else {
            @Suppress("DEPRECATION")
            vt.xVelocity
        }
    }

    private fun onSecondaryPointerUp(ev: MotionEvent) {
        val idx = ev.actionIndex
        if (ev.getPointerId(idx) == activePointerId) {
            val newIdx = if (idx == 0) 1 else 0
            if (newIdx < ev.pointerCount) {
                activePointerId = ev.getPointerId(newIdx)
                lastMotionX = ev.getX(newIdx)
                lastMotionY = ev.getY(newIdx)
            } else {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
    }

    private fun endDrag() {
        isDragging = false
        dragRejected = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        velocityTracker?.recycle()
        velocityTracker = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** 越界时施加阻尼，制造橡皮筋效果（按可见页数的实际滚动范围计算） */
    private fun applyEdgeResistance(target: Float): Float {
        val w = pageWidthPx
        if (w <= 0) return target
        val min = 0f
        val max = ((visibleCount() - 1).coerceAtLeast(0) * w).toFloat()
        return when {
            target < min -> min - (min - target) * EDGE_RESISTANCE
            target > max -> max + (target - max) * EDGE_RESISTANCE
            else -> target
        }
    }

    /**
     * 松手后按「位移 + 速度」双阈值决定目标页。
     * 由于被隐藏的页不占显示位，这里直接在「显示序」上 ±1，
     * 因此歌曲页左滑会直接落到设置页，中间不存在空转的一页。
     *
     * @param vx 水平速度（px/s）；负值表示手指向左，即去往下一页
     */
    private fun settleToTargetPage(vx: Float) {
        val w = pageWidthPx
        if (w <= 0) return
        ensureOrder()

        val slot = slotOf(currentPage)
        val offset = (scrollX - slot * w).toFloat() / w
        var targetSlot = slot
        if (abs(vx) > minFlingVelocity) {
            targetSlot = slot + if (vx < 0) 1 else -1
        } else if (abs(offset) > DISTANCE_THRESHOLD_RATIO) {
            targetSlot = slot + if (offset > 0) 1 else -1
        }
        targetSlot = targetSlot.coerceIn(0, (visibleCount() - 1).coerceAtLeast(0))
        val target = pageAtSlot(targetSlot)

        if (target != currentPage) {
            currentPage = target
            onPageChangeListener?.invoke(target)
        }
        ensurePages(target)

        val dest = targetSlot * w
        val remaining = abs(dest - scrollX)
        val duration = computeSettleDuration()

        setScrollState(SCROLL_STATE_SETTLING)
        scroller.abortAnimation()
        scroller.startScroll(scrollX, scrollY, dest - scrollX, 0, duration)
        postInvalidateOnAnimation()
    }

    /**
     * 吸附动画时长：固定值。
     *
     * 曾用「按松手速度动态计算」以追求理论最优手感，但慢速松手时
     * 时长被拉长，用户反馈「有时候会比较慢」。改为固定时长后，
     * 每次切换节奏完全一致、可预测。调整的入口就是 SETTLE_DURATION 常量。
     */
    private fun computeSettleDuration(): Int = SETTLE_DURATION

    // ==================== 拖拽落点守卫 ====================

    /**
     * 落点在以下情况时不发起翻页拖拽：
     * 1) 页面自定义守卫判定禁止；
     * 2) 命中 SeekBar（页面内滑块优先）；
     * 3) 命中可横向滚动的子 View（如 WebView、横向滚动列表）。
     *
     * @param dx 本次位移，用于判断内容滚动方向（canScrollHorizontally 取内容方向）
     */
    private fun isDragBlocked(rawX: Float, rawY: Float, localX: Float, localY: Float, dx: Float): Boolean {
        if (dragGuard?.invoke(rawX, rawY) == true) return true
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != VISIBLE) continue
            val x = (localX - child.left).toInt()
            val y = (localY - child.top).toInt()
            if (x < 0 || y < 0 || x > child.width || y > child.height) continue
            if (hitSeekBar(child, rawX.toInt(), rawY.toInt())) return true
            if (canScrollHorizontally(child, -dx, x, y)) return true
        }
        return false
    }

    private fun hitSeekBar(view: View, x: Int, y: Int): Boolean {
        if (view is SeekBar) {
            return view.getGlobalVisibleRect(hitRect) && hitRect.contains(x, y)
        }
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child.visibility != VISIBLE) continue
                if (hitSeekBar(child, x, y)) return true
            }
        }
        return false
    }

    /** 递归查找该位置是否存在可横向滚动的子 View（内容滚动方向与手指相反，故传 -dx） */
    private fun canScrollHorizontally(v: View, dx: Float, x: Int, y: Int): Boolean {
        if (v is ViewGroup) {
            val scrollX = v.scrollX
            val scrollY = v.scrollY
            for (i in v.childCount - 1 downTo 0) {
                val child = v.getChildAt(i)
                if (x + scrollX < child.left || x + scrollX >= child.right) continue
                if (y + scrollY < child.top || y + scrollY >= child.bottom) continue
                if (canScrollHorizontally(
                        child, dx,
                        x + scrollX - child.left,
                        y + scrollY - child.top
                    )
                ) return true
            }
        }
        return v.canScrollHorizontally(dx.toInt())
    }
}
