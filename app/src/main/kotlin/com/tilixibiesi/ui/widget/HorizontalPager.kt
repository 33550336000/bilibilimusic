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

class HorizontalPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    interface Adapter {
        fun getCount(): Int
        fun instantiateView(position: Int): View
    }

    companion object {
        const val SCROLL_STATE_IDLE = 0
        const val SCROLL_STATE_DRAGGING = 1
        const val SCROLL_STATE_SETTLING = 2

        private const val DISTANCE_THRESHOLD_RATIO = 0.35f
        private const val EDGE_RESISTANCE = 0.45f
        private const val AXIS_DOMINANCE = 0.5f

        private const val MIN_FLING_VELOCITY_DP = 400f

        private const val SETTLE_DURATION = 200
        private const val SETTLE_DECELERATION = 1.5f
        private const val CLOSE_ENOUGH_DP = 2f
    }

    var onPageChangeListener: ((position: Int) -> Unit)? = null
    var onScrollStateChanged: ((state: Int) -> Unit)? = null

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

    var currentPage: Int = 0
        private set

    var pageAvailability: ((page: Int) -> Boolean)? = null
        set(value) {
            field = value
            rebuildVisibleOrder()
        }

    fun refreshPages() {
        rebuildVisibleOrder()
        if (visibleOrder.indexOf(currentPage) == -1) {
            val fallback = visibleOrder.minByOrNull { abs(it - currentPage) } ?: currentPage
            currentPage = fallback
            onPageChangeListener?.invoke(fallback)
        }
        for (i in 0 until views.size()) {
            val p = views.keyAt(i)
            views.valueAt(i).visibility = if (isEnabled(p)) VISIBLE else GONE
        }
        pendingPage = currentPage
        requestLayout()
        invalidate()
    }

    private var visibleOrder: MutableList<Int> = mutableListOf()

    private fun rebuildVisibleOrder() {
        visibleOrder = (0 until pageCount).filter { isEnabled(it) }.toMutableList()
        if (pageWidthPx > 0) requestLayout()
    }

    private fun slotOf(page: Int): Int = visibleOrder.indexOf(page)

    private val temporaryVisible = mutableSetOf<Int>()

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
            if (takeFocus) pendingPage = page
        }
        invalidate()
        return changed
    }

    fun requestClearTemporaryVisible(page: Int) {
        if (page !in temporaryVisible) return
        if (page == currentPage) {
            cancelPendingClear()
            return
        }
        if (scrollState == SCROLL_STATE_IDLE) {
            postClearTemporary(page)
        } else {
            pendingClearTemporary = page
        }
    }

    private fun postClearTemporary(page: Int) {
        cancelPendingClear()
        val r = Runnable {
            pendingClearRunnable = null
            if (page !in temporaryVisible) return@Runnable
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

    fun clearPendingTemporaryClear() = cancelPendingClear()

    private fun applyViewVisibility() {
        for (i in 0 until views.size()) {
            val p = views.keyAt(i)
            views.valueAt(i).visibility = if (isEnabled(p)) VISIBLE else GONE
        }
        ensurePages(currentPage)
    }

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

    private fun pageAtSlot(slot: Int): Int = visibleOrder.getOrElse(slot) { currentPage }


    private val scroller = OverScroller(context, DecelerateInterpolator(SETTLE_DECELERATION))

    private val touchSlop: Int
    private val minFlingVelocity: Int
    private val maxFlingVelocity: Int
    private val closeEnough: Int
    private var velocityTracker: VelocityTracker? = null

    private val views = SparseArray<View>()

    private var pageWidthPx = 0
    private var isDragging = false
    private var dragRejected = false
    private var lastMotionX = 0f
    private var lastMotionY = 0f
    private var downX = 0f
    private var downY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var scrollState = SCROLL_STATE_IDLE
    private var pendingPage: Int? = null
    private var insideLayout = false
    private var pendingClearTemporary: Int? = null
    private var pendingClearRunnable: Runnable? = null
    private val hitRect = Rect()

    init {
        val vc = ViewConfiguration.get(context)
        touchSlop = vc.scaledPagingTouchSlop.takeIf { it > 0 } ?: vc.scaledTouchSlop
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

    private fun ensureOrder() {
        if (pageCount <= 0) return
        if (visibleOrder.size != (0 until pageCount).count { isEnabled(it) }) {
            rebuildVisibleOrder()
        }
    }


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
            val slot = slotOf(indexOfChildInPage(child))
            if (slot < 0) continue
            val childLeft = paddingLeft + slot * width
            child.layout(
                childLeft, paddingTop,
                childLeft + width, paddingTop + contentHeight
            )
        }

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
        if (slotOf(currentPage) < 0) {
            currentPage = visibleOrder.minByOrNull { abs(it - currentPage) } ?: currentPage
            onPageChangeListener?.invoke(currentPage)
        }
        if (scrollState == SCROLL_STATE_IDLE) {
            scrollTo(slotOf(currentPage).coerceAtLeast(0) * width, scrollY)
        }
    }

    private fun indexOfChildInPage(child: View): Int {
        for (i in 0 until views.size()) {
            if (views.valueAt(i) === child) return views.keyAt(i)
        }
        return indexOfChild(child).coerceAtLeast(0)
    }


    override fun computeScroll() {
        if (!scroller.isFinished && scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
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
        if (w > 0) {
            val aligned = slotOf(currentPage).coerceAtLeast(0) * w
            if (aligned != scrollX) scrollTo(aligned, scrollY)
        }
        ensurePages(currentPage)
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
        if (state == SCROLL_STATE_IDLE) {
            pendingClearTemporary?.let { page ->
                pendingClearTemporary = null
                if (page != currentPage) postClearTemporary(page)
            }
        }
    }

    fun setCurrentPage(position: Int, smooth: Boolean = true) {
        ensureOrder()
        val target = position.coerceIn(0, lastIndex())
        if (target == currentPage && scrollState == SCROLL_STATE_IDLE) return

        if (!isEnabled(target)) return

        if (pageWidthPx <= 0) {
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
                        isDragging = true
                        setScrollState(SCROLL_STATE_DRAGGING)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        lastMotionX = downX + if (dx > 0) touchSlop else -touchSlop
                        return true
                    }
                    abs(dy) > touchSlop && abs(dy) > abs(dx) -> {
                        dragRejected = true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> onSecondaryPointerUp(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
                if (isDragging) setCurrentPage(currentPage, true)
                endDrag()
            }
        }
        return false
    }

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

    private fun computeSettleDuration(): Int = SETTLE_DURATION


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
