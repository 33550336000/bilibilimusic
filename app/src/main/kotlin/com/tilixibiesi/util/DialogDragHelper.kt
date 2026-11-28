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

object DialogDragHelper {

    private const val TAG = "DialogDragHelper"

    private const val EDGE_STRIP_HEIGHT_DP = 12

    fun attach(dialog: Dialog?) {
        val window = dialog?.window ?: return
        val decor = window.decorView as? ViewGroup ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        val titlePanel = findTitlePanel(window)
        if (titlePanel != null) {
            attachDrag(window, titlePanel)
        } else {

            decor.post { attachEdgeStrip(window, decor) }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun findTitlePanel(window: Window): View? {
        val res = window.context.resources
        val templateId = res.getIdentifier("title_template", "id", "android")
        if (templateId != 0) {
            val template = window.findViewById<View>(templateId)
            if (template != null && template.isShown) return template
        }

        val titleId = res.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) {
            val title = window.findViewById<View>(titleId)
            val parent = title?.parent as? View
            if (parent != null && parent.isShown) return parent
        }
        return null
    }

    private fun attachEdgeStrip(window: Window, decor: ViewGroup) {
        if (decor !is FrameLayout) return

        val heightPx = (EDGE_STRIP_HEIGHT_DP * decor.resources.displayMetrics.density).toInt()
        if (heightPx <= 0) return

        if (contentTopHasEditText(window, heightPx)) {
            AppLog.d(TAG, "内容顶部是输入框，跳过顶部热区")
            return
        }

        val strip = View(decor.context).apply {

            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            heightPx
        ).apply { gravity = Gravity.TOP }

        decor.addView(strip, lp)
        attachDrag(window, strip)
    }

    private fun contentTopHasEditText(window: Window, bandPx: Int): Boolean {

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

    private fun topRelativeTo(child: View, ancestor: View): Int {
        var y = 0
        var v: View? = child
        while (v != null && v !== ancestor) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

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

                        if (hypot(dx, dy) < slop) return@setOnTouchListener true
                        active = true
                    }
                    attrs.x = startX + dx.toInt()
                    attrs.y = startY + dy.toInt()
                    window.windowManager?.updateViewLayout(window.decorView, attrs)
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    active = false
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)

                    active.also { active = false }
                }

                else -> false
            }
        }
    }
}
