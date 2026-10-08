package com.tilixibiesi.ui.widget

import com.tilixibiesi.bili.DanmakuItem

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class ActiveDanmaku(
        val item: DanmakuItem,
        val track: Int,
        val textWidth: Float,
        val startClock: Long,
        val duration: Long,
        val textSizePx: Float
    ) {
        fun progress(clock: Long): Float =
            ((clock - startClock).toFloat() / duration.toFloat()).coerceIn(0f, 1f)

        fun isExpired(clock: Long): Boolean = clock - startClock >= duration
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        style = Paint.Style.STROKE
    }

    private var data: List<DanmakuItem> = emptyList()
    private var cursor = 0

    private var videoTimeMs = 0L
    private var lastPositionWallMs = 0L
    private var clockMs = 0L
    private var lastFrameNanos = 0L
    private var paused = false
    private var running = false

    private var playbackSpeed = 1.0f

    private val actives = ArrayList<ActiveDanmaku>()

    private var needsInvalidate = false

    private var frameScheduled = false

    private var pendingSeekMs: Long? = null

    private var trackCount = 0
    private var trackHeightPx = 0f
    private var scrollCursor = 0
    private var topCursor = 0
    private var bottomCursor = 0

    var danmakuAlpha: Int = 0xEE
        set(value) { field = value.coerceIn(0, 255) }

    var danmakuScale: Float = 1.0f
        set(value) {
            val v = value.coerceIn(0.5f, 2.0f)
            if (v == field) return
            field = v
            recalcTracks()
            rebuildFrom(videoTimeMs)
        }

    var danmakuAreaRatio: Float = 1.0f
        set(value) {
            val v = value.coerceIn(0.2f, 1.0f)
            if (v == field) return
            field = v
            recalcTracks()
            rebuildFrom(videoTimeMs)
        }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!running || paused) return
            tick(frameTimeNanos)
            scheduleFrame()
        }
    }

    companion object {
        private const val SCROLL_DURATION_MS = 8000L
        private const val FIXED_DURATION_MS = 4000L
        private const val STANDARD_SIZE_SP = 21f
        private const val MIN_FONT_RATIO = 18f / 25f
        private const val MAX_FONT_RATIO = 36f / 25f
        private const val TRACK_HEIGHT_FACTOR = 1.35f
        private const val FADE_MS = 300L
        private const val SEEK_BACK_THRESHOLD_MS = 2000L
        private const val SEEK_FORWARD_MARGIN_MS = 300L
        private const val MAX_FRAME_DT_MS = 500L
        private const val BACKFILL_WINDOW_MS = SCROLL_DURATION_MS * 2
    }

    fun setData(items: List<DanmakuItem>) {
        data = items
        reset()
        invalidate()
    }

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
        pendingSeekMs = null
    }

    fun updatePosition(posMs: Long) {
        if (data.isEmpty()) {
            videoTimeMs = posMs
            return
        }
        val wall = SystemClock.uptimeMillis()
        val sinceLastMs = if (lastPositionWallMs == 0L) 0L else wall - lastPositionWallMs
        lastPositionWallMs = wall

        val diff = posMs - videoTimeMs
        val expectedAdvance = (sinceLastMs * playbackSpeed).toLong()
        val forwardThreshold = expectedAdvance + SEEK_FORWARD_MARGIN_MS
        if (diff > forwardThreshold || diff < -SEEK_BACK_THRESHOLD_MS) {
            videoTimeMs = posMs
            rebuildFrom(posMs)
        } else {
            videoTimeMs = posMs
        }
    }

    fun seek(posMs: Long) {
        videoTimeMs = posMs
        if (width <= 0 || height <= 0) {
            pendingSeekMs = posMs
            return
        }
        pendingSeekMs = null
        rebuildFrom(posMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        val s = speed.coerceIn(0.25f, 4.0f)
        if (s == playbackSpeed) return
        playbackSpeed = s
    }

    private fun rebuildFrom(posMs: Long) {
        actives.clear()
        cursor = lowerBound(posMs)
        if (data.isEmpty()) return
        if (width <= 0 || height <= 0) return
        if (trackCount <= 0) recalcTracks()
        val windowStart = posMs - BACKFILL_WINDOW_MS
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
            if (elapsed < dm.duration) actives.add(dm)
            i--
        }
        if (actives.size > 1) actives.reverse()
        markDirty()
        if (!running || paused) invalidate()
    }

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

    private fun markDirty() {
        needsInvalidate = true
    }

    private fun tick(frameTimeNanos: Long) {
        val prev = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        val rawDtMs = if (prev == 0L) 0L else (frameTimeNanos - prev) / 1_000_000L
        val dtMs = rawDtMs.coerceIn(0L, MAX_FRAME_DT_MS)
        clockMs += (dtMs * playbackSpeed).toLong()

        if (cursor < data.size) {
            var dispatched = false
            while (cursor < data.size && data[cursor].timeMs <= videoTimeMs) {
                dispatch(data[cursor])
                dispatched = true
                cursor++
            }
            if (dispatched) markDirty()
        }
        if (actives.isNotEmpty()) {
            for (i in actives.size - 1 downTo 0) {
                if (actives[i].isExpired(clockMs)) {
                    actives.removeAt(i)
                    markDirty()
                }
            }
        }
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
        val textWidth = measureText(item)
        val dm = when (item.mode) {
            4 -> makeFixed(item, textWidth, isBottom = true)
            5 -> makeFixed(item, textWidth, isBottom = false)
            else -> makeScroll(item, textWidth)
        }
        actives.add(dm)
    }

    private fun measureText(item: DanmakuItem): Float {
        fillPaint.textSize = textSizePx(item)
        return fillPaint.measureText(item.text)
    }

    private fun fontRatio(item: DanmakuItem): Float =
        (item.fontSize / 25f).coerceIn(MIN_FONT_RATIO, MAX_FONT_RATIO)

    private fun textSizePx(item: DanmakuItem): Float =
        STANDARD_SIZE_SP * danmakuScale * fontRatio(item) *
                resources.displayMetrics.scaledDensity

    private fun pickScrollTrack(): Int {
        if (trackCount <= 0) return 0
        val t = scrollCursor % trackCount
        scrollCursor = (t + 1) % trackCount
        return t
    }

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

    private fun makeFixed(
        item: DanmakuItem,
        textWidth: Float,
        isBottom: Boolean,
        startAt: Long = clockMs
    ): ActiveDanmaku {
        val size = textSizePx(item)
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
        if (trackCount != trackCountBefore || trackHeightBefore != trackHeightPx) {
            rebuildFrom(videoTimeMs)
        }
        pendingSeekMs?.let {
            pendingSeekMs = null
            rebuildFrom(it)
        }
    }

    private fun recalcTracks() {
        val h = height
        if (h <= 0) {
            trackCount = 0
            return
        }
        val density = resources.displayMetrics.scaledDensity
        val standardPx = STANDARD_SIZE_SP * danmakuScale * density
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
        val size = dm.textSizePx
        val y = trackHeightPx * (dm.track + 1) - trackHeightPx * 0.18f
        val baseAlpha = danmakuAlpha

        val progress = dm.progress(clockMs)
        val x: Float
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
            x = if (item.mode == 6)
                progress * (width + dm.textWidth) - dm.textWidth
            else
                width - progress * (width + dm.textWidth)
            baseAlpha
        }

        fillPaint.textSize = size
        fillPaint.color = (alpha shl 24) or (item.color and 0xFFFFFF)
        strokePaint.textSize = size
        strokePaint.strokeWidth = size * 0.14f
        strokePaint.color = (alpha shl 24)

        canvas.drawText(item.text, x, y, strokePaint)
        canvas.drawText(item.text, x, y, fillPaint)
    }
}
