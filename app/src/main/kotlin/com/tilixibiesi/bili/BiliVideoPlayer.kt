package com.tilixibiesi.bili
import com.tilixibiesi.model.BiliVideo
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.AppPaths
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.ui.BaseActivity
import com.tilixibiesi.ui.widget.DanmakuView
import com.tilixibiesi.R
import com.tilixibiesi.util.DialogHelper
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.AtomicFileWriter
import com.tilixibiesi.util.VideoPlaybackController
import com.tilixibiesi.service.VideoPlaybackService

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.lang.ref.WeakReference
import java.util.Locale
import android.annotation.SuppressLint
class BiliVideoPlayer(private val activity: BaseActivity) : VideoPlaybackController.Target {

    interface Callback {
        fun onFullscreenOpened()
        fun onFullscreenClosed()
        fun setSystemUIForFullscreen()
        fun restoreSystemUI()
        fun setFullScreen()
        fun onRequestSwitchNext()
        fun onRequestSwitchPrevious()
        fun onRecordHistory(video: BiliVideo)

        fun onRequestNeighbor(direction: Int): BiliVideo? = null

        fun onDanmakuToggled(enabled: Boolean) {}
    }

    var callback: Callback? = null

    val isFullscreenActive: Boolean
        get() = ::layoutFullscreen.isInitialized && layoutFullscreen.visibility == View.VISIBLE
    private val context: Context = activity
    private lateinit var layoutFullscreen: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var btnClose: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvTime: TextView
    private lateinit var btnOrientation: ImageButton
    private var btnAddToHistory: ImageButton? = null
    private var btnDanmakuToggle: ImageButton? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressUpdater: Runnable? = null
    private var isControlsVisible = true
    private var lastTapTime = 0L
    private val doubleTapInterval = 250L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchDownRawY = 0f
    private var gestureActive = false


    private var layoutSwitchPreview: FrameLayout? = null
    private var ivSwitchCover: ImageView? = null
    private var tvSwitchTitle: TextView? = null
    private var tvSwitchAuthor: TextView? = null
    private var layoutSwitchInfo: View? = null
    private var switchDirection = 0
    private val switchCommitRatio = 0.15f
    private var switchOffsetY = 0f
    private var switchCommitted = false
    private var switchCommitDirection = 0
    private var pendingSwitchSettle = false

    private var seekGestureMode = GESTURE_NONE
    private var seekAnchorX = 0f
    private var seekAnchorPos = 0
    private var lastSeekTarget = Int.MIN_VALUE
    private var isSeekingByGesture = false
    private var controlsVisibleBeforeSeek = true
    private val hideControlsAfterSeekRunnable = Runnable {
        if (mediaPlayer?.isPlaying == true) hideAllControls()
    }
    private var currentBvid: String? = null
    private var isExpectedPrepare = false
    private var progressSaveRunnable: Runnable? = null
    private val progressSaveInterval = 500L
    private val playRequestId = AtomicInteger(0)
    private var longPressTriggered = false
    private var isForcedLandscape = false
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var isUserSeeking = false
    private var seekCompleted = true
    private var danmakuView: DanmakuView? = null
    private var danmakuRequestId = 0
    private var danmakuRequest: BiliDanmakuLoader.Request? = null
    private var danmakuSyncRunnable: Runnable? = null
    private var danmakuCid: Long = 0L
    private var hasDanmakuData = false
    private var danmakuEnabled: Boolean = true
    private var hostVisible: Boolean = true
    private val longPressRunnable = Runnable {
        if (runCatching { mediaPlayer?.isPlaying }.getOrDefault(false) != true) return@Runnable
        val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        setPlaybackSpeed(2.0f)
        longPressTriggered = true
    }

    var currentVideoUrl: String = ""
        private set
    var currentVideoTitle: String = ""
        private set

