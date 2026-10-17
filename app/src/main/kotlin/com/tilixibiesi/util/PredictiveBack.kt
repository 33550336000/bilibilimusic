package com.tilixibiesi.util

import android.app.Activity
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * 预测性返回手势的最小封装。
 *
 * 用法与系统标准一致：
 *  - 只在「确实有东西要拦」的时候 [register]，关闭时 [unregister]，两边成对；
 *  - 不注册时返回留给系统，跨 Activity、回桌面的预测动画照常播放；
 *  - 注册后由本 App 自己播跟手动画。
 *
 * 关键约束（都来自系统侧的真实行为，偏离任何一条都会让跟手动画失效）：
 *
 *  1. **一个 tag 只向系统注册一次**，之后只换闭包，绝不重复调用
 *     `registerOnBackInvokedCallback`。系统每收到一次新注册就会重置该窗口的
 *     返回动画状态，正在进行的跟手追踪随之作废——表现为「能返回但没有动画」。
 *     所以本类刻意不提供「重新注册 / 重新提交」这类接口。
 *
 *  2. 回调必须在窗口 attach 之后才注册，否则系统收不到。窗口未就绪时
 *     先记下闭包，等 decorView attach 后再注册一次。
 *
 *  3. API 33 只有 [OnBackInvokedCallback]（抬手才回调）；
 *     API 34+ 用 [OnBackAnimationCallback] 拿到跟手的 progress。
 *
 * 另外，窗口本身还必须完成过一次真正的 pause→resume，系统才会把它纳入返回动画
 * 控制；否则回调收得到，但 progress 恒为 0、touchX 也不更新。这一条与本类无关，
 * 由 [com.tilixibiesi.ui.BackGesturePrimerActivity] 在冷启动时补上。
 *
 * 同一个 Activity 上可能同时存在多个持有者（主页翻页、播放页浮层、全屏视频），
 * 按 [tag] 分别记录：注销自己的那份不会误删别人的。系统按注册先后逆序派发，
 * 后注册的优先，正好符合「上层浮层先响应」。
 */
object PredictiveBack {

    const val TAG_PAGER = "pager"
    const val TAG_NOW_PLAYING = "now_playing"
    const val TAG_FULLSCREEN_VIDEO = "fullscreen_video"
    const val TAG_LOGIN = "login"

    /**
     * 每个 tag 对应一个可变的回调载体。
     *
     * 系统只认注册时那一个 [OnBackInvokedCallback] 实例，所以不能「重新注册一次」来换逻辑，
     * 只能把闭包换掉、让同一个实例转调新闭包。
     */
    private class Handler {
        var onInvoke: () -> Unit = {}
        var onProgress: ((Float) -> Unit)? = null
        var onCancel: (() -> Unit)? = null

        /**
         * 已注销标记。
         *
         * 延迟注册（等窗口 attach）的场景里，闭包可能在这之前就被 unregister 掉了，
         * 没有这个标记就会在延迟任务执行时又把它注册回去，返回键从此被吞掉。
         */
        var cancelled = false
    }

    private val registered = HashMap<Activity, MutableMap<String, OnBackInvokedCallback>>()
    private val handlers = HashMap<Activity, MutableMap<String, Handler>>()

    /**
     * @param onInvoke 抬手后真正执行返回。
     * @param onProgress 手势进度 0f→1f，只有 API 34+ 会回调；用于跟手动画。
     * @param onCancel 手势被取消，把动画复位。
     */
    fun register(
        activity: Activity,
        tag: String,
        onInvoke: () -> Unit,
        onProgress: ((Float) -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        if (Build.VERSION.SDK_INT < 33) return

        // 已注册：只换闭包。绝不重新注册——那会重置系统侧的返回动画状态。
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

        // 窗口还没 attach 时注册，系统收不到（dispatcher 要等 ViewRootImpl 建立）。
        // 这时先记下闭包，等 decorView attach 后再真正注册。
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

    /** 真正向系统注册；[handler] 已就绪，闭包后续只换内容不换实例。 */
    private fun registerNow(activity: Activity, tag: String, handler: Handler) {
        if (handler.cancelled) return
        // 已经注册过就绝不再注册第二次
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

    /** 注销指定持有者；没注册过时是空操作，可以放心在 hide/onDestroy 里重复调用。 */
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
