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
         * 按压动画时长。
         *
         * iOS 的手感是「按下去几乎瞬间亮、松手略慢回落」。按下 90ms 够快，
         * 快到不会让人觉得卡；回落 160ms 有明显阻尼，像材质在回弹。
         */
        private const val PRESS_IN_MS = 90L
        private const val PRESS_OUT_MS = 160L

        /**
         * 背景亮度低通系数。
         *
         * 抓图是每帧都可能重来的，亮度必须平滑跟随；0.35 既跟得上换页，
         * 又不会因为背景里飘过一片深色内容就把整块玻璃的膜厚跳变。
         */
        private const val ADAPT_LOWPASS = 0.35f

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
         * iOS 那套液态玻璃靠四件事撑起来：极薄的白膜、贴着边缘的一圈受光棱线、
         * 边缘处明显的透镜折射，以及「玻璃是活的」——按下时从内部发亮并瞬时鼓起。
         * 这里按这个顺序做，并且不做任何暗边。
         *
         * uAdapt 是背景平均亮度（0=全黑,1=全白）：白膜厚度随它反向变化，
         * 亮底上更薄、暗底上更厚，否则亮底会把玻璃整块吃掉、暗底又看不出轮廓。
         */
        val AGSL = """
uniform shader uBackdrop;
uniform float2 uSize;
uniform float uCorner;
uniform float uBevel;
uniform float2 uTilt;
uniform float uPress;
uniform float uAdapt;

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
    // 按下时玻璃「鼓起来」：倒角变窄但更陡，于是边缘更亮、折射带更集中。
    // 这是 iOS 按压反馈里读起来最像「玻璃被压弯」的那一部分。
    bevel *= mix(1.0, 0.72, uPress);
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

    // ---- 5. 折射采样：位移贴图式的「先收缩、再边缘外扩」----
    //
    // iOS 的做法不是把整块画面均匀往外推，而是用一张二维位移贴图：
    // 先把玻璃内部的内容整体收缩，再让最外沿一圈向外拉伸，两者在中间平滑接上。
    // 这样画面在玻璃里是连续接上的，没有一圈被硬拽出来的环带。
    //
    // 这里分两部分叠加：
    //   a) 全局收缩   —— 让整块玻璃里的内容略微变小，制造「画面被吞进玻璃里」的厚度感
    //   b) 边缘外扩   —— 只在最外一圈生效（edge^3），是真正的透镜弯光
    // 两者用同一个 dir（折射方向），因此过渡是连续的。
    float2 dir = T.xy / max(abs(T.z), 0.20);

    // a) 全局收缩：以中心为原点朝内收，proportional 让各方向收缩量一致，
    //    不会在长条形的控制栏上被拉成椭圆（这一步就是「统一缩放因子」的等价物）。
    float shrinkBase = bevel * mix(0.055, 0.075, uPress);
    float2 centered = (p - halfSize) / halfSize;   // -1..1
    float2 shrinkVec = -centered * shrinkBase;

    // b) 边缘外扩：只有最外沿真的弯光
    float strength = bevel * mix(0.34, 0.52, uPress) * edge * edge * edge;

    float2 base = p + shrinkVec + dir * strength;

    // ---- 6. 轻微色散：三通道错开一点，边缘才像真的玻璃 ----
    // 色散强度也跟折射同步放大，否则按压时只有明暗变化、没有光学变化
    float disp = strength * mix(0.10, 0.16, uPress);
    half4 sR = uBackdrop.eval(clampSample(base + dir * disp));
    half4 sG = uBackdrop.eval(clampSample(base));
    half4 sB = uBackdrop.eval(clampSample(base - dir * disp));

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
    // 再按背景亮度自适应：亮底（uAdapt→1）压薄，暗底（uAdapt→0）加厚。
    // 不这么做的话，浅色壁纸上玻璃会整块泛白、深色壁纸上又完全看不出边界。
    float filmScale = mix(1.55, 0.55, uAdapt);
    float film = mix(0.02, 0.075, smoothstep(0.72, 1.0, edge)) * filmScale;
    // 按下时白膜略增：玻璃被「点亮」了一点点，但幅度必须小，否则会读成变白
    film *= mix(1.0, 1.35, uPress);
    col = mix(col, float3(1.0), clamp(film, 0.0, 1.0));

    // ---- 10. 边缘受光：只有贴近棱线的一圈，不做整块泛白 ----
    //
    // 真实玻璃的棱线不是「一圈均匀的白」，而是随受光方向有强弱：光从左上来，
    // 左上和中上的棱最亮，右下最暗。iOS 那圈高光就是有明显方向感的。
    // 这里用两层：
    //   rim  —— 极细的一道外层亮线（像素级宽度，与倒角尺度解耦）
    //   fres —— 菲涅尔衰减，让棱线不必靠加粗也能被看见
    float rimW = clamp(2.0 / max(bevel, 1.0), 0.0, 0.12);
    float rim = smoothstep(1.0 - rimW * 3.0, 1.0, edge);
    float fres = pow(1.0 - clamp(dot(N, float3(0.0, 0.0, 1.0)), 0.0, 1.0), 3.0);

    // 受光方向：光源取左上方（与倾斜高光同一套世界假设），
    // 用它在边缘法线上的投影做方向加权，右下自然压暗。
    float2 lightDir = normalize(float2(-0.55, -0.83));
    float facing = clamp(dot(n, lightDir), -1.0, 1.0);
    // 从 0.35 到 1.0：背光侧不完全消失，否则转角处会出现突变
    float dirWeight = mix(0.35, 1.0, (facing + 1.0) * 0.5);

    // 顶部本身就更容易接光，保留一点垂直偏置（但不做整块渐变，那会读成塑料膜）
    float topBias = mix(0.90, 1.15, 1.0 - clamp(p.y / uSize.y, 0.0, 1.0));
    // 边缘受光同样要压暗底、提亮底：暗底上弱一点免得太跳，亮底上强一点才有轮廓
    float rimScale = mix(1.15, 0.85, uAdapt) * mix(1.0, 1.5, uPress)
        * topBias * dirWeight;
    col += float3(fres * 0.16 * rimScale);
    // 最外沿一道细棱线：只占最外一小圈
    col += float3(rim * 0.30 * rimScale);

    // ---- 11. 倾斜高光：跟陀螺仪走，幅度压得很低 ----
    float2 hl = float2(uSize.x * (0.5 + uTilt.x * 0.30), uSize.y * (0.30 + uTilt.y * 0.22));
    float hd = length((p - hl) / float2(uSize.x * 0.55, uSize.y * 1.10));
    col += float3(smoothstep(0.85, 0.0, hd) * 0.035);

    // ---- 11b. 按压内部发光 ----
    //
    // iOS 按下时玻璃是「从内部亮起来」的：不是外面罩一层白，而是材质本身被点亮。
    // 所以这里用两层叠加：一层是柔和的内部辉光（几乎铺满、中心稍弱），
    // 一层是贴着边缘更亮的一圈。
    //
    // 写成无分支形式（乘 uPress）而不是 if：片元着色器里 uPress 是逐像素一致的，
    // 但分支在部分 GPU 上仍会打断向量化；这里本来就是 0 时结果为 0，不必用 if。
    // 中心故意压得比边缘暗，避免整块读成一个白块。
    float2 c = (p - halfSize) / halfSize;
    float core = 1.0 - smoothstep(0.0, 1.25, length(c));
    // 亮底上要更克制，否则浅色壁纸按下时会糊成一片白
    float glowGain = mix(0.085, 0.045, uAdapt) * uPress;
    col += float3(core * glowGain);
    // 贴边的一圈额外提亮：读起来像光从玻璃边缘透进来
    float edgeGlow = smoothstep(0.55, 1.0, edge) * uPress;
    col += float3(edgeGlow * 0.10 * mix(1.0, 0.7, uAdapt));

    // ---- 12. 顶沿亮边：只留顶部一条窄亮面，整块斜纹会读成塑料膜，去掉 ----
    float topRim = (1.0 - smoothstep(0.0, 0.045, p.y / uSize.y)) * smoothstep(0.0, 0.30, inward);
    col += float3(topRim * 0.05);

    // ---- 13. 细颗粒：打散模糊后的塑料感 ----
    float2 gp = p * 1.35;
    float grain = fract(sin(dot(gp, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    // 按压时颗粒略减：材质被「点亮」后应该更干净，颗粒感会削弱玻璃感
    col += float3(grain * 0.016 * mix(1.0, 0.6, uPress));

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

    /**
     * 按压强度 0~1。
     *
     * iOS 的玻璃被按下时是「从内部亮起来 + 瞬时鼓起」，松手回弹。
     * 这里用 Animator 驱动，按下 90ms 升到 1、松开 160ms 回落，
     * 回升比下降慢一点，读起来更像有阻尼的材质而不是开关。
     */
    private var press = 0f
    private var pressAnimator: ValueAnimator? = null

    /** 是否响应按压。卡片这类不可点的容器应当关掉，免得滑动时误触发。 */
    var pressEnabled: Boolean = true

    /**
     * 背景平均亮度 0~1，用于白膜厚度自适应。
     *
     * 从已经抓好的 [backdrop] 位图里抽样统计，不额外读屏幕像素。
     * 做成低通滤波：背景一换（翻页、换壁纸）不会突然跳一个亮度。
     */
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
        // 按压动画在窗口分离后仍会跑完，
        // 期间的 invalidate 会打在一个已经不再绘制的 View 上，先停掉更干净。
        pressAnimator?.cancel()
        pressAnimator = null
        press = 0f
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
        s.setFloatUniform("uPress", press)
        s.setFloatUniform("uAdapt", adaptLuma)

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
            effectTiltY == tiltY &&
            // 按压与自适应亮度也是会被拷贝走的 uniform，必须一起比。
            // 漏掉它们的话，按压动画和背景亮度变化都不会真正画出来。
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
        sampleAdaptiveLuma(bmp, signature)
        return bmp
    }

    /**
     * 从刚抓好的背景位图里抽样估一个平均亮度，供白膜自适应。
     *
     * 不额外读屏幕像素：位图本来就是这块玻璃背后那块区域，直接拿来算最省。
     * 采样步长按位图尺寸自适应，总量恒定在一个很小的量级（约 12x12 个点），
     * 拖动/滚动时每帧都会走到这里，不能变成一次全图遍历。
     *
     * 用 0.35 的低通系数：换页、换壁纸时亮度是渐变的，不会突然跳一下。
     */
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
                    // 与着色器里的 luma 用同一组系数，判断口径一致
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                    // 半透明像素按 alpha 加权：透出来的底色本身就是背景的一部分
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

    /**
     * 按压反馈：只观察不拦截。
     *
     * 返回 false 让事件继续下发给孩子（玻璃栏里都是按钮，拦住就没法点了）。
     * 这里只关心「手指按下 / 抬起 / 划出」，用来驱动 [press]。
     * 用 ACTION_MASK 兼容多指：第二根手指按下不该把状态刷回去。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        trackPress(event)
        return super.onTouchEvent(event)
    }

    /**
     * 真正决定发光时机的是这个分发入口。
     *
     * 玻璃容器本身通常不可点击（子 View 才可点），此时 onTouchEvent 根本不会来，
     * 所以必须在 dispatchTouchEvent 里观察。看过之后原样交给 super，
     * 触摸语义与改动前完全一致。
     */
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
                // 手指划出玻璃范围时提前收掉高光，避免「按着别处这里还亮着」
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
            // 没有着色器时按压没有视觉效果，直接落值，省掉一次无谓的动画
            press = target
            return
        }
        pressAnimator = ValueAnimator.ofFloat(press, target).apply {
            duration = durationMs
            // 按下用 OvershootInterpolator 制造一点「弹」的手感，
            // 但它会让进度冲过 1.0，uPress 必须钳回 0..1，否则着色器里
            // 白膜/辉光会短暂超量，看起来像闪了一下白光。
            interpolator = easing ?: OvershootInterpolator(1.6f)
            addUpdateListener {
                press = (it.animatedValue as Float).coerceIn(0f, 1f)
                // 按压会改 uniform，而 uniform 变了要重建 RenderEffect，这里必须走重绘
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