    private var videoNotificationActive = false
    private var currentVideoAuthor: String = ""

companion object {
    private const val GESTURE_NONE = 0
    private const val GESTURE_SEEK = 1
    private const val GESTURE_VERTICAL = 2
    private const val GESTURE_SLOP_PX = 30f
    private const val HIDE_CONTROLS_DELAY_MS = 1500L
    private const val SWITCH_ANIM_MS = 180L
    private const val SWITCH_SETTLE_TIMEOUT_MS = 1200L
    private const val EDGE_RESISTANCE = 0.12f

    private const val DANMAKU_SYNC_INTERVAL_MS = 50L

    @Volatile
    private var currentPlayingInstance: WeakReference<BiliVideoPlayer>? = null
    private fun getCurrentPlayingInstance(): BiliVideoPlayer? {
        return currentPlayingInstance?.get()
    }
    fun stopCurrentVideo() {
        val instance = getCurrentPlayingInstance()
        if (instance != null) {
            instance.close()
        }
        currentPlayingInstance = null
    }
}
    fun initialize(
        layoutFullscreenVideo: FrameLayout,
        textureViewVideo: TextureView,
        closeBtn: ImageButton,
        titleTv: TextView,
        playPauseBtn: ImageButton,
        progressBar: SeekBar,
        timeTv: TextView,
        orientationBtn: ImageButton,
        addToHistoryBtn: ImageButton? = null,
        danmaku: DanmakuView? = null,
        danmakuToggleBtn: ImageButton? = null,
        switchPreview: FrameLayout? = null,
        switchCover: ImageView? = null,
        switchTitle: TextView? = null,
        switchAuthor: TextView? = null,
        switchInfo: View? = null
    ) {
        layoutFullscreen = layoutFullscreenVideo
        textureView = textureViewVideo
        btnClose = closeBtn
        tvTitle = titleTv
        btnPlayPause = playPauseBtn
        seekBar = progressBar
        tvTime = timeTv
        btnOrientation = orientationBtn
        btnAddToHistory = addToHistoryBtn
        danmakuView = danmaku
        btnDanmakuToggle = danmakuToggleBtn
        layoutSwitchPreview = switchPreview
        ivSwitchCover = switchCover
        tvSwitchTitle = switchTitle
        tvSwitchAuthor = switchAuthor
        layoutSwitchInfo = switchInfo
        danmakuEnabled = SpUtils.isDanmakuEnabled(activity)
        applyDanmakuVisibility()
        btnDanmakuToggle?.setColorFilter(
            if (danmakuEnabled) Color.parseColor("#2196F3") else Color.WHITE
        )
        danmakuToggleBtn?.setOnClickListener {
            setDanmakuEnabled(!danmakuEnabled)
            callback?.onDanmakuToggled(danmakuEnabled)
        }

        textureView.surfaceTextureListener = surfaceTextureListener
        releaseMediaPlayer()

        layoutFullscreen.setBackgroundColor(Color.BLACK)
        btnClose.background = null
        btnClose.setBackgroundColor(Color.TRANSPARENT)
        btnPlayPause.background = null
        btnPlayPause.setBackgroundColor(Color.TRANSPARENT)
        btnOrientation.background = null
        btnOrientation.setBackgroundColor(Color.TRANSPARENT)

        layoutFullscreen.isClickable = true
        layoutFullscreen.isFocusable = true
        textureView.isClickable = false
        textureView.isFocusable = false

        btnClose.setOnClickListener {
            close()
        }
        btnPlayPause.setOnClickListener {
            togglePlayPause()
        }
        btnOrientation.setOnClickListener {
            toggleLandscape()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvTime.text = context.getString(
                        R.string.player_time_format,
                        formatTime(progress),
                        formatTime(mediaPlayer?.duration ?: 0)
                    )
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                seekCompleted = false
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    val pos = it.progress
                    mediaPlayer?.seekTo(pos)
                }
            }
        })

        @SuppressLint("ClickableViewAccessibility")
        layoutFullscreen.setOnTouchListener { _, ev ->
            if (layoutFullscreen.visibility != View.VISIBLE) {
                false
            } else {
                try {
                    handleTouchEvent(ev)
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            surface = Surface(st)
            mediaPlayer?.setSurface(surface)
            textureView.post { updateLayoutForOrientation() }
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
            updateLayoutForOrientation()
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            mediaPlayer?.setSurface(null)
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            if (pendingSwitchSettle) {
                pendingSwitchSettle = false
                if (switchCommitted) resetSwitchDrag(force = true, animate = false)
            }
        }
    }

private fun updateLayoutForOrientation() {
    val params = textureView.layoutParams as? FrameLayout.LayoutParams ?: return

    if (videoWidth <= 0 || videoHeight <= 0) {
        params.width = FrameLayout.LayoutParams.MATCH_PARENT
        params.height = FrameLayout.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.CENTER
        textureView.layoutParams = params
        return
    }

    val parentWidth = layoutFullscreen.width
    val parentHeight = layoutFullscreen.height
    if (parentWidth <= 0 || parentHeight <= 0) {
        textureView.post { updateLayoutForOrientation() }
        return
    }

    val scaleX = parentWidth.toFloat() / videoWidth
    val scaleY = parentHeight.toFloat() / videoHeight
    val scale = Math.min(scaleX, scaleY)

    params.width = (videoWidth * scale).toInt()
    params.height = (videoHeight * scale).toInt()
    params.gravity = Gravity.CENTER

    textureView.layoutParams = params
}
@SuppressLint("SourceLockedOrientationActivity")
fun close() {
    isExpectedPrepare = false
    saveCurrentProgress()
    stopProgressUpdater()
    stopProgressSaver()
    resetDanmakuState()
    switchCommitted = false
    switchCommitDirection = 0
    pendingSwitchSettle = false
    handler.removeCallbacks(switchSettleTimeout)
    switchOffsetY = 0f
    switchDirection = 0
    textureView.animate().cancel()
    textureView.translationY = 0f
    danmakuView?.animate()?.cancel()
    danmakuView?.translationY = 0f
    layoutSwitchPreview?.animate()?.cancel()
    layoutSwitchPreview?.translationY = 0f
    layoutSwitchPreview?.visibility = View.GONE
    releaseMediaPlayer()

    detachVideoNotification()
    layoutFullscreen.visibility = View.GONE
    callback?.restoreSystemUI()
    callback?.setFullScreen()
    callback?.onFullscreenClosed()
    currentVideoUrl = ""
    currentVideoTitle = ""
    currentVideoAuthor = ""
    currentBvid = null
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    isForcedLandscape = false
    synchronized(BiliVideoPlayer::class.java) {
        val current = currentPlayingInstance?.get()
        if (current === this) {
            currentPlayingInstance = null
        }
    }
}

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
private val singleClickRunnable = Runnable {
    toggleControlsVisibility()
}
fun handleTouchEvent(ev: MotionEvent): Boolean {
    if (layoutFullscreen.visibility != View.VISIBLE) return false
    if (isTouchOnControl(ev)) return false

    val topThreshold = getStatusBarHeight() + dpToPx(100)

    when (ev.action) {
        MotionEvent.ACTION_DOWN -> {
            touchDownRawY = ev.rawY
            touchStartX = ev.x
            touchStartY = ev.y
            longPressTriggered = false
            seekGestureMode = GESTURE_NONE
            lastSeekTarget = Int.MIN_VALUE
            isSeekingByGesture = false
            gestureActive = true
            resetSwitchDrag()
            handler.removeCallbacks(hideControlsAfterSeekRunnable)
            if (touchDownRawY < topThreshold) {
            } else if (mediaPlayer?.isPlaying == true) {
                handler.postDelayed(longPressRunnable, 800)
            }
        }

        MotionEvent.ACTION_MOVE -> {
            if (!gestureActive) return true
            val dx = ev.x - touchStartX
            val dy = ev.y - touchStartY
            if (Math.abs(dx) > GESTURE_SLOP_PX || Math.abs(dy) > GESTURE_SLOP_PX) {
                handler.removeCallbacks(longPressRunnable)
            }
            if (longPressTriggered) return true
            if (seekGestureMode == GESTURE_NONE) {
                when {
                    Math.abs(dx) > GESTURE_SLOP_PX && Math.abs(dx) > Math.abs(dy) * 1.2f ->
                        seekGestureMode = GESTURE_SEEK
                    Math.abs(dy) > GESTURE_SLOP_PX && Math.abs(dy) > Math.abs(dx) * 1.2f ->
                        seekGestureMode = GESTURE_VERTICAL
                }
            }
            if (seekGestureMode == GESTURE_VERTICAL) {
                updateSwitchDrag(dy)
                return true
            }
            if (seekGestureMode == GESTURE_SEEK) {
                val duration = mediaPlayer?.duration ?: 0
                if (duration > 0) {
                    if (lastSeekTarget == Int.MIN_VALUE) {
                        isSeekingByGesture = true
                        seekAnchorX = touchStartX
                        seekAnchorPos = mediaPlayer?.currentPosition ?: 0
                        controlsVisibleBeforeSeek = isControlsVisible
                        if (!isControlsVisible) showAllControls()
                    }
                    val viewWidth = textureView.width.takeIf { it > 0 } ?: return true
                    val travel = ev.x - seekAnchorX
                    val newPos = (seekAnchorPos + travel / viewWidth * duration).toInt()
                        .coerceIn(0, duration)
                    lastSeekTarget = newPos
                    updateSeekPreview(newPos, duration)
                }
            }
        }

        MotionEvent.ACTION_CANCEL -> {
            handler.removeCallbacks(longPressRunnable)
            if (longPressTriggered) {
                setPlaybackSpeed(1.0f)
                longPressTriggered = false
            }
            cancelSwitchDrag()
            cancelGestureState()
            gestureActive = false
            return true
        }

        MotionEvent.ACTION_UP -> {
            handler.removeCallbacks(longPressRunnable)
            if (!gestureActive) return true
            gestureActive = false
            val wasLongPress = longPressTriggered
            if (wasLongPress) {
                setPlaybackSpeed(1.0f)
                longPressTriggered = false
            }

            val dx = ev.x - touchStartX
            val dy = ev.y - touchStartY

            if (seekGestureMode == GESTURE_VERTICAL) {
                finishSwitchDrag(dy)
                return true
            }

            if (wasLongPress) {
                lastSeekTarget = Int.MIN_VALUE
                seekGestureMode = GESTURE_NONE
                isSeekingByGesture = false
                return true
            }

            if (seekGestureMode == GESTURE_SEEK && lastSeekTarget != Int.MIN_VALUE) {
                val duration = mediaPlayer?.duration ?: 0
                if (duration > 0) {
                    val finalPos = lastSeekTarget.coerceIn(0, duration)
                    if (finalPos != (mediaPlayer?.currentPosition ?: -1)) {
                        mediaPlayer?.seekTo(finalPos)
                    }
                    updateSeekPreview(finalPos, duration)
                    danmakuView?.seek(finalPos.toLong())
                }
                lastSeekTarget = Int.MIN_VALUE
                seekGestureMode = GESTURE_NONE
                isSeekingByGesture = false
                seekCompleted = false
                handler.removeCallbacks(hideControlsAfterSeekRunnable)
                if (!controlsVisibleBeforeSeek) {
                    handler.postDelayed(hideControlsAfterSeekRunnable, HIDE_CONTROLS_DELAY_MS)
                }
                return true
            }

            if (Math.abs(dx) < 10 && Math.abs(dy) < 10) {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < doubleTapInterval) {
                    handler.removeCallbacks(singleClickRunnable)
                    togglePlayPause()
                    lastTapTime = 0
                } else {
                    handler.removeCallbacks(singleClickRunnable)
                    lastTapTime = now
                    handler.postDelayed(singleClickRunnable, 0)
                }
            }
        }
    }
    return true
}
    private fun cancelGestureState() {
        if (isSeekingByGesture) {
            val mp = mediaPlayer
            if (mp != null) {
                runCatching { updateSeekPreview(mp.currentPosition, mp.duration) }
            }
            handler.removeCallbacks(hideControlsAfterSeekRunnable)
            if (!controlsVisibleBeforeSeek) {
                handler.postDelayed(hideControlsAfterSeekRunnable, HIDE_CONTROLS_DELAY_MS)
            }
        }
        lastSeekTarget = Int.MIN_VALUE
        seekGestureMode = GESTURE_NONE
        isSeekingByGesture = false
    }


    private fun updateSwitchDrag(dy: Float) {
        if (switchCommitted) return
        if (layoutFullscreen.height <= 0) return
        val direction = if (dy > 0) -1 else 1
        if (direction != switchDirection) {
            switchDirection = direction
            showSwitchPreview(direction)
        }
        val hasNeighbor = callback?.onRequestNeighbor(direction) != null
        switchOffsetY = if (hasNeighbor) dy else dy * EDGE_RESISTANCE
        applySwitchTransform()
    }

    private fun finishSwitchDrag(dy: Float) {
        if (switchCommitted) return
        val containerH = layoutFullscreen.height
        val dir = switchDirection
        if (containerH <= 0 || dir == 0) {
            resetSwitchDrag()
            return
        }
        val threshold = containerH * switchCommitRatio
        val hasNeighbor = callback?.onRequestNeighbor(dir) != null
        if (hasNeighbor && Math.abs(dy) >= threshold) {
            switchCommitted = true
            switchCommitDirection = dir
            switchOffsetY = if (dy > 0) containerH.toFloat() else -containerH.toFloat()
            switchDirection = 0
            animateSwitchOffsetTo(switchOffsetY, dir)
            if (dir > 0) callback?.onRequestSwitchNext()
            else callback?.onRequestSwitchPrevious()
            return
        }
        resetSwitchDrag()
    }

    private fun cancelSwitchDrag() {
        resetSwitchDrag()
    }

    fun onVideoSwitched(firstFrameReady: Boolean = false) {
        if (!switchCommitted) {
            resetSwitchDrag(force = true, animate = false)
            return
        }
        if (!firstFrameReady) {
            pendingSwitchSettle = true
            handler.removeCallbacks(switchSettleTimeout)
            handler.postDelayed(switchSettleTimeout, SWITCH_SETTLE_TIMEOUT_MS)
            return
        }
        pendingSwitchSettle = false
        handler.removeCallbacks(switchSettleTimeout)
        resetSwitchDrag(force = true, animate = false)
    }

    private val switchSettleTimeout = Runnable {
        if (pendingSwitchSettle) {
            pendingSwitchSettle = false
            resetSwitchDrag(force = true, animate = false)
        }
    }

    private fun resetSwitchDrag(force: Boolean = false, animate: Boolean = true) {
        if (switchCommitted && !force) return
        val dir = if (switchCommitDirection != 0) switchCommitDirection else switchDirection
        val hadPreview = layoutSwitchPreview?.visibility == View.VISIBLE
        val hadOffset = switchOffsetY != 0f
        switchOffsetY = 0f
        switchDirection = 0
        switchCommitted = false
        switchCommitDirection = 0
        pendingSwitchSettle = false
        handler.removeCallbacks(switchSettleTimeout)
        if (!hadOffset && !hadPreview) return
        if (!animate) {
            cancelSwitchAnims()
            textureView.translationY = 0f
            danmakuView?.translationY = 0f
            layoutSwitchPreview?.translationY = 0f
            layoutSwitchPreview?.visibility = View.GONE
            return
        }
        if (hadPreview) {
            animateSwitchOffsetTo(0f, dir) {
                if (!switchCommitted) layoutSwitchPreview?.visibility = View.GONE
            }
        } else {
            animateSwitchOffsetTo(0f, dir)
        }
    }

    private fun animateSwitchOffsetTo(targetY: Float, direction: Int = 0, onEnd: (() -> Unit)? = null) {
        val previewTarget = previewOffsetFor(targetY, direction)
        textureView.animate()
            .translationY(targetY)
            .setDuration(SWITCH_ANIM_MS)
            .withEndAction { onEnd?.invoke() }
            .start()
        danmakuView?.animate()
            ?.translationY(targetY)
            ?.setDuration(SWITCH_ANIM_MS)
            ?.start()
        layoutSwitchPreview?.animate()
            ?.translationY(previewTarget)
            ?.setDuration(SWITCH_ANIM_MS)
            ?.start()
    }

    private fun applySwitchTransform() {
        cancelSwitchAnims()
        textureView.translationY = switchOffsetY
        danmakuView?.translationY = switchOffsetY
        layoutSwitchPreview?.translationY = previewOffsetFor(switchOffsetY)
    }

    private fun previewOffsetFor(offsetY: Float, direction: Int = 0): Float {
        val h = layoutFullscreen.height.toFloat()
        if (h <= 0) return 0f
        val dir = if (direction != 0) direction else if (offsetY < 0) 1 else -1
        return if (dir > 0) h + offsetY else -h + offsetY
    }
    private fun cancelSwitchAnims() {
        textureView.animate().cancel()
        danmakuView?.animate()?.cancel()
        layoutSwitchPreview?.animate()?.cancel()
    }

    private fun showSwitchPreview(direction: Int) {
        val video = callback?.onRequestNeighbor(direction) ?: run {
            layoutSwitchPreview?.visibility = View.GONE
            return
        }
        val preview = layoutSwitchPreview ?: return
        preview.visibility = View.VISIBLE
        tvSwitchTitle?.text = video.title
        tvSwitchAuthor?.text = video.author
        ivSwitchCover?.let { BiliCoverLoader.load(it, video.coverUrl) }
        val params = layoutSwitchInfo?.layoutParams as? FrameLayout.LayoutParams
        if (params != null) {
            params.gravity = if (direction > 0) Gravity.BOTTOM else Gravity.TOP
            layoutSwitchInfo?.layoutParams = params
        }
    }

    private fun updateSeekPreview(pos: Int, duration: Int) {
        if (seekBar.max != duration) seekBar.max = duration
        seekBar.progress = pos.coerceIn(0, duration)
        tvTime.text = context.getString(
            R.string.player_time_format,
            formatTime(pos),
            formatTime(duration)
        )
    }


    fun isDanmakuEnabled(): Boolean = danmakuEnabled

    fun setDanmakuEnabled(enabled: Boolean) {
        if (enabled == danmakuEnabled) return
        danmakuEnabled = enabled
        SpUtils.setDanmakuEnabled(activity, enabled)
        applyDanmakuVisibility()
        btnDanmakuToggle?.setColorFilter(
            if (enabled) Color.parseColor("#2196F3") else Color.WHITE
        )
        if (!enabled) {
            danmakuView?.stop()
            danmakuView?.clear()
            return
        }
        val view = danmakuView ?: return
        if (danmakuCid > 0 && !hasDanmakuData) {
            loadDanmaku(danmakuCid, SpUtils.getBiliCookie(activity), playRequestId.get())
            return
        }
        view.post {
            view.seek(getCurrentPosition().toLong())
            view.start()
            if (mediaPlayer?.isPlaying != true) view.setPaused(true)
        }
    }

    private fun applyDanmakuVisibility() {
        danmakuView?.visibility = if (danmakuEnabled) View.VISIBLE else View.GONE
    }

    private fun loadDanmaku(cid: Long, cookie: String, requestId: Int) {
        val view = danmakuView ?: return
        val myId = ++danmakuRequestId
        danmakuCid = cid
        hasDanmakuData = false
        view.clear()
        if (!danmakuEnabled) return
        cancelDanmakuRequest()
        val request = BiliDanmakuLoader.Request()
        danmakuRequest = request
        AppExecutors.io.execute {
            val items = BiliDanmakuLoader.fetch(cid, cookie, request)
            handler.post {
                if (danmakuRequest === request) danmakuRequest = null
                if (myId != danmakuRequestId) return@post
                if (requestId != playRequestId.get()) return@post
                if (request.cancelled) return@post
                if (items == null) {
                    hasDanmakuData = false
                    return@post
                }
                view.setData(items)
                hasDanmakuData = true
                if (!danmakuEnabled) return@post
                view.seek(getCurrentPosition().toLong())
                view.start()
            }
        }
    }

    private fun cancelDanmakuRequest() {
        danmakuRequest?.cancel()
        danmakuRequest = null
    }

    private fun startDanmakuSync() {
        stopDanmakuSync()
        val r = object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                if (mp.isPlaying && !isUserSeeking && seekCompleted && !isSeekingByGesture) {
                    syncDanmakuToPosition(mp.currentPosition)
                }
                handler.postDelayed(this, DANMAKU_SYNC_INTERVAL_MS)
            }
        }
        danmakuSyncRunnable = r
        handler.postDelayed(r, DANMAKU_SYNC_INTERVAL_MS)
    }

    private fun stopDanmakuSync() {
        danmakuSyncRunnable?.let { handler.removeCallbacks(it) }
        danmakuSyncRunnable = null
    }

    private fun syncDanmakuToPosition(posMs: Int) {
        danmakuView?.updatePosition(posMs.toLong())
    }

    fun onHostPause() {
        hostVisible = false
        if (mediaPlayer == null) return
        danmakuView?.setPaused(true)
    }

    fun onHostResume() {
        hostVisible = true
        val mp = mediaPlayer ?: return
        danmakuView?.setPaused(!mp.isPlaying)
    }

    private fun resumeDanmakuIfPlaying() {
        if (hostVisible && mediaPlayer?.isPlaying == true) danmakuView?.setPaused(false)
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        if (layoutFullscreen.visibility == View.VISIBLE) {
            handler.post {
                callback?.setSystemUIForFullscreen()
                updateControlsPositionForOrientation(newConfig.orientation)
                textureView.post { updateLayoutForOrientation() }
            }
        }
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            stopProgressUpdater()
            danmakuView?.setPaused(true)
        } else {
            mp.start()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            startProgressUpdater()
            danmakuView?.updatePosition(mp.currentPosition.toLong())
            danmakuView?.setPaused(false)
        }
        syncVideoNotificationState()
    }


    private fun attachVideoNotification() {
        if (videoNotificationActive) return
        videoNotificationActive = true
        VideoPlaybackController.attach(this)
        VideoPlaybackService.show(activity, currentVideoTitle, currentVideoAuthor)
        flushPendingLyric()
    }

    private fun detachVideoNotification() {
        VideoPlaybackController.detach(this)
        cancelLyricLoad()
        pendingLyric = null
        pendingLyricBvid = null
        if (!videoNotificationActive) return
        videoNotificationActive = false
        VideoPlaybackService.stop(activity)
    }

    private fun syncVideoNotificationState() {
        if (!videoNotificationActive) return
        VideoPlaybackService.updateState(activity, mediaPlayer?.isPlaying == true)
    }


    private fun loadLyric(bvid: String, cid: Long, durationSec: Int, requestId: Int) {
        if (lyricInFlight) return
        lyricInFlight = true
        AppExecutors.io.execute {
            val lyric = BiliLyricHelper.fetch(
                bvid = bvid,
                cid = cid,
                durationSec = durationSec,
                cookie = SpUtils.getBiliCookie(activity)
            )
            handler.post {
                if (requestId != playRequestId.get()) return@post
                pendingLyric = lyric
                pendingLyricBvid = bvid
                if (videoNotificationActive) {
                    VideoPlaybackService.attachLyric(activity, lyric)
                }
            }
        }
    }

    private fun flushPendingLyric() {
        val bvid = pendingLyricBvid ?: return
        if (bvid != currentBvid) {
            pendingLyric = null
            pendingLyricBvid = null
            return
        }
        val lyric = pendingLyric
        pendingLyric = null
        pendingLyricBvid = null
        VideoPlaybackService.attachLyric(activity, lyric)
    }

    private var pendingLyric: BiliLyric? = null
    private var pendingLyricBvid: String? = null

    private fun cancelLyricLoad() {
        lyricInFlight = false
    }

    private var lyricInFlight = false



    override fun pause() {
        runOnUiThread { if (mediaPlayer?.isPlaying == true) togglePlayPause() }
    }

    override fun resume() {
        runOnUiThread {
            val mp = mediaPlayer ?: return@runOnUiThread
            if (!mp.isPlaying) togglePlayPause()
        }
    }

    override fun switchPrevious() {
        runOnUiThread { callback?.onRequestSwitchPrevious() }
    }

    override fun switchNext() {
        runOnUiThread { callback?.onRequestSwitchNext() }
    }

    override fun getPosition(): Int = try {
        mediaPlayer?.currentPosition ?: 0
    } catch (_: IllegalStateException) {
        0
    }

    override fun getDuration(): Int = try {
        mediaPlayer?.duration ?: 0
    } catch (_: IllegalStateException) {
        0
    }

    override fun seekTo(positionMs: Int) {
        runOnUiThread {
            val mp = mediaPlayer ?: return@runOnUiThread
            val dur = getDuration()
            val target = if (dur > 0) positionMs.coerceIn(0, dur) else positionMs
            runCatching { mp.seekTo(target) }
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    private fun saveProgress(bvid: String, position: Int) {
        AppExecutors.io.execute {
            try {
                val dir = AppPaths.getProgressDir(activity)
                val file = File(dir, "$bvid.progress")
                AtomicFileWriter.writeText(file, position.toString())
            } catch (e: Exception) {
            }
        }
    }

    private fun startProgressSaver() {
        stopProgressSaver()
        progressSaveRunnable = object : Runnable {
            override fun run() {
                if (mediaPlayer?.isPlaying == true) {
                    saveCurrentProgress()
                }
                handler.postDelayed(this, progressSaveInterval)
            }
        }
        handler.postDelayed(progressSaveRunnable!!, progressSaveInterval)
    }

    private fun loadProgress(bvid: String): Int {
        try {
            val dir = AppPaths.getProgressDir(activity)
            val file = File(dir, "$bvid.progress")
            if (file.exists()) {
                val text = file.readText().trim()
                val pos = text.toIntOrNull() ?: 0
                return pos
            }
        } catch (e: Exception) {
        }
        return 0
    }

    fun saveCurrentProgress() {
        if (mediaPlayer?.isPlaying != true) return
        currentBvid?.let { bvid ->
            val pos = mediaPlayer?.currentPosition ?: 0
            if (pos > 0) saveProgress(bvid, pos)
        }
    }

    private fun toggleControlsVisibility() {
        if (isControlsVisible) hideAllControls() else showAllControls()
    }

    private fun showAllControls() {
        btnClose.visibility = View.VISIBLE
        tvTitle.visibility = View.VISIBLE
        btnPlayPause.visibility = View.VISIBLE
        seekBar.visibility = View.VISIBLE
        tvTime.visibility = View.VISIBLE
        btnOrientation.visibility = View.VISIBLE
        btnAddToHistory?.visibility = View.VISIBLE
        btnDanmakuToggle?.visibility = View.VISIBLE
        isControlsVisible = true
    }

    private fun hideAllControls() {
        btnClose.visibility = View.GONE
        tvTitle.visibility = View.GONE
        btnPlayPause.visibility = View.GONE
        seekBar.visibility = View.GONE
        tvTime.visibility = View.GONE
        btnOrientation.visibility = View.GONE
        btnAddToHistory?.visibility = View.GONE
        btnDanmakuToggle?.visibility = View.GONE
        isControlsVisible = false
    }

private fun setPlaybackSpeed(speed: Float) {
    val mp = mediaPlayer
    if (mp != null) {
        val wasPlaying = runCatching { mp.isPlaying }.getOrDefault(false)
        runCatching { mp.playbackParams = PlaybackParams().setSpeed(speed) }
        if (!wasPlaying && runCatching { mp.isPlaying }.getOrDefault(false)) {
            runCatching { mp.pause() }
        }
    }
    danmakuView?.setPlaybackSpeed(speed)
}

    private fun stopProgressSaver() {
        progressSaveRunnable?.let { handler.removeCallbacks(it) }
        progressSaveRunnable = null
    }

    private fun startProgressUpdater() {
        stopProgressUpdater()
        startDanmakuSync()
        progressUpdater = object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                if (mp.isPlaying && !isUserSeeking && seekCompleted && !isSeekingByGesture) {
                    syncDanmakuToPosition(mp.currentPosition)
                    seekBar.progress = mp.currentPosition
                    tvTime.text = context.getString(
                        R.string.player_time_format,
                        formatTime(mp.currentPosition),
                        formatTime(mp.duration)
                    )
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(progressUpdater!!)
        startProgressSaver()
    }

    private fun stopProgressUpdater() {
        progressUpdater?.let { handler.removeCallbacks(it) }
        progressUpdater = null
        stopDanmakuSync()
        stopProgressSaver()
    }

private fun toggleLandscape() {
    isForcedLandscape = !isForcedLandscape
    activity.requestedOrientation = if (isForcedLandscape) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

    private fun releaseMediaPlayer() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            setSurface(null)
            reset()
            release()
        }
        mediaPlayer = null
    }

    private fun resetDanmakuState() {
        danmakuRequestId++
        cancelDanmakuRequest()
        hasDanmakuData = false
        danmakuCid = 0L
        stopDanmakuSync()
        danmakuView?.stop()
        danmakuView?.clear()
    }

    private fun stopPlaybackAndClear() {
        isExpectedPrepare = false
        saveCurrentProgress()
        stopProgressUpdater()
        stopProgressSaver()
        resetDanmakuState()
        releaseMediaPlayer()
        detachVideoNotification()
        currentVideoUrl = ""
        currentVideoTitle = ""
        currentVideoAuthor = ""
        currentBvid = null
    }

    private fun isTouchOnControl(ev: MotionEvent): Boolean {
        val rawX = ev.rawX.toInt()
        val rawY = ev.rawY.toInt()
        val controls = mutableListOf<View>(btnClose, btnPlayPause, btnOrientation, seekBar)
        btnAddToHistory?.let { controls.add(it) }
        btnDanmakuToggle?.let { controls.add(it) }
        for (c in controls) {
            if (c.visibility != View.VISIBLE) continue
            val loc = IntArray(2)
            c.getLocationOnScreen(loc)
            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + c.width, loc[1] + c.height)
            if (rect.contains(rawX, rawY)) return true
        }
        return false
    }

    private fun updateControlsPositionForOrientation(orientation: Int) {
        val params = seekBar.layoutParams as? FrameLayout.LayoutParams
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            params?.bottomMargin = dpToPx(40)
        } else {
            params?.bottomMargin = dpToPx(8)
        }
        seekBar.requestLayout()
    }

    private data class PlayInfo(val url: String, val cid: Long)

    private fun fetchPlayInfo(bvid: String, cookie: String): PlayInfo? {
        try {
            val detail = BiliSearchHelper.getVideoDetail(bvid, cookie) ?: return null
            val cid = detail.cid
            val apiUrl = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=80&fnval=1&fnver=0&fourk=1"
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            if (json.optInt("code") != 0) return null
            val durl = json.getJSONObject("data").optJSONArray("durl")
            if (durl != null && durl.length() > 0) {
                return PlayInfo(durl.getJSONObject(0).getString("url"), cid)
            }
        } catch (e: Exception) {
        }
        return null
    }
    private fun prepareMediaPlayer(url: String, actualStartPos: Int, thisRequestId: Int) {
        mediaPlayer = MediaPlayer().apply {
            setScreenOnWhilePlaying(true)

            setOnSeekCompleteListener { mp ->
                isUserSeeking = false
                seekCompleted = true
                syncDanmakuToPosition(mp.currentPosition)
                seekBar.progress = mp.currentPosition
                tvTime.text = context.getString(
                    R.string.player_time_format,
                    formatTime(mp.currentPosition),
                    formatTime(mp.duration)
                )
            }

            setOnPreparedListener { mp ->
                if (thisRequestId != playRequestId.get()) {
                    mp.pause()
                    return@setOnPreparedListener
                }
                if (!isExpectedPrepare) return@setOnPreparedListener

                this@BiliVideoPlayer.videoWidth = mp.videoWidth
                this@BiliVideoPlayer.videoHeight = mp.videoHeight

                textureView.post { updateLayoutForOrientation() }

                if (actualStartPos in 1 until mp.duration) {
                    mp.seekTo(actualStartPos)
                }
                mp.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                onVideoSwitched(firstFrameReady = false)
                attachVideoNotification()
                seekBar.max = mp.duration
                tvTime.text = context.getString(
                    R.string.player_time_format,
                    formatTime(mp.currentPosition),
                    formatTime(mp.duration)
                )
                startProgressUpdater()
                hideAllControls()
                setPlaybackSpeed(1.0f)
                isExpectedPrepare = false
                resumeDanmakuIfPlaying()
            }

            setOnCompletionListener {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
                attachVideoNotification()
                danmakuView?.seek(0L)
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                seekBar.progress = 0
                tvTime.text = context.getString(
                    R.string.player_time_placeholder,
                    formatTime(mediaPlayer?.duration ?: 0)
                )
                startProgressUpdater()
                resumeDanmakuIfPlaying()
            }

            setOnErrorListener { _, what, extra ->
                resetSwitchDrag(force = true, animate = false)
                true
            }

            if (surface != null) setSurface(surface)

            try {
                val headers = mapOf(
                    "Referer" to "https://www.bilibili.com/",
                    "User-Agent" to "Mozilla/5.0"
                )
                setDataSource(activity, Uri.parse(url), headers)
                prepareAsync()
            } catch (e: Exception) {
                releaseMediaPlayer()
            }
        }
    }

    fun play(video: BiliVideo, startPosition: Int = 0) {
        synchronized(BiliVideoPlayer::class.java) {
            val oldPlayer = currentPlayingInstance?.get()
            if (oldPlayer != null && oldPlayer != this) {
                oldPlayer.close()
            }
            currentPlayingInstance = WeakReference(this)
        }

        stopPlaybackAndClear()
        isUserSeeking = false
        seekCompleted = true

        val bvid = video.bvid
        currentBvid = bvid
        val filePosition = loadProgress(bvid)
        val actualStartPos = if (startPosition > 0) startPosition else filePosition
        isExpectedPrepare = true

        val loadingDialog = DialogHelper.createLoadingDialog(activity, LanguageUtils.getString(activity, R.string.loading_video))
        loadingDialog.show()

        val thisRequestId = playRequestId.incrementAndGet()

        AppExecutors.io.execute {
            val cookie = SpUtils.getBiliCookie(activity)
            val info = fetchPlayInfo(bvid, cookie)

            handler.post {
                if (thisRequestId != playRequestId.get()) {
                    loadingDialog.dismiss()
                    detachVideoNotification()
                    return@post
                }
                loadingDialog.dismiss()
                if (info == null) {
                    resetSwitchDrag(force = true, animate = false)
                    Toast.makeText(activity, LanguageUtils.getString(activity, R.string.get_link_failed), Toast.LENGTH_SHORT).show()
                    detachVideoNotification()
                    return@post
                }
                val url = info.url

                currentVideoTitle = video.title
                currentVideoAuthor = video.author
                currentVideoUrl = url
                loadDanmaku(info.cid, cookie, thisRequestId)
                loadLyric(bvid, info.cid, parseDurationSeconds(video.duration), thisRequestId)
                prepareMediaPlayer(url, actualStartPos, thisRequestId)

                tvTitle.text = video.title
                layoutFullscreen.visibility = View.VISIBLE
                callback?.setSystemUIForFullscreen()
                activity.window.decorView.foreground = null
                callback?.onFullscreenOpened()
            }
        }
    }

    override fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    private fun parseDurationSeconds(raw: String): Int {
        val parts = raw.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 +
                (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> parts.getOrNull(0)?.toIntOrNull() ?: 0
        }
    }

    private fun getStatusBarHeight(): Int {
        val insets = activity.window.decorView.rootWindowInsets
        return insets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
    }

    private fun dpToPx(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()

    private fun formatTime(ms: Int): String {
        val sec = ms / 1000
        return String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60)
    }
}
