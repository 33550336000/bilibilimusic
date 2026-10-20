package com.tilixibiesi.ui.widget

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
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 「液态玻璃」底板：把背后那一层离屏抓成位图，模糊后再用 AGSL 做折射合成。
 *
 * 抓图区域严格等于本栏自己在窗口里的矩形，位图与本 View 一一对应（只差一个降采样倍数），
 * 因此着色器里的采样坐标不需要任何偏移换算 —— 换算越少，滚动/翻页时越不可能错位。
 */
class LiquidGlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        /** 抓图降采样倍数：位图放大回原尺寸时带来约 [DOWNSCALE] px 软化，细节还在。 */
        private const val DOWNSCALE = 3

        private const val CORNER_DP = 26f
        private const val BEVEL_DP = 18f

        /**
         * 倾斜低通系数与死区：原始传感器噪声直接驱动高光会以 50Hz 震颤。
         *
         * 静止时陀螺仪读数一直在小幅漂，系数 0.18 让滤波后的值每帧都还能动一小步，
         * 而 0.004 的死区比这一步还小、拦不住，于是每次传感器回调都 invalidate 一次。
         * 设置页同时有多块玻璃，就变成每帧各自重抓、重模糊、重算折射，看着一卡一卡。
         * 系数压到 0.08 让抖动单步变小，死区抬到 0.02 把它整段吃掉：静止时彻底不重绘。
         */
        private const val TILT_SMOOTHING = 0.08f
        private const val TILT_DEADZONE = 0.02f

        /**
         * 抓图进行中的层数。
         *
         * 抓图是把来源子树整棵在软件画布上重画一遍，而本类通常就长在那棵子树里
         * （悬浮栏的来源层里就有设置页的玻璃卡片）。不拦住的话，玻璃会把「上一帧的自己」
         * 画进新背景，逐帧自我叠加。这里用一个计数让内层玻璃在抓图期间改画平面替身。
         */
        private var captureDepth = 0

        /** 是否正在抓图。同包的翻页控件据此在抓图期间跳过 [View.computeScroll]。 */
        internal val capturingBackdrop: Boolean get() = captureDepth > 0

        private val shaderSupported: Boolean by lazy {
            Build.VERSION.SDK_INT >= 33 && runCatching { RuntimeShader(AGSL) }.isSuccess
        }

        /** 本机能否真正跑折射；不可用时调用方保持原有背景实现。 */
        fun isAvailable(): Boolean = shaderSupported

        /**
         * 折射着色器，输入 uBackdrop = 模糊后的背景。
         *
         * iOS 那套液态玻璃靠三件事撑起来：极薄的白膜、贴着边缘的一圈受光棱线、
         * 边缘处明显的透镜折射。这里按这个顺序做，并且不做任何暗边。
         */
        val AGSL = """
uniform shader uBackdrop;
uniform float2 uSize;
uniform float uCorner;
uniform float uBevel;
uniform float2 uTilt;

// 圆角矩形符号距离场
float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// 采样坐标夹在玻璃自身范围内：折射会把采样点推出框外，框外没有像素，
// 混进来就是一条发白/发黑的边。夹住等价于把最外沿像素拉出去。
float2 clampSample(float2 q) {
    return clamp(q, float2(0.5, 0.5), uSize - float2(0.5, 0.5));
}

half4 main(float2 fragCoord) {
    float2 halfSize = uSize * 0.5;
    float2 p = fragCoord;

    // ---- 1. SDF 与梯度当表面法线 ----
    float d = sdRoundBox(p - halfSize, halfSize, uCorner);
    float e = 1.5;
    float2 g = float2(
        sdRoundBox(p + float2(e, 0.0) - halfSize, halfSize, uCorner) - d,
        sdRoundBox(p + float2(0.0, e) - halfSize, halfSize, uCorner) - d
    );
    float glen = length(g);
    float2 n = glen > 0.000001 ? g / glen : float2(0.0);

    // ---- 2. 倒角高度场（半圆剖面）----
    float bevel = max(uBevel, 1.0);
    float inward = clamp((-d) / bevel, 0.0, 1.0);
    float edge = 1.0 - inward;
    float h = sqrt(max(0.0, 1.0 - edge * edge));

    // ---- 3. 三维法线 ----
    float3 N = normalize(float3(n * edge * 1.20, h + 0.28));

    // ---- 4. Snell 折射：空气 1.0 -> 玻璃 1.48 ----
    float3 I = float3(0.0, 0.0, -1.0);
    float eta = 1.0 / 1.48;
    float cosi = -dot(N, I);
    float k = 1.0 - eta * eta * (1.0 - cosi * cosi);
    float3 T = k < 0.0 ? reflect(I, N) : eta * I + (eta * cosi - sqrt(k)) * N;

    // ---- 5. 折射采样：越靠边偏折越大，形成边缘透镜 ----
    float2 dir = T.xy / max(abs(T.z), 0.20);
    float strength = bevel * 0.34 * edge * edge * edge;
    float2 base = p + dir * strength;

    // ---- 6. 轻微色散：三通道错开一点，边缘才像真的玻璃 ----
    half4 sR = uBackdrop.eval(clampSample(base + dir * (strength * 0.10)));
    half4 sG = uBackdrop.eval(clampSample(base));
    half4 sB = uBackdrop.eval(clampSample(base - dir * (strength * 0.10)));

    // 输入是预乘 alpha 的：位图没覆盖到的地方是 (0,0,0,0)，直接用会发黑。
    // 补一层浅色，等价于「这里确实没有内容」时的底色 —— 既不发黑也不留纯白块。
    float3 neutral = float3(0.86, 0.87, 0.90);
    float a = clamp(float(sG.a), 0.0, 1.0);
    float3 col = float3(float(sR.r), float(sG.g), float(sB.b)) + (1.0 - a) * neutral;

    // ---- 7. 边缘镜像回声：贴边像素把外侧镜像样本混回来，玻璃才有厚度 ----
    half4 m = uBackdrop.eval(clampSample(p - n * (bevel * 1.35) * edge));
    float ma = clamp(float(m.a), 0.0, 1.0);
    float3 echo = float3(float(m.r), float(m.g), float(m.b)) + (1.0 - ma) * neutral;
    col = mix(col, echo, smoothstep(0.72, 1.0, edge) * 0.22);

    // ---- 8. 提纯：玻璃会把背后的颜色提亮提纯，压灰了就不像玻璃 ----
    float luma = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = luma + (col - luma) * 1.50;
    col = (col - 0.5) * 1.06 + 0.5;

    // ---- 9. 白膜：整块只蒙极薄一层，越靠边略厚 ----
    // 之前 0.045~0.16 铺在整块上，卡片就已经偏白了；这里压到 0.02~0.075。
    col = mix(col, float3(1.0), mix(0.02, 0.075, smoothstep(0.72, 1.0, edge)));

    // ---- 10. 边缘受光：只有贴近棱线的一圈，不做整块泛白 ----
    // edge 是以 uBevel(18dp) 为宽度的归一化量，smoothstep 的区间必须贴着 1.0 收窄，
    // 否则这圈高光会摊成 55px 宽的白晕，整块玻璃看着就是一层白雾。
    // 边缘光也按「像素级」宽度另算（rim），与倒角尺度解耦。
    float rimW = clamp(2.0 / max(bevel, 1.0), 0.0, 0.12);
    float rim = smoothstep(1.0 - rimW * 3.0, 1.0, edge);
    float fres = pow(1.0 - clamp(dot(N, float3(0.0, 0.0, 1.0)), 0.0, 1.0), 3.0);
    float topBias = mix(0.85, 1.20, 1.0 - clamp(p.y / uSize.y, 0.0, 1.0));
    col += float3(fres * 0.16 * topBias);
    // 最外沿一道细棱线：只占最外一小圈
    col += float3(rim * 0.30 * topBias);

    // ---- 11. 倾斜高光：跟陀螺仪走，幅度压得很低 ----
    float2 hl = float2(uSize.x * (0.5 + uTilt.x * 0.30), uSize.y * (0.30 + uTilt.y * 0.22));
    float hd = length((p - hl) / float2(uSize.x * 0.55, uSize.y * 1.10));
    col += float3(smoothstep(0.85, 0.0, hd) * 0.035);

    // ---- 12. 顶沿亮边：只留顶部一条窄亮面，整块斜纹会读成塑料膜，去掉 ----
    float topRim = (1.0 - smoothstep(0.0, 0.045, p.y / uSize.y)) * smoothstep(0.0, 0.30, inward);
    col += float3(topRim * 0.05);

    // ---- 13. 细颗粒：打散模糊后的塑料感 ----
    float2 gp = p * 1.35;
    float grain = fract(sin(dot(gp, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    col += float3(grain * 0.016);

    col *= 0.99;

    // ---- 14. 形状遮罩：框外一律透明 ----
    // SDF 为正在外面时 edge=1，上面前几项会把颜色直接推到饱和白。只要节点尺寸和
    // uSize 有一点出入（或者圆角裁切没盖住），那一片就会变成纯白块。用 SDF 本身当
    // alpha，框外什么都不画，白块就无从产生。
    float mask = 1.0 - smoothstep(-1.5, 0.5, d);
    return half4(half3(clamp(col, 0.0, 1.0) * mask), mask);
}
"""
    }

    /** 本栏背后要被折射的那一层。 */
    var backdropSource: View? = null
        set(value) {
            if (field === value) return
            detachWatcher()
            field = value
            invalidateBackdrop()
            if (isAttachedToWindow) value?.viewTreeObserver?.addOnPreDrawListener(preDrawWatcher)
            invalidate()
        }

    /** 玻璃开关：关掉就是纯白底（或让位给 View 自己的 background）。 */
    var glassEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateBackdrop()
            updateTiltSensor()
            invalidate()
        }

    /** 模糊半径（dp）。越大越像毛玻璃；iOS 那种要透出轮廓，不宜过大。 */
    var blurRadiusDp: Float = 6f
        set(value) {
            if (field == value) return
            field = value
            blurEffect = null
            invalidate()
        }

    /** 圆角半径（dp）：控制栏默认 [CORNER_DP]，设置页卡片用它对齐卡片底色。 */
    var cornerRadiusDp: Float = CORNER_DP
        set(value) {
            if (field == value) return
            field = value
            cornerPx = value * density
            invalidateOutline()
            invalidate()
        }

    /** 关闭时是否自己画不透明白底。设置页卡片要回到自己的 background，那里设 false。 */
    var solidWhenDisabled: Boolean = true

    /** 是否跟随陀螺仪画倾斜高光。六张卡片各注册一个传感器既费电、收益也看不出。 */
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
    /** 玻璃状态下的边线：白色低透明棱线（黑边会被读成玻璃在吸光）。 */
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x59FFFFFF
    }
    /** 关闭玻璃的纯白底仍用深色描边，否则白底白边看不出轮廓。 */
    private val solidBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x1F000000
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 被抓图时画的平面替身：软件画布跑不了 RenderNode，但也不能什么都不画。 */
    /** 最后一档兜底：连一帧背景都没抓到过时的平板替身，透明度压低到不致于发白。 */
    private val captureProxyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33EEEDF4
    }

    /** 当前背景位图，坐标与本 View 一一对应（已降采样）。 */
    private var backdrop: Bitmap? = null
    private var captureCanvas: Canvas? = null
    /** 已画进位图的签名。与 [lastPreDrawSignature] 必须分开，否则会漏掉整整一帧。 */
    private var lastSignature = Int.MIN_VALUE
    private var lastPreDrawSignature = Int.MIN_VALUE

    private var shader: RuntimeShader? = null
    private var shaderBroken = false
    private var blurEffect: RenderEffect? = null
    private var blurEffectRadius = Float.NaN
    private var shaderEffect: RenderEffect? = null

    /**
     * [shaderEffect] 是按哪一组 uniform 建出来的。
     *
     * RenderEffect 不是「引用着色器、每帧现取 uniform」，而是创建那一刻就把 uniform 拷走了：
     * Java 侧 `RenderEffect.createRuntimeShaderEffect` 交给 JNI 的是
     * `RuntimeShader.getNativeShaderBuilder()`，hwui 再调 `SkImageFilters::RuntimeShader(*builder, …)`，
     * Skia 在 `SkRuntimeImageFilter` 里存的是 `SkRuntimeShaderBuilder` 的**副本**
     * （见 SkRuntimeImageFilter.cpp 的 `fRuntimeEffectBuilder(builder)`）。
     * 也就是说：effect 建好之后再 `setFloatUniform`，对已经建出来的滤镜完全没有作用。
     *
     * 旧代码只在第一次绘制时建一次 effect，uSize 就永远停在那一刻的尺寸上：
     * 竖屏启动 → 转横屏，SDF 遮罩仍按竖屏宽度算，玻璃只画到旧宽度那一条，右边整块透明；
     * 反过来横屏启动 → 竖屏，遮罩比 View 大，被 View 自身边界裁掉，看起来反而是正常的。
     * 这就是「竖屏进入应用、设置页转横屏玻璃异常，横屏进入应用再竖屏却正常」的原因。
     * 尺寸/圆角/倾斜任何一项变了都必须重建 effect。
     */
    private var effectWidth = -1
    private var effectHeight = -1
    private var effectCorner = Float.NaN
    private var effectBevel = Float.NaN
    private var effectTiltX = Float.NaN
    private var effectTiltY = Float.NaN

    private val blurNode = RenderNode("liquidGlassBlur")
    private val shaderNode = RenderNode("liquidGlassShader")
    private val blurRect = RectF()

    private val selfLoc = IntArray(2)
    private val srcLoc = IntArray(2)

    private var tiltX = 0f
    private var tiltY = 0f
    private var sensorManager: SensorManager? = null

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
            // 一个传感器回调一次重绘太碎：并进下一个 vsync，同一帧的多块玻璃合成一次绘制
            if (glassEnabled) postInvalidateOnAnimation()
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

    /** 让下一次绘制重新抓背景（尺寸、主题或内容变化时调用）。 */
    fun invalidateBackdrop() {
        lastSignature = Int.MIN_VALUE
        lastPreDrawSignature = Int.MIN_VALUE
    }

    /** 背后内容确实动过时调用：丢弃签名并立刻重画一帧。 */
    fun refreshBackdrop() {
        invalidateBackdrop()
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // 从 GONE 切回 VISIBLE 时背后多半已经换了一屏，必须重新抓图
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
        detachWatcher()
        backdrop = null
        captureCanvas = null
        invalidateBackdrop()
    }

    /**
     * 来源层每次绘制前比一次签名。
     *
     * 比的是「上一次 preDraw 看到的签名」，不是「已经画进位图的签名」：滚动是在绘制阶段推进的
     * （Scroller 在 draw 里才 scrollTo），preDraw 早于 draw，拿它跟已绘制的签名比必然比中，
     * 玻璃就会隔帧才更新一次，看着像滞后。
     */
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

    /**
     * 签名 = 来源子树内容 + 本栏自身窗口位置。
     *
     * 自身位置必须算：设置页卡片在 ScrollView 里滚动时来源层不动、动的是卡片自己。
     */
    private fun backdropSignature(src: View): Int {
        getLocationInWindow(selfLoc)
        return scrollSignature(src, 96) * 31 + selfLoc[0] * 31 + selfLoc[1]
    }

    /**
     * 便宜的内容签名：尺寸、滚动位置、子项位置。
     *
     * 带上 child.left/top 是因为列表滚动时 View.scrollY 不变（位移在子项上）。
     */
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

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        boundsF.set(0f, 0f, width.toFloat(), height.toFloat())
        // 尺寸和圆角不变时不重建路径：滚动期间这两者都不变
        if (clipWidth != width || clipHeight != height || clipCorner != cornerPx) {
            clipPath.reset()
            clipPath.addRoundRect(boundsF, cornerPx, cornerPx, Path.Direction.CW)
            clipWidth = width
            clipHeight = height
            clipCorner = cornerPx
        }

        // 正被别的玻璃抓图：画平面替身。什么都不画会让外层玻璃把这里当透明，
        // 一帧有一帧没有，滚起来就是抖动。
        if (capturingBackdrop) {
            drawCaptureProxy(canvas)
            return
        }

        if (!glassEnabled) {
            drawSolid(canvas)
            return
        }

        // 没有 AGSL：控制栏退回「模糊 + 白调」，卡片让位给原来的卡片底色
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

    /** 纯白底兜底：关闭玻璃或不可用时的样子。 */
    private fun drawSolid(canvas: Canvas) {
        if (!solidWhenDisabled) return
        canvas.drawRoundRect(boundsF, cornerPx, cornerPx, whitePaint)
        canvas.drawRoundRect(boundsF, cornerPx, cornerPx, solidBorderPaint)
    }

    /**
     * 被抓图时画在软件画布上的替身。
     *
     * 软件画布画不了 RenderNode，所以不能走真折射，但替身的颜色必须与真玻璃接近：
     * 之前这里一律涂一块高不透明的浅灰白，外层玻璃抓到的背景里卡片就成了一块白板，
     * 模糊折射之后铺成一片白雾 —— 页面右侧那块「莫名的白」主要就是它叠出来的。
     * 现在直接把上一次抓到的背景位图放大贴上去（它本来就是降采样图，放大自带柔化，
     * 颜色与真实内容一致）；只有一帧都还没抓到过时才退回淡色替身。
     */
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

    /** 模糊 node 按位图分辨率录制，外套折射 node 一次性画出来。 */
    private fun drawShaderPass(canvas: Canvas, bmp: Bitmap): Boolean {
        val s = ensureShader() ?: return false

        s.setFloatUniform("uSize", width.toFloat(), height.toFloat())
        s.setFloatUniform("uCorner", cornerPx)
        s.setFloatUniform("uBevel", bevelPx)
        s.setFloatUniform("uTilt", tiltX, tiltY)

        val bw = bmp.width
        val bh = bmp.height
        recordBlurNode(bmp, bw, bh)

        shaderNode.setPosition(0, 0, width, height)
        val nc = shaderNode.beginRecording(width, height)
        // 放大回 View 坐标：着色器里的 p 是按 View 尺寸算的
        nc.scale(width.toFloat() / bw, height.toFloat() / bh)
        nc.drawRenderNode(blurNode)
        shaderNode.endRecording()
        // uniform 必须在这之前设好；effect 会把这些值拷走，所以换了尺寸/倾斜就得重建
        shaderNode.setRenderEffect(ensureShaderEffect(s))

        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRenderNode(shaderNode)
        canvas.restoreToCount(save)
        return true
    }

    /**
     * 按当前 uniform 取折射用的 [RenderEffect]，取值变了就重建。
     *
     * RenderEffect 在创建时把 uniform 拷贝进 Skia 的滤镜（见 [shaderEffect] 上方注释），
     * 之后 [RuntimeShader.setFloatUniform] 改的是 RuntimeShader 自己的 builder，滤镜读不到。
     * 因此这里用一份「建 effect 时的 uniform 快照」做比较：只是传感器抖动/同一帧重复绘制时
     * 直接复用，尺寸或倾斜真正变了才付一次建滤镜的代价。
     */
    private fun ensureShaderEffect(s: RuntimeShader): RenderEffect {
        val cached = shaderEffect
        if (cached != null &&
            effectWidth == width &&
            effectHeight == height &&
            effectCorner == cornerPx &&
            effectBevel == bevelPx &&
            effectTiltX == tiltX &&
            effectTiltY == tiltY
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
        return rebuilt
    }

    /** 把位图按自身分辨率录进 [blurNode]：后面本来就要放大，在高分辨率上模糊纯属浪费。 */
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

    /** 降级路径：模糊 + 白调（API 30 没有 RenderEffect，靠位图放大本身柔化）。 */
    private fun drawFallback(canvas: Canvas, bmp: Bitmap) {
        val save = canvas.save()
        canvas.clipPath(clipPath)
        val inner = canvas.save()
        canvas.scale(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        // 模糊效果建不出来（厂商实现缺失）时直接放大位图，绝不让异常穿过 onDraw
        val ready = Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated &&
            runCatching { recordBlurNode(bmp, bmp.width, bmp.height) }.isSuccess
        if (ready) canvas.drawRenderNode(blurNode) else canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        canvas.restoreToCount(inner)
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

    /**
     * 需要时重抓背景，返回可用位图。
     *
     * 抓的是「本栏矩形背后的那块屏幕」：位图坐标 = 本栏局部坐标 / DOWNSCALE，
     * 所以着色器直接用 fragCoord 采样就是对齐的，没有任何随时间漂移的偏移量。
     * 抓图时画布额外裁到本栏范围内，来源子树里落在范围外的内容会被 Skia 直接剔除。
     */
    private fun refreshBackdropIfNeeded(): Bitmap? {
        val src = backdropSource ?: return backdrop
        if (width <= 0 || height <= 0) return backdrop
        // 抓图过程中（可能正是别层玻璃在抓本层）绝不重入
        if (capturingBackdrop) return backdrop

        val signature = backdropSignature(src)
        if (signature == lastSignature) return backdrop

        val bw = (width / DOWNSCALE).coerceAtLeast(8)
        val bh = (height / DOWNSCALE).coerceAtLeast(4)
        var bmp = backdrop
        if (bmp == null || bmp.width != bw || bmp.height != bh || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            backdrop = bmp
            // Canvas 会抓住位图的像素缓冲，位图换了画布必须跟着换
            captureCanvas = Canvas(bmp)
        }
        val c = captureCanvas ?: Canvas(bmp).also { captureCanvas = it }
        // 画布是复用的：必须清空，否则来源层这一帧没画到的地方会留着上一帧的像素
        // （SRC_OVER 画透明色并不会擦除，得用 CLEAR）
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        getLocationInWindow(selfLoc)
        src.getLocationInWindow(srcLoc)
        // 画布是复用的，必须用 save/restore 夹住变换，否则 scale/translate 会逐帧累积
        val saveCount = c.save()
        // 裁到本栏矩形（此刻还在位图坐标系里）：来源子树里落在范围外的内容直接被剔除，
        // 抓出来的像素也一定覆盖着色器可能采样到的每一个坐标。
        c.clipRect(0, 0, bw, bh)
        c.scale(1f / DOWNSCALE, 1f / DOWNSCALE)
        // View.draw(Canvas) 不应用自身 scrollX/scrollY（那步由父 View 在 drawChild 里做），
        // 翻页控件停在别页时 scrollX 是几千像素，不补偿会把内容整块画到画布外。
        c.translate(
            (srcLoc[0] - selfLoc[0] - src.scrollX).toFloat(),
            (srcLoc[1] - selfLoc[1] - src.scrollY).toFloat()
        )

        captureDepth++
        try {
            src.draw(c)
        } catch (_: Throwable) {
            // 抓图失败沿用上一帧，绝不影响主流程
        } finally {
            captureDepth--
            c.restoreToCount(saveCount)
        }
        // 成败都记签名：永久抛异常的子树立重复抓图只会每帧白烧一次
        lastSignature = signature
        return bmp
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
