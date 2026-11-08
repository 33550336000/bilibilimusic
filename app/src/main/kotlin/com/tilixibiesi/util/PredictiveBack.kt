package com.tilixibiesi.util

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

import java.util.WeakHashMap

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

    /**
     * 按 Activity 分组的两张表。
     *
     * 必须用 [WeakHashMap]：`PredictiveBack` 是 object 单例（生命周期 = 进程），
     * 用强引用 key 会让「任何一条 unregister 没走到的路径」都把整个 Activity
     * 永久钉死在堆上。改为弱引用是防御性的正确做法，代价为零。
     *
     * 注意：改弱引用后实测「recreate 8 轮 Native Heap 23MB→71MB」的现象**依旧存在**，
     * 说明那笔增长的主要来源不在这里（已在 onDestroy 加日志确认 MainPagerActivity
     * 的 onCreate/onDestroy 是 1→0→1→0 正常配对，没有实例累积）。
     * 本类的改动解决的是「注册表自身可能钉住 Activity」这一确定缺陷，
     * 不是那个现象的完整解药。
     */
    private val registered =
        WeakHashMap<Activity, MutableMap<String, OnBackInvokedCallback>>()
    private val handlers = WeakHashMap<Activity, MutableMap<String, Handler>>()

    fun register(
        activity: Activity,
        tag: String,
        onInvoke: () -> Unit,
        onProgress: ((Float) -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        if (Build.VERSION.SDK_INT < 33) return
        // 已销毁的 Activity 不再接受注册：onResume 之后仍有异步分支可能回调到这里，
        // 一旦注册进去，这个已 destroy 的 Activity 就会被静态表钉住直到进程结束。
        if (activity.isDestroyed) return

        handlers[activity]?.get(tag)?.let { existing ->
            existing.onInvoke = onInvoke
            existing.onProgress = onProgress
            existing.onCancel = onCancel
            if (registered[activity]?.containsKey(tag) != true) {
                existing.cancelled = false
                registerNow(activity, tag, existing)
            }
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

    @SuppressLint("NewApi")
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

    /**
     * 撤销某个 tag 的注册。
     *
     * 旧实现在 `registered[activity]` 不存在时直接 `return`，
     * 导致 `handlers` 可能残留。现在两边都独立清理，不再互相短路。
     */
    fun unregister(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT < 33) return

        handlers[activity]?.let { h ->
            h.remove(tag)?.cancelled = true
            if (h.isEmpty()) handlers.remove(activity)
        }

        registered[activity]?.let { map ->
            map.remove(tag)?.let { callback ->
                if (map.isEmpty()) registered.remove(activity)
                runCatching {
                    activity.onBackInvokedDispatcher
                        .unregisterOnBackInvokedCallback(callback)
                }
            }
        }
    }

    /**
     * 清掉某个 Activity 的全部注册。
     *
     * 给 Activity.onDestroy 兜底用：即便某个业务方漏了 [unregister]，
     * 只要 Activity 走到 onDestroy，这里就能把两张表里的条目一次性清干净。
     *
     * 注意顺序问题：`register()` 有一条 `decor.post { registerNow(...) }` 的异步分支，
     * 如果它在 destroy 之后才跑，会把已销毁的 Activity 又注册回去。
     * 所以这里除了清表，还要把该 Activity 下所有 handler 标记为 cancelled——
     * 异步分支里的 `if (!handler.cancelled)` 会因此跳过注册。
     */
    fun clearAll(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return

        handlers.remove(activity)?.let { map ->
            for (h in map.values) h.cancelled = true
        }
        val map = registered.remove(activity) ?: return
        for (callback in map.values) {
            runCatching {
                activity.onBackInvokedDispatcher
                    .unregisterOnBackInvokedCallback(callback)
            }
        }
    }

    /**
     * 该 Activity 是否还有未撤销的注册。仅供调试/断言使用。
     */
    fun hasPending(activity: Activity): Boolean =
        handlers.containsKey(activity) || registered.containsKey(activity)
}
