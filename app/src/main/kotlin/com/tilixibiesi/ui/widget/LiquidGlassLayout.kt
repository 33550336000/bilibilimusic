package com.tilixibiesi.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.Path
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
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 悬浮播放栏的「液态玻璃」底板（Android 原生 SDK 实现，无第三方渲染库）。
 *
 * 渲染管线，顺序固定：
 *  1. 背景捕获——把 [backdropSource]（本栏背后那一层，通常是翻页控件）降采样成 Bitmap，
 *     并用滚动签名做脏检查；签名没变就复用上一帧，不重跑后续任何阶段。
 *  2. 高斯模糊——位图画进内层 [RenderNode]，用 [RenderEffect.createBlurEffect] 模糊。
 *  3. AGSL 折射——内层 node 再画进外层 node，外层套
 *     [RenderEffect.createRuntimeShaderEffect]，于是着色器的输入就是「模糊后的背景」。
 *     着色器按 SDF 圆角矩形求表面法线，做半圆倒角高度场、Snell 折射、逐通道色散、
 *     边缘镜像回声、Fresnel 边缘高光。
 *  4. 色调与合成——白调在着色器里叠到折射结果上，倾斜高光画在最上层。
 *
 * 降级：API < 33 没有 AGSL，退化为「模糊 + 白调」；AGSL 编译或绘制抛异常时同样回落，
 * 任何情况下都不崩。关闭玻璃时直接画白底圆角，零额外开销。
 */
class LiquidGlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        /** 背景捕获降采样倍数：越小越清晰，也越贵。 */
        private const val DOWNSCALE = 5

        private const val CORNER_DP = 26f
        private const val BEVEL_DP = 18f

        /**
         * 本机能否真正跑折射：API 33+ 才有 AGSL RuntimeShader。
         *
         * 编译一次就缓存结果，失败（厂商实现缺失等）一律当作不可用，
         * 调用方据此直接退回普通背景，而不是画一层模糊糊上去。
         */
        private val shaderSupported: Boolean by lazy {
            Build.VERSION.SDK_INT >= 33 && runCatching { RuntimeShader(AGSL) }.isSuccess
        }

        /** 折射是否可用；不可用时调用方应保持原有背景实现。 */
        fun isAvailable(): Boolean = shaderSupported

        /**
         * AGSL 折射着色器。
         *
         * uBackdrop 由 createRuntimeShaderEffect 绑定为「模糊后的背景」。
         * 逐像素流程：SDF → 法线 → 倒角高度场 → 3D 法线 → Snell 折射 →
         * 三通道色散 → 边缘镜像回声 → 白调 → Fresnel 高光 → 倾斜高光。
         */
        val AGSL = """
uniform shader uBackdrop;
uniform float2 uSize;
uniform float uCorner;
uniform float uBevel;
uniform float2 uTilt;

// 圆角矩形的符号距离场
float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fragCoord) {
    float2 halfSize = uSize * 0.5;
    float2 p = fragCoord;

    // ---- 1. SDF 与梯度当表面法线 ----
    float d = sdRoundBox(p - halfSize, halfSize, uCorner);
    float e = 1.8;
    float2 g = float2(
        sdRoundBox(p + float2(e, 0.0) - halfSize, halfSize, uCorner) - d,
        sdRoundBox(p + float2(0.0, e) - halfSize, halfSize, uCorner) - d
    );
    float glen = length(g);
    float2 n = glen > 0.000001 ? g / glen : float2(0.0);

    // ---- 2. 倒角高度场 ----
    float bevel = max(uBevel, 1.0);
    float inward = clamp((-d) / bevel, 0.0, 1.0);  // 1 = 靠内，0 = 贴着边缘
    float edge = 1.0 - inward;                     // 1 = 贴着边缘
    float h = sqrt(max(0.0, 1.0 - edge * edge));   // 半圆剖面高度

    // ---- 3. 三维法线 ----
    float3 N = normalize(float3(n * edge * 1.15, h + 0.30));

    // ---- 4. Snell 折射：空气 n=1.0，玻璃 n=1.48 ----
    float3 I = float3(0.0, 0.0, -1.0);
    float eta = 1.0 / 1.48;
    float cosi = -dot(N, I);
    float k = 1.0 - eta * eta * (1.0 - cosi * cosi);
    float3 T = k < 0.0 ? reflect(I, N) : eta * I + (eta * cosi - sqrt(k)) * N;

    // ---- 5. 折射采样：越靠边偏折越大，形成边缘「透镜」 ----
    float2 dir = T.xy / max(abs(T.z), 0.20);
    float strength = bevel * 0.32 * edge * edge * edge;
    float2 base = p + dir * strength;

    // ---- 6. 轻微色散：三通道用不同偏移 ----
    half4 sR = uBackdrop.eval(base + dir * (strength * 0.11));
    half4 sG = uBackdrop.eval(base);
    half4 sB = uBackdrop.eval(base - dir * (strength * 0.11));
    float3 col = float3(float(sR.r), float(sG.g), float(sB.b));

    // ---- 7. 边缘镜像回声：贴着边的像素把外侧镜像样本混回来 ----
    half4 m = uBackdrop.eval(p - n * (bevel * 1.5) * edge);
    float3 echo = float3(float(m.r), float(m.g), float(m.b));
    col = mix(col, echo, smoothstep(0.62, 1.0, edge) * 0.20);

    // ---- 8. 饱和度提升：玻璃会把背后的颜色「提纯」，否则白调一压就灰 ----
    float luma = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = luma + (col - luma) * 1.35;

    // ---- 9. 白调：越靠内越白，形成浅色玻璃 ----
    col = mix(col, float3(1.0), mix(0.18, 0.34, smoothstep(0.0, 1.0, inward)));

    // ---- 10. Fresnel 边缘高光 + 最外沿亮环 ----
    float fres = pow(1.0 - clamp(dot(N, float3(0.0, 0.0, 1.0)), 0.0, 1.0), 3.0);
    col += float3(fres * 0.38);
    col += float3(smoothstep(0.93, 1.0, edge) * 0.26);

    // ---- 11. 倾斜高光：位置随陀螺仪移动 ----
    float2 hl = float2(uSize.x * (0.5 + uTilt.x * 0.42), uSize.y * (0.35 + uTilt.y * 0.30));
    float hd = length((p - hl) / float2(uSize.x * 0.55, uSize.y * 1.10));
    col += float3(smoothstep(0.85, 0.0, hd) * 0.16);

    // 轻微压暗，玻璃不至于过曝
    col *= 0.97;

    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""
    }

    /** 本栏背后要被折射的那一层。设置时会自动迁移脏检查监听。 */
    var backdropSource: View? = null
        set(value) {
            if (field === value) return
            detachPreDrawWatcher()
            field = value
            invalidateBackdrop()
            if (isAttachedToWindow) {
                value?.viewTreeObserver?.addOnPreDrawListener(preDrawWatcher)
            }
            invalidate()
        }

    /** 玻璃开关：关掉就是纯白底控制栏。 */
    var glassEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateBackdrop()
            updateTiltSensor()
            invalidate()
        }

    /** 模糊半径（dp）。 */
    var blurRadiusDp: Float = 12f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    /** 圆角半径（dp）：控制栏默认 [CORNER_DP]，设置页卡片用它对齐原来的 32dp 卡片圆角。 */
    var cornerRadiusDp: Float = CORNER_DP
        set(value) {
            if (field == value) return
            field = value
            cornerPx = value * density
            invalidateOutline()
            invalidate()
        }

    /**
     * 关闭时是否自己画不透明白底。
     *
     * 控制栏底下没有别的背景，关掉玻璃就得自己补一层白底；
     * 设置页卡片关掉玻璃要回到原来的卡片底色（由 View 自己的 background 画），
     * 所以那里把它设为 false。
     */
    var solidWhenDisabled: Boolean = true

    /**
     * 是否跟随陀螺仪画倾斜高光。
     *
     * 只有控制栏需要：一屏六张卡片各注册一个旋转矢量传感器，既费电，
     * 每帧还要各自重绘，收益却看不出来。
     */
    var tiltEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            updateTiltSensor()
        }

    private var cornerPx = CORNER_DP * density
    private val bevelPx = BEVEL_DP * density
    private val clipPath = Path()
    private val boundsF = RectF()

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 0x8C
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x1F000000
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var backdrop: Bitmap? = null
    private var lastSignature = Int.MIN_VALUE
    private var capturing = false

    private var shader: RuntimeShader? = null
    private var shaderBroken = false

    private val blurNode = RenderNode("liquidGlassBlur")
    private val shaderNode = RenderNode("liquidGlassShader")

    private var tiltX = 0f
    private var tiltY = 0f
    private var sensorManager: SensorManager? = null

    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val nx = (-event.values[1] * 4f).coerceIn(-1f, 1f)
            val ny = (-event.values[0] * 4f).coerceIn(-1f, 1f)
            if (abs(nx - tiltX) < 0.01f && abs(ny - tiltY) < 0.01f) return
            tiltX = nx
            tiltY = ny
            if (glassEnabled) invalidate()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    init {
        setWillNotDraw(false)
        // 底色由 onDraw 自己画，但 elevation 阴影需要 outline 才能有圆角轮廓
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
            }
        }
    }

    /** 让下一次绘制重新捕获背景（尺寸、主题或内容变化时调用）。 */
    fun invalidateBackdrop() {
        lastSignature = Int.MIN_VALUE
    }

    /**
     * 背后内容确实动过时调用：立刻丢弃签名并重画一帧。
     *
     * 和 [invalidateBackdrop] 的区别只是顺手 invalidate()。
     * 翻页、返回预览、页面切换都走这里，否则玻璃会一直显示「上一次静止时」的背景。
     */
    fun refreshBackdrop() {
        invalidateBackdrop()
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // 从 GONE 切回 VISIBLE 时背后多半已经换了一屏，必须重新捕获
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
        detachPreDrawWatcher()
        backdrop = null
        invalidateBackdrop()
    }

    /**
     * 背景层每次绘制前比一次签名：内容真的变了才让本栏失效。
     * 静止画面不会触发 preDraw，等于零开销；滚动时正好每帧都有机会更新。
     */
    private val preDrawWatcher = android.view.ViewTreeObserver.OnPreDrawListener {
        val src = backdropSource
        if (src != null && glassEnabled && backdropSignature(src) != lastSignature) invalidate()
        true
    }

    /**
     * 背景签名 = 来源子树的内容 + 自己相对窗口的位置。
     *
     * 只算来源子树是不够的：设置页的卡片在 ScrollView 里，滚动时来源（背景层）一动不动，
     * 变的是卡片自己的位置。不把自身位置算进去，滚过之后玻璃会一直折射「上一屏」的内容。
     */
    private fun backdropSignature(src: View): Int {
        val selfLoc = IntArray(2)
        getLocationInWindow(selfLoc)
        return scrollSignature(src) * 31 + selfLoc[0] * 31 + selfLoc[1]
    }

    private fun detachPreDrawWatcher() {
        val observer = backdropSource?.viewTreeObserver ?: return
        if (observer.isAlive) observer.removeOnPreDrawListener(preDrawWatcher)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        boundsF.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(boundsF, cornerPx, cornerPx, Path.Direction.CW)

        if (!glassEnabled) {
            drawSolid(canvas)
            return
        }

        // 没有 AGSL：控制栏退回「模糊 + 白调」，卡片直接让位给原来的卡片底色
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
        // 着色器中途坏掉：卡片回到原背景，控制栏继续用降级路径
        if (!drawn && !solidWhenDisabled) return
        if (!drawn) drawFallback(canvas, bmp)

        drawSpecular(canvas)
        canvas.drawRoundRect(boundsF, cornerPx, cornerPx, borderPaint)
    }

    /** 纯白底兜底：控制栏关闭玻璃/不可用时的样子。 */
    private fun drawSolid(canvas: Canvas) {
        if (!solidWhenDisabled) return
        canvas.drawRoundRect(boundsF, cornerPx, cornerPx, whitePaint)
        canvas.drawRoundRect(boundsF, cornerPx, cornerPx, borderPaint)
    }

    /** 阶段 2+3+4：模糊 node 作为 AGSL 的输入，外套折射 node 一次性画出来。 */
    private fun drawShaderPass(canvas: Canvas, bmp: Bitmap): Boolean {
        val s = ensureShader() ?: return false

        s.setFloatUniform("uSize", width.toFloat(), height.toFloat())
        s.setFloatUniform("uCorner", cornerPx)
        s.setFloatUniform("uBevel", bevelPx)
        s.setFloatUniform("uTilt", tiltX, tiltY)

        val radius = blurRadiusDp * density
        blurNode.setPosition(0, 0, width, height)
        val bc = blurNode.beginRecording(width, height)
        bc.drawBitmap(bmp, null, boundsF, bitmapPaint)
        blurNode.endRecording()
        blurNode.setRenderEffect(
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        )

        shaderNode.setPosition(0, 0, width, height)
        val nc = shaderNode.beginRecording(width, height)
        nc.drawRenderNode(blurNode)
        shaderNode.endRecording()
        shaderNode.setRenderEffect(
            RenderEffect.createRuntimeShaderEffect(s, "uBackdrop")
        )

        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRenderNode(shaderNode)
        canvas.restoreToCount(save)
        return true
    }

    /** 降级路径：模糊 + 白调（API 30 没有 RenderEffect，用降采样位图放大本身柔化）。 */
    private fun drawFallback(canvas: Canvas, bmp: Bitmap) {
        val save = canvas.save()
        canvas.clipPath(clipPath)
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated) {
            val radius = blurRadiusDp * density
            blurNode.setPosition(0, 0, width, height)
            val bc = blurNode.beginRecording(width, height)
            bc.drawBitmap(bmp, null, boundsF, bitmapPaint)
            blurNode.endRecording()
            blurNode.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
            canvas.drawRenderNode(blurNode)
        } else {
            canvas.drawBitmap(bmp, null, boundsF, bitmapPaint)
        }
        canvas.drawRect(boundsF, tintPaint)
        canvas.restoreToCount(save)
    }

    /** 倾斜镜面高光（仅降级路径需要，着色器路径已在片元里算过）。 */
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

    // ---------- 背景捕获 ----------

    private fun refreshBackdropIfNeeded(): Bitmap? {
        val src = backdropSource ?: return backdrop
        if (width <= 0 || height <= 0) return backdrop
        if (capturing) return backdrop

        val signature = backdropSignature(src)
        if (signature == lastSignature) return backdrop

        val bw = (width / DOWNSCALE).coerceAtLeast(8)
        val bh = (height / DOWNSCALE).coerceAtLeast(4)
        var bmp = backdrop
        if (bmp == null || bmp.width != bw || bmp.height != bh || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            backdrop = bmp
        }
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        // 目标：位图坐标 = (来源坐标 - 本栏原点) / DOWNSCALE。
        // 画布变换左乘累积，所以顺序是「先 scale 后 translate」：
        // CTM = S · T(-origin)，点 p 落点 = (p - origin) / DOWNSCALE，正好是想要的。
        val selfLoc = IntArray(2)
        val srcLoc = IntArray(2)
        getLocationInWindow(selfLoc)
        src.getLocationInWindow(srcLoc)
        c.scale(1f / DOWNSCALE, 1f / DOWNSCALE)
        // View.draw(Canvas) 不应用自身 scrollX/scrollY——那一步是父 View 在 drawChild 里做的。
        // 翻页控件停在别的页时 scrollX 等于几千像素，不补偿就会把子页整块画到画布外，
        // 抓到的图里只剩背景。这里把来源层自身的滚动量减掉。
        c.translate(
            (srcLoc[0] - selfLoc[0] - src.scrollX).toFloat(),
            (srcLoc[1] - selfLoc[1] - src.scrollY).toFloat()
        )
        capturing = true
        try {
            src.draw(c)
        } catch (_: Throwable) {
            // 捕获失败就沿用上一帧，绝不影响主流程
        } finally {
            capturing = false
        }
        lastSignature = signature
        return bmp
    }

    /**
     * 便宜的内容签名：来源子树各层的尺寸、滚动位置和子项位置。
     *
     * 带上 child.left/top 是因为列表滚动时 View.scrollY 不变（位移在子项上），
     * 只看 scrollY 会漏掉整屏内容的移动。
     */
    private fun scrollSignature(v: View): Int {
        var budget = 96
        return scrollSignature(v, budget)
    }

    private fun scrollSignature(v: View, budget: Int): Int {
        var h = v.width * 31 + v.height
        h = h * 31 + v.scrollX
        h = h * 31 + v.scrollY
        // ListView / GridView：滚动时子项是复用的，left/top 变化不足以反映内容变化
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

    // ---------- 着色器 ----------

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

    // ---------- 交互 ----------

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
