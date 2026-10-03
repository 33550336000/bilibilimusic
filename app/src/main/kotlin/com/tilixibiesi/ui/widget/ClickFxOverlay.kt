package com.tilixibiesi.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebSettings
import android.webkit.WebView
import com.tilixibiesi.data.AppPaths
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 点击特效层：用标准 SDK 的 WebView 渲染 BA4D 的 HTML 特效，并透传指针位置。
 *
 * 与 BA4D 原版的关键差异：BA4D 靠 Shizuku + 无障碍服务读全局触摸；
 * 本实现只在**本应用内**显示，触摸来自 Activity 的 dispatchTouchEvent，无需任何权限。
 *
 * 性能三原则（曾因违反导致整屏卡死，改动此处务必遵守）：
 *  1. **节流**：触摸 MOVE 每秒可达上百次，绝不能每次都跨进程调 JS。
 *     本类按 [FRAME_INTERVAL_MS] 合帧，一帧只投递一次，且位置无变化时不投递。
 *  2. **不建 Jehov超大合成层**：elevation 必须是确定值（Float.MAX_VALUE 会让阴影投影
 *     面积无限，合成开销爆炸）；透明层不用 HARDWARE。
 *  3. **生命周期收口**：页面不可见时必须 [pause]，否则 rAF 持续渲染白耗 GPU。
 */
