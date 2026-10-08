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
            v.onTouchEvent(event)
            true
        }
    }

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
                        v.performClick()
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