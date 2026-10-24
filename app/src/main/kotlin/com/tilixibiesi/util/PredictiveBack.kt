package com.tilixibiesi.util

import android.app.Activity
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

object PredictiveBack {

    const val TAG_PAGER = "pager"
    const val TAG_NOW_PLAYING = "now_playing"
    const val TAG_FULLSCREEN_VIDEO = "fullscreen_video"
    const val TAG_LOGIN = "login"

    private class Handler {
        var onInvoke: () -> Unit = {}
        var onProgress: ((Float) -> Unit)? = null
        var onCancel: (() -> Unit)? = null

        var cancelled = false
    }

    private val registered = HashMap<Activity, MutableMap<String, OnBackInvokedCallback>>()
    private val handlers = HashMap<Activity, MutableMap<String, Handler>>()

    fun register(
        activity: Activity,
        tag: String,
        onInvoke: () -> Unit,
        onProgress: ((Float) -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        if (Build.VERSION.SDK_INT < 33) return

        handlers[activity]?.get(tag)?.let { existing ->
            existing.onInvoke = onInvoke
            existing.onProgress = onProgress
            existing.onCancel = onCancel
            return
        }

        val handler = Handler().apply {
            this.onInvoke = onInvoke
            this.onProgress = onProgress
            this.onCancel = onCancel
        }

        val decor = activity.window?.decorView
        if (decor != null && !decor.isAttachedToWindow) {
            handlers.getOrPut(activity) { HashMap() }[tag] = handler
            decor.post {
                if (!handler.cancelled) registerNow(activity, tag, handler)
            }
            return
        }
        registerNow(activity, tag, handler)
    }

    private fun registerNow(activity: Activity, tag: String, handler: Handler) {
        if (handler.cancelled) return
        if (registered[activity]?.containsKey(tag) == true) return

        val callback: OnBackInvokedCallback =
            if (Build.VERSION.SDK_INT >= 34) {
                object : OnBackAnimationCallback {
                    override fun onBackStarted(backEvent: BackEvent) {
                        handler.onProgress?.invoke(0f)
                    }

                    override fun onBackProgressed(backEvent: BackEvent) {
                        handler.onProgress?.invoke(backEvent.progress.coerceIn(0f, 1f))
                    }

                    override fun onBackCancelled() {
                        handler.onCancel?.invoke()
                    }

                    override fun onBackInvoked() {
                        handler.onInvoke()
                    }
                }
            } else {
                OnBackInvokedCallback { handler.onInvoke() }
            }

        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback
        )
        registered.getOrPut(activity) { HashMap() }[tag] = callback
        handlers.getOrPut(activity) { HashMap() }[tag] = handler
    }

    fun unregister(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        handlers[activity]?.let { h ->
            h.remove(tag)?.cancelled = true
            if (h.isEmpty()) handlers.remove(activity)
        }
        val map = registered[activity] ?: return
        val callback = map.remove(tag) ?: return
        if (map.isEmpty()) registered.remove(activity)
        activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
    }
}