class ClickFxOverlay(context: Context) : WebView(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var lastPostMs = 0L
    private var lastX = -1
    private var lastY = -1
    private var lastPressed = false
    private var lastPointerId = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        // 透明覆盖层不开硬件层：全屏透明 HARDWARE 层每帧都要重合成整屏。
        // 默认软件层对 Canvas 绘制足够，且省掉额外的 GPU 合成开销。
        setLayerType(LAYER_TYPE_NONE, null)
        // 不能写死 DisplayMetrics 的 widthPixels/heightPixels：沉浸式全屏下它仍是
        // 「扣掉系统导航栏」的可用高度（实测 1260x2800 的屏只有 2696），
        // 屏幕最底部一条会永远画不出特效。DecorView 才是完整窗口，交给 MATCH_PARENT 铺满。
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 必须是确定值：Float.MAX_VALUE 会让阴影投影视为无限大，合成层极慢（曾整屏卡死）
        elevation = 1f
        settings.apply {
            javaScriptEnabled = true
            // 关掉 DOM Storage：ba.html 完全不用 localStorage / sessionStorage /
            // indexedDB / document.cookie（已对线上 ba.html 逐一 grep 确认），
            // 属于最小权限——特效是**从服务端下载**的 HTML，不给它持久化存储能力。
            //
            // 注意：这**不能**阻止 Chromium 建出 app_webview/Default/Session Storage/。
            // 已用 A/B 对照实验证伪：domStorage 开与关，生成的目录树完全一致
            // （都是 48~49 个条目）。该目录是 Chromium 初始化浏览器上下文时无条件创建的，
            // 与应用是否真的使用 DOM Storage 无关。省不了磁盘，只是收敛能力面。
            domStorageEnabled = false
            // allowFileAccess 必须保留：特效 HTML 是以 file:// 加载的本地文件。
            allowFileAccess = true
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            // 视口必须与屏幕 1:1：
            // 开 wide viewport / overview 会让 innerWidth 变成缩放后的布局视口，
            // HTML 又把坐标钳制到 0..width，右下角触摸就会被压到左上角且不成对称。
            useWideViewPort = false
            loadWithOverviewMode = false
        }
        webViewClient = android.webkit.WebViewClient()
    }

    /**
     * 窗口尺寸变化（转屏、折叠屏展开、系统栏显隐）时让页面重新 resize。
     *
     * 布局参数是 MATCH_PARENT，尺寸由 DecorView 自动给足，无需手动改；
     * 但 HTML 内部的 canvas 是按首次布局的视口尺寸建的，
     * 尺寸变了必须派发一次 resize，否则画布仍停在旧尺寸（表现为换屏后底部又缺一条）。
     * 不重新 load：那会丢掉正在播放的特效，还要重等 600ms 就绪。
     */
    fun syncBounds() {
        evaluateJavascript("(function(){try{window.dispatchEvent(new Event('resize'));}catch(e){}})();", null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (ready && w > 0 && h > 0 && (w != oldw || h != oldh)) syncBounds()
    }

    /**
     * 加载特效 HTML。
     *
     * 使用本地文件而非 assets：这样特效可随时从服务端更新，无需发版。
     * file:// 加载本地 HTML 时，其中的 <script> 需要能读取同源资源，
     * 这里全部内联在单个 HTML 内，不受 allowUniversalAccess 限制。
     */
    fun load() {
        val file = AppPaths.clickFxFile()
        if (!file.exists() || file.length() == 0L) {
            ready = false
            return
        }
        loadUrl("file://" + file.absolutePath)
        handler.postDelayed({ markReady() }, 600)
    }

    /** 重新加载（例如 HTML 刚下载完成时调用）。
     *  不叫 reload：WebView 已有同名公开方法，会造成覆盖歧义。 */
    fun loadAgain() {
        ready = false
        lastPostMs = 0L
        lastX = -1
        lastY = -1
        load()
    }

    private fun markReady() {
        ready = true
        val dpr = resources.displayMetrics.density
        val js = "(function(){try{" +
            "window.__MIRAAPI_ELEMENT_ID__=window.__MIRAAPI_ELEMENT_ID__||1;" +
            "window.postMessage({type:'__MIRAAPI_DISPLAY_ORIGIN__',origin:{x:0,y:0}},'*');" +
            "window.postMessage({type:'__MIRAAPI_PROPS__',props:{" +
            "maxDpr:Math.max(1,Math.min(3," + dpr + "))," +
            "fpsLimit:60" +
            "}},'*');" +
            "}catch(e){}})();"
        evaluateJavascript(js, null)
    }

    /**
     * 旁路采集触摸。必须不影响返回值，原有分发链路一律照旧。
     */
    fun feed(ev: MotionEvent) {
        if (!ready) return
        // 多指时以最后一个指针为准：特效是装饰性的，
        // 逐建立一个 eVery effet instance 会线性放大 JS 与渲染开销。
        val idx = ev.pointerCount - 1
        if (idx < 0) return
        val pid = ev.getPointerId(idx)
        val x = ev.getRawX(idx).toInt()
        val y = ev.getRawY(idx).toInt()
        val pressed = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_DOWN -> true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> false
            else -> return
        }
        // 关键节流：按下/抬起必须立即送达（否则丢 down/up 会缺特效或不停住），
        // MOVE 则按帧节流，且位置无实质变化时不打扰 WebView。
        val now = android.os.SystemClock.uptimeMillis()
        val stateChanged = pressed != lastPressed || pid != lastPointerId
        val movedEnough = abs(x - lastX) > 1 || abs(y - lastY) > 1
        if (!stateChanged && movedEnough && now - lastPostMs < FRAME_INTERVAL_MS) return
        if (!stateChanged && !movedEnough) return
        lastPostMs = now
        lastX = x
        lastY = y
        lastPressed = pressed
        lastPointerId = pid
        postPointer(pid, x, y, pressed)
    }

    private fun postPointer(pointerId: Int, x: Int, y: Int, pressed: Boolean) {
        val buf = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x02.toByte())
        buf.putInt(pointerId)
        buf.putInt(x)
        buf.putInt(y)
        buf.put(if (pressed) 1 else 0)
        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        val js = "(function(){try{var raw=atob('" + b64 + "');" +
            "var b=new Uint8Array(raw.length);" +
            "for(var i=0;i<raw.length;i++)b[i]=raw.charCodeAt(i);" +
            "window.postMessage({type:'__MIRAAPI_INPUT__'," +
            "id:(window.__MIRAAPI_ELEMENT_ID__||1),device:'touch',payload:b.buffer},'*');" +
            "}catch(e){}})();"
        evaluateJavascript(js, null)
    }

    /**
     * 页面不可见时调用：彻底停摆 WebView 渲染。
     *
     * 只调 WebView.onPause() 不够：它暂停的是插件/V8，
     * 页面里的 requestAnimationFrame 循环仍会被 VSYNC 驱动，
     * 回到前台时还可能出现「卡在原地不响应」——因为 rAF 回调被暂停期间堆积、
     * 或 JS 状态与实际指针不同步。这里同时：
     *  1. 停 WebView 自身；
     *  2. 通知页面 blur，让 BA4D 释放所有 activePointer（其代码里 blur 会清理指针）；
     *  3. 停掉可能残留的形式 비动画。
     */
    fun pause() {
        evaluateJavascript(
            "(function(){try{" +
                "window.dispatchEvent(new Event('blur'));" +
                "if(window.BAClickFXDemo&&window.BAClickFXDemo.paused!==undefined)" +
                "window.BAClickFXDemo.paused=true;" +
                "}catch(e){}})();", null
        )
        super.onPause()
    }

    /**
     * 应用整体退到后台时调用：直接销毁 WebView，而不是只 [pause]。
     *
     * 两点原因：
     *  1. **性能**：WebView 活着就常驻一个 Chromium 渲染进程 + GPU 纹理 + 缓存（几十 MB），
     *     后台纯属白占——特效本来只在用户能看见时才需要渲染。
     *  2. **正确**：应用不可见时会激进清理私有数据目录
     *     （[com.tilixibiesi.data.WebViewMetricsCleaner]），而 WebView 存活时
     *     Chromium 会在几秒内把 `app_webview/` 原样写回来（实测 8 秒重建），删了等于白删。
     *     先销毁它，清理才追得上。
     *
     * 这里必须 `destroy()`。视图留在 DecorView 上不影响：回到前台时
     * [com.tilixibiesi.ui.MainPagerActivity.onResume] 会整个重建特效层。
     */
    fun releaseForBackground() {
        runCatching {
            stopLoading()
            val p = parent as? ViewGroup
            if (p != null) {
                // 摘出视图树会触发 onDetachedFromWindow() → destroy()，不必再显式调一次
                p.removeView(this)
            } else {
                destroy()
            }
        }
    }

    /** 回到前台：恢复渲染并重置本地缓存状态，避免投递被去重逻辑误吞 */
    fun resume() {
        super.onResume()
        lastPostMs = 0L
        lastX = -1
        lastY = -1
        lastPressed = false
        lastPointerId = -1
        evaluateJavascript(
            "(function(){try{" +
                "if(window.BAClickFXDemo&&window.BAClickFXDemo.paused!==undefined)" +
                "window.BAClickFXDemo.paused=false;" +
                "}catch(e){}})();", null
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        destroy()
    }

    companion object {
        /** 投递节流间隔（毫秒）：约 60fps 的合成节奏 */
        private const val FRAME_INTERVAL_MS = 16L
    }
}
