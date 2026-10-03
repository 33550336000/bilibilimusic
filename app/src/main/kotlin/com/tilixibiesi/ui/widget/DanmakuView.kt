package com.tilixibiesi.ui.widget

import com.tilixibiesi.bili.DanmakuItem

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * 弹幕渲染层（Canvas 自绘，尽量贴近 B 站 Web/客户端的观感）。
 *
 * 核心机制：
 *  - **双时钟**：`clockMs` 是只在播放时推进的虚拟绘制时钟（暂停时弹幕原地冻结，
 *    B 站同样行为），`videoTimeMs` 是视频时间轴，用来决定"该放哪些弹幕"。
 *    `clockMs` 按 `playbackSpeed` 缩放推进，因此长按 2 倍速时弹幕同步加速。
 *  - **游标投放**：数据按时间升序，用 [cursor] 单向推进，把
 *    `[cursor, 首个 timeMs > t)` 区间内的弹幕投入屏幕，之后不再回扫，
 *    所以几千条弹幕也是 O(n) 总量而非每帧 O(n)。
 *  - **轨道模型**：高度按行切成若干轨道。弹幕分配到哪条轨道就画在哪条轨道的 y 上，
 *    **不做任何防重叠 / 防追尾约束**：不要求与上一条的间隔、不查轨道是否被占用，
 *    也不会因为"这一轨还满着"就把弹幕丢弃或挪到别的位置。重叠就重叠，按实际位置画。
 *  - **匀速穿过**：所有滚动弹幕都用固定的 [SCROLL_DURATION_MS] 走完
 *    「屏幕宽 + 自身宽」，因此长弹幕略快于短弹幕，与 B 站一致。
 *  - 顶部(5)/底部(4)固定弹幕居中显示 [FIXED_DURATION_MS] 后移除；
 *    逆向滚动(6) 左进右出；特殊弹幕(7) 按普通滚动处理。
 *
 * 该 View 只负责画，不持有播放器：位置由 [updatePosition] 喂进来，
 * 暂停由 [setPaused] 控制。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一条「正在屏幕上」的弹幕 */
    private class ActiveDanmaku(
        val item: DanmakuItem,
        val track: Int,
        val textWidth: Float,
        val startClock: Long,
        val duration: Long,
        /** 投放时使用的字号（px）。轨道高度会随字号变化，重排时要按它重算 */
        val textSizePx: Float
    ) {
        fun progress(clock: Long): Float =
            ((clock - startClock).toFloat() / duration.toFloat()).coerceIn(0f, 1f)

        fun isExpired(clock: Long): Boolean = clock - startClock >= duration
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    /** 描边：亮色画面上保证文字可读（B 站的弹幕同样带描边） */
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        style = Paint.Style.STROKE
    }

    private var data: List<DanmakuItem> = emptyList()
    private var cursor = 0

    private var videoTimeMs = 0L
    /**
     * 上一次 [updatePosition] 的墙钟时刻。
     *
     * 用于推算"正常播放本应前进多少"（间隔 × 倍速），
     * 从而让正向 seek 阈值自适应，而不是取一个会被 2 倍速+卡顿误触发的固定值。
     */
    private var lastPositionWallMs = 0L
    /** 虚拟绘制时钟，只在播放时推进（暂停时弹幕原地冻结） */
    private var clockMs = 0L
    private var lastFrameNanos = 0L
    private var paused = false
    private var running = false

    /** 播放倍速：长按 2.0x 时弹幕必须同倍速加速，否则与画面脱节 */
    private var playbackSpeed = 1.0f

    private val actives = ArrayList<ActiveDanmaku>()

    /** 是否需要重绘。仅在内容真正变化时置位，避免空屏也 60fps 重绘 */
    private var needsInvalidate = false

    /** 是否已挂上 Choreographer 帧回调（用 vsync 驱动，而不是 postDelayed(16)） */
    private var frameScheduled = false

    /**
     * 尺寸尚未就绪时被推迟的 seek 位置。
     *
     * `seek()` 可能在 View 还没 layout（width/height == 0）时被调用，
     * 此时回填无从下手。记下来，等 [onSizeChanged] 拿到真实尺寸后补做。
     */
    private var pendingSeekMs: Long? = null

    // ---- 轨道状态 ----
    private var trackCount = 0
    private var trackHeightPx = 0f
    /**
     * 三类弹幕各自的轮转游标。
     *
     * 只用来决定"下一条落在哪条轨道"，**不记录该轨道是否被占用、上一条多宽、
     * 何时让位**——那些都是防重叠约束的一部分，已全部移除。
     */
    private var scrollCursor = 0
    private var topCursor = 0
    private var bottomCursor = 0

    /** 弹幕不透明度（0..255） */
    var danmakuAlpha: Int = 0xEE
        set(value) { field = value.coerceIn(0, 255) }

    /** 字号缩放，1.0 为 B 站标准 */
    var danmakuScale: Float = 1.0f
        set(value) {
            val v = value.coerceIn(0.5f, 2.0f)
            if (v == field) return
            field = v
            recalcTracks()
            // 轨道几何变了，在屏弹幕的 track 下标随之失效，必须重排
            rebuildFrom(videoTimeMs)
        }

    /** 显示区域占高度的比例（对应 B 站的「显示区域：1/4、1/2、不限」） */
    var danmakuAreaRatio: Float = 1.0f
        set(value) {
            val v = value.coerceIn(0.2f, 1.0f)
            if (v == field) return
            field = v
            recalcTracks()
            rebuildFrom(videoTimeMs)
        }

    /**
     * 帧回调：由 vsync 驱动。
     *
     * 旧实现用 `handler.postDelayed(this, 16)`，有两个毛病：
     *  1. 与屏幕刷新率无关——120Hz 屏不会提帧，且与 vsync 不对齐会抖动/撕裂；
     *  2. 只要在播放就无条件 `invalidate()`，屏幕上一条弹幕都没有时也照样全屏重绘。
     * 现在改为 Choreographer + [needsInvalidate]：只在画面内容确实变化时才重绘。
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!running || paused) return
            tick(frameTimeNanos)
            scheduleFrame()
        }
    }

    companion object {
        /** 滚动弹幕穿过屏幕的时长，B 站约为 8 秒 */
        private const val SCROLL_DURATION_MS = 8000L
        /** 顶部/底部固定弹幕停留时长，B 站约为 4 秒 */
        private const val FIXED_DURATION_MS = 4000L
        /** 字号 25（B 站标准）对应的 sp */
        private const val STANDARD_SIZE_SP = 21f
        /** 字号比例下限（对应 B 站小字号 18） */
        private const val MIN_FONT_RATIO = 18f / 25f
        /**
         * 字号比例上限（对应 B 站大字号 36）。
         *
         * 解析器允许到 60，但渲染封顶到 36：见 [fontRatio]。
         * 封顶后单条超大字号最多把轨数压到 25/36，而不会压到 1/2.4。
         */
        private const val MAX_FONT_RATIO = 36f / 25f
        /** 行高 = 字号 × 该系数 */
        private const val TRACK_HEIGHT_FACTOR = 1.35f
        /** 固定弹幕（顶部/底部）的淡入淡出时长 */
        private const val FADE_MS = 300L
        /**
         * 判定为"用户往回拖了"的阈值。
         * 必须显著大于播放器上报周期与常见抖动，
         * 否则缓冲期间的位置停滞会被误判成回退，导致周期性清屏。
         */
        private const val SEEK_BACK_THRESHOLD_MS = 2000L
        /**
         * 判定为"位置向前跳了"的**附加余量**。
         *
         * 不能用一个固定阈值：正常播放时每次上报的前进量 = 上报间隔 × 倍速。
         * 若固定取 500ms，2 倍速下只要主线程卡顿 250ms，
         * 一次**正常**前进就会被误判成 seek 而触发整屏重排（弹幕瞬间跳位）。
         * 因此实际阈值 = `本次间隔 × 倍速 + 该余量`（见 [updatePosition]）。
         *
         * 余量取 300ms：足以吸收上报抖动与位置读数误差，
         * 又远小于任何有意义的用户跳转。
         */
        private const val SEEK_FORWARD_MARGIN_MS = 300L
        /**
         * 单帧允许推进绘制时钟的上限（墙钟毫秒）。
         *
         * 注意这**不会**造成"一次性投放上百条"——投放由 `videoTimeMs` 驱动，
         * 与 `clockMs` 无关。这里只是防止主线程长时间卡顿后，动画被一次性快进。
         */
        private const val MAX_FRAME_DT_MS = 500L
        /**
         * 回填窗口：向回看多久，把"本该还在屏上"的弹幕找回来。
         * 滚动弹幕在屏最长 8 秒，宽弹幕行程更长，故在 [SCROLL_DURATION_MS]
         * 上再放宽一截；宁可多扫几条（反正会被 elapsed ≥ duration 过滤掉），
         * 也不能漏掉还在飞的那几条。
         */
        private const val BACKFILL_WINDOW_MS = SCROLL_DURATION_MS * 2
    }

    /** 装载弹幕数据；会清空屏幕并重置游标 */
    fun setData(items: List<DanmakuItem>) {
        data = items
        reset()
        invalidate()
    }

    /**
     * 只清空屏幕上的弹幕，**保留已加载的数据**。
     *
     * 早期实现在这里把 `data` 置空，导致"关闭弹幕再打开"时必须重新走网络：
     * 一旦补拉时机没对上，屏幕上就一条弹幕都没有。数据与显示状态必须解耦——
     * 数据是视频级别的（跟着 cid 走），屏幕状态是播放位置级别的。
     * 真正要丢弃数据（切集/关闭播放器）请显式调用 [setData]。
     */
    fun clear() {
        reset()
        invalidate()
    }

    private fun reset() {
        cursor = 0
        actives.clear()
        scrollCursor = 0
        topCursor = 0
        bottomCursor = 0
        clockMs = 0L
        lastPositionWallMs = 0L
        // 数据换了（切集），上一次遗留的"待补做 seek"必须作废，
        // 否则新集会先被按旧位置回填一次
        pendingSeekMs = null
    }

    /**
     * 用播放器的真实位置校正视频时间轴。
     *
     * **绝不能因为"播放器报的位置比我们累进的值小"就清屏**——
     * MediaPlayer 在缓冲、起播、seek 收敛期间 `currentPosition` 会停滞甚至回退几帧，
     * 而弹幕时钟在 tick 里持续推进，于是 `diff < 0` 会**每隔一个上报周期就成立一次**，
     * 表现为"弹幕刚飘出来两三秒就被整体抹掉"（这正是早期版本的实际症状）。
     *
     * 同时，**向前的大跳也必须回填**：只处理回退时，任何正向跳转
     * （续播进度、通知栏拖动进度条）都会让 tick 里的游标一次性把整个区间的弹幕
     * 投出去，它们拿到同一个 startClock、同时从右边缘起步，画面瞬间被糊死。
     */
    fun updatePosition(posMs: Long) {
        if (data.isEmpty()) {
            videoTimeMs = posMs
            return
        }
        // 与上次上报的墙钟间隔：用来推算"正常前进"本应是多少
        val wall = SystemClock.uptimeMillis()
        val sinceLastMs = if (lastPositionWallMs == 0L) 0L else wall - lastPositionWallMs
        lastPositionWallMs = wall

        val diff = posMs - videoTimeMs
        // 正向阈值随「实际间隔 × 倍速」浮动：正常播放的前进量就是这个量级，
        // 只有超出它一个余量才可能是 seek。固定阈值在 2 倍速 + 主线程卡顿时
        // 会把正常前进误判成跳转，导致弹幕无故跳位。
        val expectedAdvance = (sinceLastMs * playbackSpeed).toLong()
        val forwardThreshold = expectedAdvance + SEEK_FORWARD_MARGIN_MS
        // 双向判定：正向与负向用不同阈值（见两个常量的说明）。
        // 只有"明确跳转"才清屏重排；小幅抖动与缓冲停滞都视为噪声，不干预。
        if (diff > forwardThreshold || diff < -SEEK_BACK_THRESHOLD_MS) {
            videoTimeMs = posMs
            rebuildFrom(posMs)
        } else {
            videoTimeMs = posMs
        }
    }

    /** 显式跳转（切集、拖动进度条）：同样回填，拖到哪就从哪的满屏状态继续 */
    fun seek(posMs: Long) {
        videoTimeMs = posMs
        // 尺寸还没量出来时回填不出任何东西（rebuildFrom 会直接返回）。
        // 记下位置，等 onSizeChanged 量到尺寸后补做——否则「切集/开弹幕」
        // 若发生在布局完成之前，屏幕会一直空着等下一条弹幕自然到达。
        if (width <= 0 || height <= 0) {
            pendingSeekMs = posMs
            return
        }
        pendingSeekMs = null
        rebuildFrom(posMs)
    }

    /**
     * 设置播放倍速。长按 2.0x 时视频 2 倍速走，弹幕也必须同倍速，
     * 否则观感上弹幕相对画面"慢了一半"。
     *
     * **绝不能在这里重排（rebuildFrom）**：变速只是改变 `clockMs` 的推进速率，
     * 而 `duration` 是速度无关的（单位＝视频时间轴毫秒）。`clockMs` 保持连续，
     * 每条弹幕的 `progress = (clock - startClock) / duration` 在变速瞬间**不变**，
     * 位置自然平滑衔接。一旦重排，每条在屏弹幕都会按数据重新推导出
     * 新的 startClock 与**新的轨道下标**，整屏弹幕会瞬间跳位。
     */
    fun setPlaybackSpeed(speed: Float) {
        val s = speed.coerceIn(0.25f, 4.0f)
        if (s == playbackSpeed) return
        playbackSpeed = s
        // 只改速率：位置由 clockMs 的连续性保证，无需也不允许重排
    }

    /**
     * 回填：把「此刻本该在屏幕上」的弹幕按各自的飞行进度直接铺回去。
     *
     * **为什么需要它：** 弹幕接口只给「出现时刻」，不给坐标也不给行号，
     * 位置全靠客户端按 `当前时刻 - 出现时刻` 现算。而投放是游标单向推进的，
     * 一旦中途接入（关闭弹幕再打开、拖动进度条、续播），游标只能指向「下一条」，
     * 屏幕上就是空的——必须等下一条弹幕的时间点自然到达才有东西冒出来，
     * 冷门视频要干等好几秒。官方客户端的做法正是回填一个在屏时长窗口。
     *
     * 做法：对 `[posMs - 在屏时长, posMs]` 窗口内的每条弹幕，用
     * `elapsed = posMs - 它的出现时刻` 反推它已经飞了多久，
     * 把 startClock 设成 `clockMs - elapsed`，于是它一上来就处于
     * 「飞到一半」的状态，而不是从右边缘重新起步。
     *
     * 已经飞完整段行程的（elapsed ≥ 时长）自然被丢掉，不需要额外判断。
     */
    private fun rebuildFrom(posMs: Long) {
        actives.clear()
        cursor = lowerBound(posMs)
        if (data.isEmpty()) return
        if (width <= 0 || height <= 0) return
        if (trackCount <= 0) recalcTracks()
        // 窗口起点：滚动弹幕最长在屏 8 秒（+ 宽弹幕的行程余量），固定弹幕 4 秒，
        // 取两者上界再放宽一点，保证不会漏掉任何一条本该还在屏上的。
        val windowStart = posMs - BACKFILL_WINDOW_MS
        // data 按 timeMs 升序，直接线性扫描窗口区间即可，无需再二分
        var i = cursor - 1
        while (i >= 0 && data[i].timeMs >= windowStart) {
            val item = data[i]
            val elapsed = posMs - item.timeMs
            val textWidth = measureText(item)
            val dm = when (item.mode) {
                4 -> makeFixed(item, textWidth, isBottom = true, startAt = clockMs - elapsed)
                5 -> makeFixed(item, textWidth, isBottom = false, startAt = clockMs - elapsed)
                else -> makeScroll(item, textWidth, startAt = clockMs - elapsed)
            }
            // 已飞完的不铺（它们的进度会算成 1.0，画在屏幕外），直接跳过
            if (elapsed < dm.duration) actives.add(dm)
            i--
        }
        // actives 现在是「从新到旧」的顺序，倒回来恢复时间升序，
        // 与正常投放的顺序一致（绘制顺序上后画的压在先画的之上）
        if (actives.size > 1) actives.reverse()
        markDirty()
        // 帧回调只在「运行且未暂停」时才跑，暂停期间的任何重排
        // （暂停时拖进度条、转屏、切开关）都不会有人来消费 needsInvalidate，
        // 必须自己立即重绘一次，否则屏幕停留在重排前的旧内容。
        if (!running || paused) invalidate()
    }

    /** 返回 data 中首个 timeMs > timeMs 的下标 */
    private fun lowerBound(timeMs: Long): Int {
        var lo = 0
        var hi = data.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (data[mid].timeMs <= timeMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun setPaused(p: Boolean) {
        if (p == paused) return
        paused = p
        if (!p) {
            // 恢复播放：重置帧基准，避免把暂停时长算进一次 dt
            lastFrameNanos = 0L
            if (running) scheduleFrame()
            invalidate()
        } else {
            cancelFrame()
            invalidate()
        }
    }

    fun start() {
        running = true
        lastFrameNanos = 0L
        scheduleFrame()
    }

    fun stop() {
        running = false
        cancelFrame()
        actives.clear()
        invalidate()
    }

    public override fun onDetachedFromWindow() {
        running = false
        cancelFrame()
        super.onDetachedFromWindow()
    }

    private fun scheduleFrame() {
        if (frameScheduled || !running || paused) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun cancelFrame() {
        if (!frameScheduled) return
        frameScheduled = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /** 标记画面内容已变化，下一帧需要重绘 */
    private fun markDirty() {
        needsInvalidate = true
    }

    private fun tick(frameTimeNanos: Long) {
        val prev = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        // 首帧没有基准，只记录时间不推进
        val rawDtMs = if (prev == 0L) 0L else (frameTimeNanos - prev) / 1_000_000L
        // 单帧上限：卡顿/后台恢复后不要一次性把弹幕推完（旧实现是 2000ms，过宽）
        val dtMs = rawDtMs.coerceIn(0L, MAX_FRAME_DT_MS)
        // 只推进绘制时钟（决定弹幕飘到哪、何时回收），并按播放倍速缩放。
        // **视频时间轴不由这里推进**：它由播放器通过 updatePosition 驱动。
        clockMs += (dtMs * playbackSpeed).toLong()

        // 投放：把已到达播放位置的弹幕放进屏幕
        if (cursor < data.size) {
            var dispatched = false
            while (cursor < data.size && data[cursor].timeMs <= videoTimeMs) {
                dispatch(data[cursor])
                dispatched = true
                cursor++
            }
            if (dispatched) markDirty()
        }
        // 回收
        if (actives.isNotEmpty()) {
            for (i in actives.size - 1 downTo 0) {
                if (actives[i].isExpired(clockMs)) {
                    actives.removeAt(i)
                    markDirty()
                }
            }
        }
        // 仍在飞的弹幕每帧位置都在变 → 必须重绘。
        // 空屏时（actives 为空且无新投放）不重绘，后台/冷场不再白烧 GPU。
        if (actives.isNotEmpty()) markDirty()
        if (needsInvalidate) {
            needsInvalidate = false
            invalidate()
        }
    }

    private fun dispatch(item: DanmakuItem) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (trackCount <= 0) recalcTracks()
        // 用真实文本宽度（不截断）：超长弹幕的滚动行程更长、在屏时间更久，
        // 与 B 站「长弹幕飘得久」的观感一致；若在此处钳制宽度，
        // 行程会被算短，弹幕会在尚未完全驶出屏幕时就被回收，出现"半截消失"。
        val textWidth = measureText(item)
        val dm = when (item.mode) {
            4 -> makeFixed(item, textWidth, isBottom = true)
            5 -> makeFixed(item, textWidth, isBottom = false)
            else -> makeScroll(item, textWidth)   // 1 普通滚动 / 6 逆向 / 7 特殊
        }
        actives.add(dm)
    }

    private fun measureText(item: DanmakuItem): Float {
        fillPaint.textSize = textSizePx(item)
        return fillPaint.measureText(item.text)
    }

    /**
     * 弹幕字号相对标准字号（25）的比例。
     *
     * 解析器允许 `p` 字段里的字号为 10..60，但**渲染侧必须封顶**：
     * 轨高一旦按最大字号算，一条 fontSize=60 的弹幕就会把轨数压到 1/2.4
     * （32 轨掉到 13 轨，密度明显变差）。B 站客户端同样只区分
     * 小(18)/标准(25)/大(36) 几档，超大字号会被归一化。
     *
     * 封顶后 [recalcTracks] 用同一比例上界定轨高，
     * 因此"渲染高度 ≤ 轨道高度"恒成立，压字在构造上不可能发生。
     */
    private fun fontRatio(item: DanmakuItem): Float =
        (item.fontSize / 25f).coerceIn(MIN_FONT_RATIO, MAX_FONT_RATIO)

    private fun textSizePx(item: DanmakuItem): Float =
        STANDARD_SIZE_SP * danmakuScale * fontRatio(item) *
                resources.displayMetrics.scaledDensity

    /**
     * 滚动弹幕的轨道分配：轮转取一条，**不做任何约束**。
     *
     * 旧实现在此做了两层"软约束"：先找一条满足最小间隔的空闲轨道，
     * 全都还没让位时再挑最久未使用的那条叠上去。这里全部去掉——
     * 不查上一条多宽、不查何时让位、不查这一轨是不是还被占着。
     * 轮到哪条轨道就画在哪条轨道的位置上，压字就压字。
     */
    private fun pickScrollTrack(): Int {
        if (trackCount <= 0) return 0
        val t = scrollCursor % trackCount
        scrollCursor = (t + 1) % trackCount
        return t
    }

    /**
     * 滚动弹幕的在屏时长（单位：绘制时钟毫秒，等于视频时间轴毫秒）。
     *
     * 行程 = 屏宽 + 自身宽（右边缘之外 → 左边缘之外），按固定速度走完，
     * 因此长弹幕在屏时间自然更久，与 B 站一致。
     * 注意：不能直接用 SCROLL_DURATION_MS 当生命周期——那是"屏宽"的基准时长，
     * 用它当过期时间会让宽弹幕在还没完全驶出屏幕时就被回收（表现为"半截消失"）。
     *
     * **倍速不在这里处理**：`clockMs` 已按 `playbackSpeed` 加速推进，
     * 时长保持"视频时间轴单位"才能让回填的 `elapsed` 与 `startClock` 一一对应。
     * 若此处再除一次倍速，2 倍速会变成 4 倍速（弹幕飞完只需 2 秒）。
     */
    private fun scrollDuration(textWidth: Float): Long =
        (SCROLL_DURATION_MS * (width + textWidth) / width.toFloat())
            .toLong().coerceAtLeast(1L)

    private fun makeScroll(
        item: DanmakuItem,
        textWidth: Float,
        startAt: Long = clockMs
    ): ActiveDanmaku =
        ActiveDanmaku(
            item, pickScrollTrack(), textWidth, startAt, scrollDuration(textWidth),
            textSizePx(item)
        )

    /**
     * 顶部弹幕自上而下排、底部弹幕自下而上排——这是它们**本来的位置**（mode 5 / 4），
     * 不是约束。
     *
     * 旧实现在此还要查"这一轨空不空"（fixedFreeAt 占用表），占满时复用最旧的一条；
     * 现在同样去掉：轮到哪条轨道就停在哪条轨道上，与已在屏上的弹幕重不重叠不再考虑。
     */
    private fun makeFixed(
        item: DanmakuItem,
        textWidth: Float,
        isBottom: Boolean,
        startAt: Long = clockMs
    ): ActiveDanmaku {
        val size = textSizePx(item)
        // 与 scrollDuration 同理：倍速由 clockMs 的推进速率承担，时长保持视频时间轴单位
        val duration = FIXED_DURATION_MS
        if (trackCount <= 0) {
            return ActiveDanmaku(item, 0, textWidth, startAt, duration, size)
        }
        val seq = (if (isBottom) bottomCursor else topCursor) % trackCount
        val next = (seq + 1) % trackCount
        if (isBottom) bottomCursor = next else topCursor = next
        val track = if (isBottom) trackCount - 1 - seq else seq
        return ActiveDanmaku(item, track, textWidth, startAt, duration, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val trackCountBefore = trackCount
        val trackHeightBefore = trackHeightPx
        recalcTracks()
        // 尺寸/轨道布局真的变了才重排：`track` 是投放时定死的整数下标，
        // 竖屏 32 轨切到横屏 12 轨后，track=20 的弹幕 y 会落到屏幕外，
        // 横屏切竖屏则全挤在上半屏。必须按新几何重算在屏弹幕的位置。
        if (trackCount != trackCountBefore || trackHeightBefore != trackHeightPx) {
            rebuildFrom(videoTimeMs)
        }
        // 补做此前因尺寸未就绪而被推迟的 seek
        pendingSeekMs?.let {
            pendingSeekMs = null
            rebuildFrom(it)
        }
    }

    /**
     * 计算轨道高度与条数。
     *
     * **轨道高度必须容纳最大的字号**：字号来自弹幕自身的 `p` 字段
     * （解析器允许 10..60，25 为标准），而旧实现把轨高写死成
     * `标准字号 × 1.35`。一旦遇到 36/60 的大字号弹幕，文字高度是轨道的
     * 1.4~2.4 倍，必然溢出到相邻轨道，叠字不可避免。
     *
     * 这里按**标准字号与该视频实际出现的最大字号**取上界来定轨高，
     * 既不会因为少数大字号弹幕把整屏轨道数压得太少，也不会让它们压字。
     */
    private fun recalcTracks() {
        val h = height
        if (h <= 0) {
            trackCount = 0
            return
        }
        val density = resources.displayMetrics.scaledDensity
        val standardPx = STANDARD_SIZE_SP * danmakuScale * density
        // 取数据中最大的**有效**字号比例（与 [fontRatio] 同一套钳制，
        // 否则封顶后的渲染高度会小于按原始字号算出的轨高，白白浪费轨道）
        val maxRatio = if (data.isEmpty()) {
            1f
        } else {
            var m = 0f
            for (d in data) {
                val r = fontRatio(d)
                if (r > m) m = r
            }
            m.coerceAtLeast(MIN_FONT_RATIO)
        }
        trackHeightPx = standardPx * maxRatio * TRACK_HEIGHT_FACTOR
        val usable = h * danmakuAreaRatio
        val count = (usable / trackHeightPx).toInt().coerceAtLeast(1)
        trackCount = count
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (actives.isEmpty() || width <= 0 || height <= 0) return
        if (trackCount <= 0) recalcTracks()
        for (dm in actives) drawOne(canvas, dm)
    }

    private fun drawOne(canvas: Canvas, dm: ActiveDanmaku) {
        val item = dm.item
        // 用投放时记录的字号，而不是重新推导：轨道高度/位置是按它算的，
        // 中途改变 danmakuScale 会导致文字与轨道错位
        val size = dm.textSizePx
        val y = trackHeightPx * (dm.track + 1) - trackHeightPx * 0.18f
        val baseAlpha = danmakuAlpha

        val progress = dm.progress(clockMs)
        val x: Float
        // 固定弹幕在生命周期的头尾各 FADE_MS 淡入/淡出，
        // 避免"停留几秒后啪一下整条消失"——B 站的固定弹幕同样有淡出。
        val alpha: Int = if (item.mode == 4 || item.mode == 5) {
            val elapsed = clockMs - dm.startClock
            val fade = when {
                elapsed < FADE_MS -> elapsed.toFloat() / FADE_MS
                elapsed > dm.duration - FADE_MS ->
                    ((dm.duration - elapsed).toFloat() / FADE_MS).coerceIn(0f, 1f)
                else -> 1f
            }
            x = (width - dm.textWidth) / 2f
            (baseAlpha * fade).toInt()
        } else {
            // 滚动弹幕：行程从「右边缘之外」开始、到「左边缘之外」结束，
            // 因此文字是完整地从一侧滑入、从另一侧滑出，而不是在边缘凭空出现/消失。
            x = if (item.mode == 6)
                progress * (width + dm.textWidth) - dm.textWidth  // 逆向：左进右出
            else
                width - progress * (width + dm.textWidth)         // 常规：右进左出
            baseAlpha
        }

        fillPaint.textSize = size
        fillPaint.color = (alpha shl 24) or (item.color and 0xFFFFFF)
        strokePaint.textSize = size
        strokePaint.strokeWidth = size * 0.14f
        strokePaint.color = (alpha shl 24) // 黑色描边

        canvas.drawText(item.text, x, y, strokePaint)
        canvas.drawText(item.text, x, y, fillPaint)
    }
}
