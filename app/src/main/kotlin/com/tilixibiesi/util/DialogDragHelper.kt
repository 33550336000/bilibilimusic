package com.tilixibiesi.util

import android.annotation.SuppressLint
import android.app.Dialog
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * 给弹窗挂「窗口式顶部拖拽」。
 *
 * 需求语义（刻意做成这样，不是缺功能）：
 *  - 只在上方一小条区域按住才能拖，不是整块弹窗；
 *  - 可以整块拖出屏幕，不做任何坐标夹取；
 *  - 位置不持久化，每次 show() 都从系统默认位置开始。
 *
 * 热区按优先级二选一，互斥：
 *  1. 有标题栏时用系统标题模板（title_template），也就是弹窗最上面那一条；
 *  2. 没有标题栏时，在窗口顶部叠一条 12dp 高的透明细热区——但仅当内容顶部
 *     没有 EditText 时才挂，否则会抢走输入框的点击与键盘弹出。
 *
 * 拖动本身只是改窗口 LayoutParams 的 x/y 再 updateViewLayout，等价于桌面上
 * 拖一个普通窗口；因为弹窗宽度已被 DialogHelper 固定，拖动不会引起内容重排。
 */
object DialogDragHelper {

    private const val TAG = "DialogDragHelper"

    /** 无标题弹窗的细热区高度。再小手指按不准，再大就会盖住内容。 */
    private const val EDGE_STRIP_HEIGHT_DP = 12

    /**
     * 给一个已经 show() 的弹窗挂上拖拽。
     *
     * show() 内部已走完 installContent，标题模板的显隐此刻已经确定，
     * 所以标题那条可以同步判断。
     */
    fun attach(dialog: Dialog?) {
        val window = dialog?.window ?: return
        val decor = window.decorView as? ViewGroup ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        val titlePanel = findTitlePanel(window)
        if (titlePanel != null) {
            attachDrag(window, titlePanel)
        } else {
            // 内容顶部的输入框判定要读 getTop()，而 show() 返回时首帧布局
            // 通常还没跑（此时所有 top 都是 0，会把任意一处 EditText 误判成
            // 压在顶部）。post 出去等这一帧完成再判断。
            decor.post { attachEdgeStrip(window, decor) }
        }
    }

    /** 系统标题模板（含 alertTitle 的那条），不存在或不可见时返回 null。 */
    @SuppressLint("DiscouragedApi")
    private fun findTitlePanel(window: Window): View? {
        val res = window.context.resources
        val templateId = res.getIdentifier("title_template", "id", "android")
        if (templateId != 0) {
            val template = window.findViewById<View>(templateId)
            if (template != null && template.isShown) return template
        }
        // 退路：拿到标题文字本身，用它的父容器当热区。
        val titleId = res.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) {
            val title = window.findViewById<View>(titleId)
            val parent = title?.parent as? View
            if (parent != null && parent.isShown) return parent
        }
        return null
    }

    /** 无标题弹窗：顶部叠一条透明细热区。内容顶部有输入框时放弃。 */
    private fun attachEdgeStrip(window: Window, decor: ViewGroup) {
        if (decor !is FrameLayout) return

        val heightPx = (EDGE_STRIP_HEIGHT_DP * decor.resources.displayMetrics.density).toInt()
        if (heightPx <= 0) return

        if (contentTopHasEditText(window, heightPx)) {
            AppLog.d(TAG, "内容顶部是输入框，跳过顶部热区")
            return
        }

        val strip = View(decor.context).apply {
            // 纯热区，别让读屏把它当成一个可聚焦的空白控件。
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            heightPx
        ).apply { gravity = Gravity.TOP }

        decor.addView(strip, lp)
        attachDrag(window, strip)
    }

    /** 内容区顶部 [bandPx] 高度内是否压着 EditText。 */
    private fun contentTopHasEditText(window: Window, bandPx: Int): Boolean {
        // 拿不到内容容器时按"有输入框"处理，宁可少拖也不抢输入。
        val content = window.findViewById<View>(android.R.id.content) as? ViewGroup ?: return true
        return hasEditTextInTopBand(content, content, bandPx)
    }

    private fun hasEditTextInTopBand(view: View, content: View, bandPx: Int): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view is EditText && topRelativeTo(view, content) < bandPx) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasEditTextInTopBand(view.getChildAt(i), content, bandPx)) return true
            }
        }
        return false
    }

    /** child 顶端相对 ancestor 顶端的纵向距离。 */
    private fun topRelativeTo(child: View, ancestor: View): Int {
        var y = 0
        var v: View? = child
        while (v != null && v !== ancestor) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

    /**
     * 真正的拖动：按下记起点，超过 touchSlop 后按 raw 坐标差改窗口 x/y。
     * 不夹取边界（可以拖出屏幕），不记录位置（每次打开都是默认位置）。
     *
     * 这里不调 performClick()：热区本身没有点击语义，拖动判定完成后 UP 事件
     * 是被吞掉的，调它反而会凭空多出一次点击回调。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(window: Window, target: View) {
        val slop = ViewConfiguration.get(target.context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var active = false

        target.setOnTouchListener { v, event ->
            val attrs = window.attributes ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = attrs.x
                    startY = attrs.y
                    active = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!active) {
                        // 没过阈值就当点击，不算拖动，避免手指轻微抖动就位移。
                        if (hypot(dx, dy) < slop) return@setOnTouchListener true
                        active = true
                    }
                    attrs.x = startX + dx.toInt()
                    attrs.y = startY + dy.toInt()
                    window.windowManager?.updateViewLayout(window.decorView, attrs)
                    true
                }

                // 第二根手指落下会打断拖动，跟桌面窗口的多点行为保持一致。
                MotionEvent.ACTION_POINTER_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    active = false
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    // 拖动过就吞掉 UP，免得在标题栏上抬手指时触发下层点击。
                    active.also { active = false }
                }

                else -> false
            }
        }
    }
}
