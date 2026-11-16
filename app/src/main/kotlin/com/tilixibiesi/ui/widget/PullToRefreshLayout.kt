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

/**
 * 下拉刷新容器（项目无 AndroidX/Material 依赖，这里用原生控件实现）。
 *
 * 只包裹一个 [AbsListView] 子 View。只有当列表已经在顶部、且手指向下拖拽时，
 * 才由本容器接管手势并显示刷新指示；其余情况一律不拦截，
 * 保证列表本身的点击、长按、滚动行为完全不变。
 *
 * 松手时若超过触发距离就调用 [onRefresh] 进入刷新态，
 * 调用方在完成后调用 [finishRefresh] 收起。
 */
class PullToRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 触发刷新所需的拖拽距离。 */
    private val triggerDistance: Float = dp(64f)

    /** 指示区最大下拉距离。 */
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

    /** 下拉刷新回调。 */
    var onRefresh: (() -> Unit)? = null

    /** 是否允许下拉刷新；置 false 时本容器完全不介入手势。 */
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
            setTextColor(0xFFFFFFFF.toInt())
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
        // 指示区是 init 里先 addView 进来的（索引 0），会排在被 inflate 的列表之前绘制，
        // 也就是被列表盖住。这里把它提到最上层。
        header?.bringToFront()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    /** 列表是否已经处于最顶部。 */
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
                // 阻尼：越往下拉越沉
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
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun applyPullVisual() {
        val fraction = (currentPull / triggerDistance).coerceIn(0f, 1f)
        progress?.alpha = 0.3f + 0.7f * fraction
        label?.alpha = 0.3f + 0.7f * fraction
        label?.text = if (currentPull >= triggerDistance) "松开刷新" else "下拉刷新"
        // 让指示区跟随手指，从顶部逐渐露出
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

    /** 收起指示区；[success] 为 false 时短暂显示失败提示。 */
    fun finishRefresh(success: Boolean = true) {
        refreshing = false
        if (!success) {
            label?.text = "刷新失败"
            postDelayed({ collapse() }, 700L)
        } else {
            collapse()
        }
    }

    /** 收起下拉指示（不触发回调）。 */
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
