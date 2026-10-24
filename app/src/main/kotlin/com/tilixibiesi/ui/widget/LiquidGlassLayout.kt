package com.tilixibiesi.ui.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import kotlin.math.abs

class LiquidGlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val DOWNSCALE = 3

        private const val CORNER_DP = 26f
        private const val BEVEL_DP = 18f

        private const val TILT_SMOOTHING = 0.08f
        private const val TILT_DEADZONE = 0.02f

        private const val PRESS_IN_MS = 90L
        private const val PRESS_OUT_MS = 160L

        private const val ADAPT_LOWPASS = 0.35f

        private var captureDepth = 0

        internal val capturingBackdrop: Boolean get() = captureDepth > 0

        private val shaderSupported: Boolean by lazy {
            Build.VERSION.SDK_INT >= 33 && runCatching { RuntimeShader(AGSL) }.isSuccess
        }

        fun isAvailable(): Boolean = shaderSupported

        val AGSL = """
uniform shader uBackdrop;
uniform float2 uSize;
uniform float uCorner;
uniform float uBevel;
uniform float2 uTilt;
uniform float uPress;
uniform float uAdapt;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float2 clampSample(float2 q) {
    return clamp(q, float2(0.5, 0.5), uSize - float2(0.5, 0.5));
}

half4 main(float2 fragCoord) {
    float2 halfSize = uSize * 0.5;
    float2 p = fragCoord;

    float d = sdRoundBox(p - halfSize, halfSize, uCorner);
    float e = 1.5;
    float2 g = float2(
        sdRoundBox(p + float2(e, 0.0) - halfSize, halfSize, uCorner) - d,
        sdRoundBox(p + float2(0.0, e) - halfSize, halfSize, uCorner) - d
    );
    float glen = length(g);
    float2 n = glen > 0.000001 ? g / glen : float2(0.0);

    float bevel = max(uBevel, 1.0);
    bevel *= mix(1.0, 0.72, uPress);
    float inward = clamp((-d) / bevel, 0.0, 1.0);
    float edge = 1.0 - inward;
    float h = sqrt(max(0.0, 1.0 - edge * edge));

    float3 N = normalize(float3(n * edge * 1.20, h + 0.28));

    float3 I = float3(0.0, 0.0, -1.0);
    float eta = 1.0 / 1.48;
    float cosi = -dot(N, I);
    float k = 1.0 - eta * eta * (1.0 - cosi * cosi);
    float3 T = k < 0.0 ? reflect(I, N) : eta * I + (eta * cosi - sqrt(k)) * N;

    float2 dir = T.xy / max(abs(T.z), 0.20);

    float shrinkBase = bevel * mix(0.055, 0.075, uPress);
    float2 centered = (p - halfSize) / halfSize;
    float2 shrinkVec = -centered * shrinkBase;

    float strength = bevel * mix(0.34, 0.52, uPress) * edge * edge * edge;

    float2 base = p + shrinkVec + dir * strength;

    float disp = strength * mix(0.10, 0.16, uPress);
    half4 sR = uBackdrop.eval(clampSample(base + dir * disp));
    half4 sG = uBackdrop.eval(clampSample(base));
    half4 sB = uBackdrop.eval(clampSample(base - dir * disp));

    float3 neutral = float3(0.86, 0.87, 0.90);
    float a = clamp(float(sG.a), 0.0, 1.0);
    float3 col = float3(float(sR.r), float(sG.g), float(sB.b)) + (1.0 - a) * neutral;

    half4 m = uBackdrop.eval(clampSample(p - n * (bevel * 1.35) * edge));
    float ma = clamp(float(m.a), 0.0, 1.0);
    float3 echo = float3(float(m.r), float(m.g), float(m.b)) + (1.0 - ma) * neutral;
    col = mix(col, echo, smoothstep(0.72, 1.0, edge) * 0.22);

    float luma = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = luma + (col - luma) * 1.50;
    col = (col - 0.5) * 1.06 + 0.5;

    float filmScale = mix(1.55, 0.55, uAdapt);
    float film = mix(0.02, 0.075, smoothstep(0.72, 1.0, edge)) * filmScale;
    film *= mix(1.0, 1.35, uPress);
    col = mix(col, float3(1.0), clamp(film, 0.0, 1.0));

    float rimW = clamp(2.0 / max(bevel, 1.0), 0.0, 0.12);
    float rim = smoothstep(1.0 - rimW * 3.0, 1.0, edge);
    float fres = pow(1.0 - clamp(dot(N, float3(0.0, 0.0, 1.0)), 0.0, 1.0), 3.0);

    float2 lightDir = normalize(float2(-0.55, -0.83));
    float facing = clamp(dot(n, lightDir), -1.0, 1.0);
    float dirWeight = mix(0.35, 1.0, (facing + 1.0) * 0.5);

    float topBias = mix(0.90, 1.15, 1.0 - clamp(p.y / uSize.y, 0.0, 1.0));
    float rimScale = mix(1.15, 0.85, uAdapt) * mix(1.0, 1.5, uPress)
        * topBias * dirWeight;
    col += float3(fres * 0.16 * rimScale);
    col += float3(rim * 0.30 * rimScale);

    float2 hl = float2(uSize.x * (0.5 + uTilt.x * 0.30), uSize.y * (0.30 + uTilt.y * 0.22));
    float hd = length((p - hl) / float2(uSize.x * 0.55, uSize.y * 1.10));
    col += float3(smoothstep(0.85, 0.0, hd) * 0.035);

    float2 c = (p - halfSize) / halfSize;
    float core = 1.0 - smoothstep(0.0, 1.25, length(c));
    float glowGain = mix(0.085, 0.045, uAdapt) * uPress;
    col += float3(core * glowGain);
    float edgeGlow = smoothstep(0.55, 1.0, edge) * uPress;
    col += float3(edgeGlow * 0.10 * mix(1.0, 0.7, uAdapt));

    float topRim = (1.0 - smoothstep(0.0, 0.045, p.y / uSize.y)) * smoothstep(0.0, 0.30, inward);
    col += float3(topRim * 0.05);

    float2 gp = p * 1.35;
    float grain = fract(sin(dot(gp, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    col += float3(grain * 0.016 * mix(1.0, 0.6, uPress));

    col *= 0.99;

    float mask = 1.0 - smoothstep(-1.5, 0.5, d);
    return half4(half3(clamp(col, 0.0, 1.0) * mask), mask);
}
"""
    }

    var backdropSource: View? = null
        set(value) {
            if (field === value) return
            detachWatcher()
            field = value
            invalidateBackdrop()
            if (isAttachedToWindow) value?.viewTreeObserver?.addOnPreDrawListener(preDrawWatcher)
            invalidate()
        }

    var glassEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateBackdrop()
            updateTiltSensor()
            invalidate()
        }

    var blurRadiusDp: Float = 6f
        set(value) {
            if (field == value) return
            field = value
            blurEffect = null
            invalidate()
        }

    var cornerRadiusDp: Float = CORNER_DP
        set(value) {
            if (field == value) return
            field = value
            cornerPx = value * density
            invalidateOutline()
            invalidate()
        }

    var solidWhenDisabled: Boolean = true

    var tiltEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            updateTiltSensor()
        }

    private val density = resources.displayMetrics.density
    private var cornerPx = CORNER_DP * density
    private val bevelPx = BEVEL_DP * density

    private val clipPath = Path()
    private val boundsF = RectF()
    private var clipWidth = -1
    private var clipHeight = -1
    private var clipCorner = Float.NaN

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 0x3D
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x59FFFFFF
    }
    private val solidBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x1F000000
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val captureProxyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33EEEDF4
    }

    private var backdrop: Bitmap? = null
    private var captureCanvas: Canvas? = null
    private var lastSignature = Int.MIN_VALUE
    private var lastPreDrawSignature = Int.MIN_VALUE

    private var shader: RuntimeShader? = null
    private var shaderBroken = false
    private var blurEffect: RenderEffect? = null
    private var blurEffectRadius = Float.NaN
    private var shaderEffect: RenderEffect? = null

    private var effectWidth = -1
    private var effectHeight = -1
    private var effectCorner = Float.NaN
    private var effectBevel = Float.NaN
    private var effectTiltX = Float.NaN
    private var effectTiltY = Float.NaN
    private var effectPress = Float.NaN
    private var effectAdapt = Float.NaN

    private val blurNode = RenderNode("liquidGlassBlur")
    private val shaderNode = RenderNode("liquidGlassShader")
    private val blurRect = RectF()

    private val selfLoc = IntArray(2)
    private val srcLoc = IntArray(2)

    private var tiltX = 0f
    private var tiltY = 0f
    private var sensorManager: SensorManager? = null

    private var press = 0f
    private var pressAnimator: ValueAnimator? = null

    var pressEnabled: Boolean = true

    private var adaptLuma = 0.5f
    private var adaptPending = false
    private var adaptSampledSignature = Int.MIN_VALUE

    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val nx = (-event.values[1] * 4f).coerceIn(-1f, 1f)
            val ny = (-event.values[0] * 4f).coerceIn(-1f, 1f)
            val sx = tiltX + (nx - tiltX) * TILT_SMOOTHING
            val sy = tiltY + (ny - tiltY) * TILT_SMOOTHING
            if (abs(sx - tiltX) < TILT_DEADZONE && abs(sy - tiltY) < TILT_DEADZONE) return
            tiltX = sx
            tiltY = sy
            if (glassEnabled) postInvalidateOnAnimation()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
            }
        }
    }

    fun invalidateBackdrop() {
        lastSignature = Int.MIN_VALUE
        lastPreDrawSignature = Int.MIN_VALUE
    }

    fun refreshBackdrop() {
        invalidateBackdrop()
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidateBackdrop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidateBackdrop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateBackdrop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTiltSensor()
        backdropSource?.viewTreeObserver?.addOnPreDrawListener(preDrawWatcher)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sensorManager?.unregisterListener(tiltListener)
        pressAnimator?.cancel()
        pressAnimator = null
        press = 0f
        detachWatcher()
        backdrop = null
        captureCanvas = null
        invalidateBackdrop()
    }

    private val preDrawWatcher = android.view.ViewTreeObserver.OnPreDrawListener {
        val src = backdropSource
        if (src != null && glassEnabled) {
            val sig = backdropSignature(src)
            if (sig != lastPreDrawSignature) {
                lastPreDrawSignature = sig
                invalidate()
            }
        }
        true
    }

    private fun detachWatcher() {
        val observer = backdropSource?.viewTreeObserver ?: return
        if (!observer.isAlive) return
        observer.removeOnPreDrawListener(preDrawWatcher)
    }

    private fun backdropSignature(src: View): Int {
        getLocationInWindow(selfLoc)
        return scrollSignature(src, 96) * 31 + selfLoc[0] * 31 + selfLoc[1]
    }

    private fun scrollSignature(v: View, budget: Int): Int {
        var h = v.width * 31 + v.height
        h = h * 31 + v.scrollX
        h = h * 31 + v.scrollY
        if (v is android.widget.AdapterView<*>) {
            h = h * 31 + v.count
            h = h * 31 + v.firstVisiblePosition
            h = h * 31 + (v.getChildAt(0)?.top ?: 0)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                h = h * 31 + child.left
                h = h * 31 + child.top
                if (budget > 0) h = h * 31 + scrollSignature(child, budget - 1)
            }
        }
        return h
    }

    override fun onDraw(canvas: Canvas) {
        boundsF.set(0f, 0f, width.toFloat(), height.toFloat())
        if (clipWidth != width || clipHeight != height || clipCorner != cornerPx) {
            clipPath.reset()
            clipPath.addRoundRect(boundsF, cornerPx, cornerPx, Path.Direction.CW)
            clipWidth = width
            clipHeight = height
            clipCorner = cornerPx
        }

        if (capturingBackdrop) {
            drawCaptureProxy(canvas)
            return
        }

        if (!glassEnabled) {
            drawSolid(canvas)
            return
        }

        val bmp = if (shaderSupported) refreshBackdropIfNeeded() else null
        if (bmp == null) {
            if (!shaderSupported && !solidWhenDisabled) return
            drawSolid(canvas)
            return
        }

        var drawn = false
        if (canvas.isHardwareAccelerated) {
            drawn = try {
                drawShaderPass(canvas, bmp)
            } catch (_: Throwable) {
                shaderBroken = true
                false
            }
        }
        if (!drawn && !solidWhenDisabled) return
        if (!drawn) drawFallback(canvas, bmp)

        drawSpecular(canvas)
        canvas.drawRoundRect(boundsF, cornerPx, cornerPx, borderPaint)
    }

    private fun drawSolid(canvas: Canvas) {
        if (!solidWhenDisabled) return
        canvas.drawRoundRect(boundsF, cornerPx, cornerPx, whitePaint)
        canvas.drawRoundRect(boundsF, cornerPx, cornerPx, solidBorderPaint)
    }

    private fun drawCaptureProxy(canvas: Canvas) {
        if (!glassEnabled) {
            drawSolid(canvas)
            return
        }
        val bmp = backdrop
        val save = canvas.save()
        canvas.clipPath(clipPath)
        if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
            canvas.drawBitmap(bmp, null, boundsF, bitmapPaint)
        } else {
            canvas.drawRect(boundsF, captureProxyPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun drawShaderPass(canvas: Canvas, bmp: Bitmap): Boolean {
        val s = ensureShader() ?: return false

        s.setFloatUniform("uSize", width.toFloat(), height.toFloat())
        s.setFloatUniform("uCorner", cornerPx)
        s.setFloatUniform("uBevel", bevelPx)
        s.setFloatUniform("uTilt", tiltX, tiltY)
        s.setFloatUniform("uPress", press)
        s.setFloatUniform("uAdapt", adaptLuma)

        val bw = bmp.width
        val bh = bmp.height
        recordBlurNode(bmp, bw, bh)

        shaderNode.setPosition(0, 0, width, height)
        val nc = shaderNode.beginRecording(width, height)
        nc.scale(width.toFloat() / bw, height.toFloat() / bh)
        nc.drawRenderNode(blurNode)
        shaderNode.endRecording()
        shaderNode.setRenderEffect(ensureShaderEffect(s))

        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRenderNode(shaderNode)
        canvas.restoreToCount(save)
        return true
    }

    private fun ensureShaderEffect(s: RuntimeShader): RenderEffect {
        val cached = shaderEffect
        if (cached != null &&
            effectWidth == width &&
            effectHeight == height &&
            effectCorner == cornerPx &&
            effectBevel == bevelPx &&
            effectTiltX == tiltX &&
            effectTiltY == tiltY &&
            effectPress == press &&
            effectAdapt == adaptLuma
        ) {
            return cached
        }
        val rebuilt = RenderEffect.createRuntimeShaderEffect(s, "uBackdrop")
        shaderEffect = rebuilt
        effectWidth = width
        effectHeight = height
        effectCorner = cornerPx
        effectBevel = bevelPx
        effectTiltX = tiltX
        effectTiltY = tiltY
        effectPress = press
        effectAdapt = adaptLuma
        return rebuilt
    }

    private fun recordBlurNode(bmp: Bitmap, bw: Int, bh: Int) {
        val radius = blurRadiusDp * density / DOWNSCALE
        blurNode.setPosition(0, 0, bw, bh)
        val bc = blurNode.beginRecording(bw, bh)
        blurRect.set(0f, 0f, bw.toFloat(), bh.toFloat())
        bc.drawBitmap(bmp, null, blurRect, bitmapPaint)
        blurNode.endRecording()
        if (blurEffect == null || blurEffectRadius != radius) {
            blurEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            blurEffectRadius = radius
        }
        blurNode.setRenderEffect(blurEffect)
    }

    private fun drawFallback(canvas: Canvas, bmp: Bitmap) {
        val save = canvas.save()
        canvas.clipPath(clipPath)
        val inner = canvas.save()
        canvas.scale(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val ready = Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated &&
            runCatching { recordBlurNode(bmp, bmp.width, bmp.height) }.isSuccess
        if (ready) canvas.drawRenderNode(blurNode) else canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        canvas.restoreToCount(inner)
        canvas.drawRect(boundsF, tintPaint)
        canvas.restoreToCount(save)
    }

    private fun drawSpecular(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= 33 && !shaderBroken && shader != null) return
        if (abs(tiltX) <= 0.001f && abs(tiltY) <= 0.001f) return

        val save = canvas.save()
        canvas.clipPath(clipPath)

        val cx = width * (0.5f + tiltX * 0.42f)
        val cy = height * (0.35f + tiltY * 0.30f)
        specPaint.reset()
        specPaint.isAntiAlias = true
        specPaint.shader = LinearGradient(
            cx - width * 0.55f, cy - height,
            cx + width * 0.55f, cy + height,
            intArrayOf(Color.TRANSPARENT, 0x24FFFFFF, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(boundsF, specPaint)

        canvas.restoreToCount(save)
    }

    private fun refreshBackdropIfNeeded(): Bitmap? {
        val src = backdropSource ?: return backdrop
        if (width <= 0 || height <= 0) return backdrop
        if (capturingBackdrop) return backdrop

        val signature = backdropSignature(src)
        if (signature == lastSignature) return backdrop

        val bw = (width / DOWNSCALE).coerceAtLeast(8)
        val bh = (height / DOWNSCALE).coerceAtLeast(4)
        var bmp = backdrop
        if (bmp == null || bmp.width != bw || bmp.height != bh || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            backdrop = bmp
            captureCanvas = Canvas(bmp)
        }
        val c = captureCanvas ?: Canvas(bmp).also { captureCanvas = it }
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        getLocationInWindow(selfLoc)
        src.getLocationInWindow(srcLoc)
        val saveCount = c.save()
        c.clipRect(0, 0, bw, bh)
        c.scale(1f / DOWNSCALE, 1f / DOWNSCALE)
        c.translate(
            (srcLoc[0] - selfLoc[0] - src.scrollX).toFloat(),
            (srcLoc[1] - selfLoc[1] - src.scrollY).toFloat()
        )

        captureDepth++
        try {
            src.draw(c)
        } catch (_: Throwable) {
        } finally {
            captureDepth--
            c.restoreToCount(saveCount)
        }
        lastSignature = signature
        sampleAdaptiveLuma(bmp, signature)
        return bmp
    }

    private fun sampleAdaptiveLuma(bmp: Bitmap, signature: Int) {
        if (signature == adaptSampledSignature) return
        adaptSampledSignature = signature
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return

        val cx = 12
        val cy = 12
        val stepX = (bmp.width / cx).coerceAtLeast(1)
        val stepY = (bmp.height / cy).coerceAtLeast(1)
        var sum = 0f
        var weight = 0f
        var y = stepY / 2
        while (y < bmp.height) {
            var x = stepX / 2
            while (x < bmp.width) {
                val argb = bmp.getPixel(x, y)
                val a = (argb ushr 24) and 0xFF
                if (a > 8) {
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                    sum += luma * a
                    weight += a
                }
                x += stepX
            }
            y += stepY
        }
        if (weight <= 0f) return
        val measured = (sum / weight).coerceIn(0f, 1f)
        adaptLuma += (measured - adaptLuma) * ADAPT_LOWPASS
    }

    private fun ensureShader(): RuntimeShader? {
        if (Build.VERSION.SDK_INT < 33) return null
        if (shaderBroken) return null
        shader?.let { return it }
        return try {
            RuntimeShader(AGSL).also { shader = it }
        } catch (_: Throwable) {
            shaderBroken = true
            null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        trackPress(event)
        return super.onTouchEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        trackPress(event)
        return super.dispatchTouchEvent(event)
    }

    private fun trackPress(event: MotionEvent) {
        if (!pressEnabled || !glassEnabled) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animatePress(1f, PRESS_IN_MS, null)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animatePress(0f, PRESS_OUT_MS, DecelerateInterpolator())
            MotionEvent.ACTION_MOVE -> {
                val inside = event.x >= 0 && event.y >= 0 &&
                    event.x <= width && event.y <= height
                if (!inside && press > 0f) animatePress(0f, PRESS_OUT_MS, DecelerateInterpolator())
            }
        }
    }

    private fun animatePress(target: Float, durationMs: Long, easing: android.animation.TimeInterpolator?) {
        if (press == target) return
        pressAnimator?.cancel()
        if (shaderEffect == null && !shaderSupported) {
            press = target
            return
        }
        pressAnimator = ValueAnimator.ofFloat(press, target).apply {
            duration = durationMs
            interpolator = easing ?: OvershootInterpolator(1.6f)
            addUpdateListener {
                press = (it.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    private fun updateTiltSensor() {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = sm
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (glassEnabled && tiltEnabled && isAttachedToWindow && sensor != null && sm != null) {
            sm.registerListener(tiltListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sm?.unregisterListener(tiltListener)
            tiltX = 0f
            tiltY = 0f
        }
    }
}
