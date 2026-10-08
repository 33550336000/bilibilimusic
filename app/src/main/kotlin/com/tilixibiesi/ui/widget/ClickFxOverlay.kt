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
        setLayerType(LAYER_TYPE_NONE, null)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        elevation = 1f
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = true
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = false
            loadWithOverviewMode = false
        }
        webViewClient = android.webkit.WebViewClient()
    }

    fun syncBounds() {
        evaluateJavascript("(function(){try{window.dispatchEvent(new Event('resize'));}catch(e){}})();", null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (ready && w > 0 && h > 0 && (w != oldw || h != oldh)) syncBounds()
    }

    fun load() {
        val file = AppPaths.clickFxFile()
        if (!file.exists() || file.length() == 0L) {
            ready = false
            return
        }
        loadUrl("file://" + file.absolutePath)
        handler.postDelayed({ markReady() }, 600)
    }

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

    fun feed(ev: MotionEvent) {
        if (!ready) return
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

    fun releaseForBackground() {
        runCatching {
            stopLoading()
            val p = parent as? ViewGroup
            if (p != null) {
                p.removeView(this)
            } else {
                destroy()
            }
        }
    }

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
        private const val FRAME_INTERVAL_MS = 16L
    }
}
