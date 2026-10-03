package com.tilixibiesi.util

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar

object ViewUtils {

    /**
     * 递归遍历视图树，为所有 Button 和 ProgressBar 添加按下缩放效果。
     * 适用于静态布局（如 Activity 根布局），不适用于动态列表。
     */
    fun applyScaleOnTouch(root: ViewGroup?) {
        root?.let {
            for (i in 0 until it.childCount) {
                val child = it.getChildAt(i)
                when (child) {
                    is Button -> {
                        child.setOnTouchListener(
                            ScaleTouchListener(
                                scaleDown = 0.87f,
                                durationDown = 50L,
                                durationUp = 100L
                            )
                        )
                    }
                    is ProgressBar -> {
                        child.setOnTouchListener(
                            ScaleTouchListener(
                                scaleDown = 1.2f,
                                durationDown = 50L,
                                durationUp = 150L
                            )
                        )
                    }
                    is ViewGroup -> {
                        applyScaleOnTouch(child)
                    }
                }
            }
        }
    }

    /**
     * 为单个按钮设置按压缩放，消费事件并传递给原视图，保证点击正常。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun applyScaleToButton(
        view: View,
        scaleDown: Float = 0.87f,
        durationDown: Long = 50L,
        durationUp: Long = 100L
    ) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(scaleDown)
                        .scaleY(scaleDown)
                        .setDuration(durationDown)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(durationUp)
                        .start()
                }
            }
            // 将事件传递给原视图，使按钮的点击仍能触发
            v.onTouchEvent(event)
            true
        }
    }

    /**
     * 为列表 item 的根布局设置按压缩放，并通过 performItemClick 触发列表点击。
     * 适用于 ListView。
     */
    fun applyScaleToItemView(
        view: View,
        positionProvider: () -> Int,
        scaleDown: Float = 0.95f,
        duration: Long = 100L
    ) {
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var isSwiped = false
        var isLongPressed = false
        // 自实现长按检测：touch listener 返回 true 会屏蔽系统长按，因此手动触发列表长按
        val longPressRunnable = Runnable {
            isLongPressed = true
            val position = positionProvider()
            val parent = view.parent
            if (position >= 0 && parent is ListView) {
                parent.onItemLongClickListener?.onItemLongClick(parent, view, position, view.id.toLong())
            }
        }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    isSwiped = false
                    isLongPressed = false
                    v.removeCallbacks(longPressRunnable)
                    v.postDelayed(longPressRunnable, longPressTimeout)
                    v.animate()
                        .scaleX(scaleDown)
                        .scaleY(scaleDown)
                        .setDuration(duration)
                        .start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        isSwiped = true
                        v.removeCallbacks(longPressRunnable)
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(duration)
                            .start()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(duration)
                        .start()
                    if (!isSwiped && !isLongPressed) {
                        // 无障碍服务通过 performClick() 触发点击，先回调一次以保持语义
                        v.performClick()
                        // 触发 ListView 的点击事件；位置每次实时解析，避免 convertView 复用时索引过期
                        val position = positionProvider()
                        val parent = v.parent
                        if (position >= 0 && parent is ListView) {
                            parent.performItemClick(v, position, v.id.toLong())
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(duration)
                        .start()
                }
            }
            true
        }
    }

    /**
     * 通用的触摸缩放监听器（内部使用），会消费触摸事件。
     */
    @SuppressLint("ClickableViewAccessibility")
    private class ScaleTouchListener(
        private val scaleDown: Float,
        private val durationDown: Long,
        private val durationUp: Long
    ) : View.OnTouchListener {

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(scaleDown)
                        .scaleY(scaleDown)
                        .setDuration(durationDown)
                        .start()
                    v.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    v.onTouchEvent(event)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(durationUp)
                        .start()
                    v.onTouchEvent(event)
                }
            }
            return true
        }
    }
}