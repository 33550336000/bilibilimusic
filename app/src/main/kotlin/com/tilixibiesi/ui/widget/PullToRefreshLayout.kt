package com.tilixibiesi.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView

class PullToRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val triggerDistance: Float = dp(64f)

    private val maxPull: Float = dp(96f)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var listView: AbsListView? = null
    private var header: View? = null
    private var progress: ProgressBar? = null
    private var label: TextView? = null

    private var refreshing = false
    private var dragging = false
    private var downY = 0f
    private var currentPull = 0f

    var onRefresh: (() -> Unit)? = null

    var refreshEnabled = true

    init {
        val container = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(96f).toInt(), Gravity.TOP)
            visibility = GONE
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = LayoutParams(dp(24f).toInt(), dp(24f).toInt(), Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(20f).toInt()
            }
        }
        val text = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(46f).toInt()
            }
            setTextColor(0xFF1A1B21.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        container.addView(bar)
        container.addView(text)
        addView(container)
        header = container
        progress = bar
        label = text
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is AbsListView) {
                listView = child
                break
            }
        }

        header?.bringToFront()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun isAtTop(): Boolean {
        val lv = listView ?: return true
        if (lv.childCount == 0) return true
        if (lv.firstVisiblePosition != 0) return false
        val first = lv.getChildAt(0) ?: return true
        return first.top >= lv.paddingTop - 2
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!refreshEnabled || refreshing) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                if (!dragging && dy > touchSlop && isAtTop()) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!refreshEnabled || refreshing) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = (ev.y - downY).coerceAtLeast(0f)

                currentPull = (dy * 0.45f).coerceAtMost(maxPull)
                header?.visibility = View.VISIBLE
                header?.translationY = currentPull - (header?.height ?: 0)
                applyPullVisual()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (currentPull >= triggerDistance && !refreshing) {
                    startRefresh()
                } else {
                    collapse()

                    if (ev.actionMasked == MotionEvent.ACTION_UP) performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun applyPullVisual() {
        val fraction = (currentPull / triggerDistance).coerceIn(0f, 1f)
        progress?.alpha = 0.3f + 0.7f * fraction
        label?.alpha = 0.3f + 0.7f * fraction
        label?.text = if (currentPull >= triggerDistance) "松开刷新" else "下拉刷新"

        val h = header?.height ?: 0
        header?.translationY = currentPull - h
    }

    private fun startRefresh() {
        refreshing = true
        currentPull = triggerDistance
        header?.visibility = View.VISIBLE
        val h = header?.height ?: 0
        header?.translationY = (triggerDistance - h).coerceAtLeast(0f)
        progress?.alpha = 1f
        label?.alpha = 1f
        label?.text = "正在刷新…"
        onRefresh?.invoke()
    }

    fun finishRefresh(success: Boolean = true) {
        refreshing = false
        if (!success) {
            label?.text = "刷新失败"
            postDelayed({ collapse() }, 700L)
        } else {
            collapse()
        }
    }

    fun collapse() {
        refreshing = false
        currentPull = 0f
        header?.animate()?.cancel()
        header?.translationY = -(header?.height ?: 0).toFloat()
        header?.visibility = View.GONE
        progress?.alpha = 1f
        label?.alpha = 1f
    }
}
