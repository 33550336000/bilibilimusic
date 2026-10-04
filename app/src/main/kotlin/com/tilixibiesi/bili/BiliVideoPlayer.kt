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

        /**
         * 查询相邻视频，供上下拖动时的预览层显示内容。
         *
         * @param direction -1=上一条（手指下滑）, +1=下一条（手指上滑）
         * @return 目标视频；该方向没有更多视频时返回 null（此时不显示预览层）
         */
        fun onRequestNeighbor(direction: Int): BiliVideo? = null

        /** 弹幕开关被点击后的通知（宿主据此同步按钮图标/提示） */
        fun onDanmakuToggled(enabled: Boolean) {}
    }

    var callback: Callback? = null

    /**
     * 供页面宿主（如 HorizontalPager.dragGuard）查询：全屏播放器是否已打开。
     *
     * 打开期间播放器需要完整接管触摸（拖动快进、上下滑切集、长按倍速），
     * 分页容器必须让行，否则一次横向拖动会被判成翻页，播放界面看起来「点了没反应」。
     */
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
    /** 弹幕开关按钮（与控制栏同显隐） */
    private var btnDanmakuToggle: ImageButton? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressUpdater: Runnable? = null
    private var isControlsVisible = true
    private var lastTapTime = 0L
    private val doubleTapInterval = 250L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchDownRawY = 0f
    /**
     * 当前是否存在一次"已收到 ACTION_DOWN"的手势。
     *
     * 系统接管手势后，应用可能只收到 ACTION_UP/CANCEL 而没有配对的 DOWN，
     * 此时 touchStartX/Y 还是**上一次**手势的残留值，据此算出的 dx/dy 毫无意义，
     * 却足以满足"纵向滑动切换视频"的条件。所有 MOVE/UP 处理都必须先校验这个标志。
     */
    private var gestureActive = false

    // ---- 上下滑动切换：跟手拖动 ----

    /** 目标视频预览层（位于视频之下，拖动时被让出来） */
    private var layoutSwitchPreview: FrameLayout? = null
    private var ivSwitchCover: ImageView? = null
    private var tvSwitchTitle: TextView? = null
    private var tvSwitchAuthor: TextView? = null
    private var layoutSwitchInfo: View? = null
    /** 本次纵向拖动当前预览的是哪一条（-1 上一条 / +1 下一条 / 0 无） */
    private var switchDirection = 0
    /**
     * 触发切换的位移阈值（占屏高比例）。
     *
     * 与"滑一下就切"的旧行为不同：现在必须拖过该比例才提交切换，
     * 否则回弹复位。这样即便系统手势（下拉通知栏、上滑退出）被误判成纵向滑动，
     * 也因为位移不够而只是回弹，不会真的换视频。
     */
    private val switchCommitRatio = 0.15f
    /** 纵向"跟手"位移当前值（px），用于位移与回弹动画 */
    private var switchOffsetY = 0f
    /** 已提交切换、但新视频尚未起播：此期间画面停在屏幕外、预览层居中显示 */
    private var switchCommitted = false
    /**
     * 已提交切换的方向（-1/+1），供收尾动画使用。
     *
     * 必须单独存一份：提交瞬间 [switchDirection] 会被清零（防连跳），
     * 而收尾时又要知道预览层该朝哪一侧滑走。若届时按"位移符号"去推断，
     * 位移已被归零，方向必然推断错误，预览层会往反方向飞出去。
     */
    private var switchCommitDirection = 0
    /**
     * 已提交切换、新视频首帧尚未上屏：此时先不把画面归位。
     *
     * 归位会让屏幕内容从"封面"切换成"视频画面"，若新视频还没渲染出第一帧，
     * 那一瞬间就是黑的。因此挂起，等 [onSurfaceTextureUpdated] 确认有帧了再收尾。
     */
    private var pendingSwitchSettle = false

    /** 本次手势已判定的模式（判定后不再改变，避免抖动） */
    private var seekGestureMode = GESTURE_NONE
    /** 快进锚点：进入快进时的手指 x 与播放位置，之后按此基准 1:1 连续跟手 */
    private var seekAnchorX = 0f
    private var seekAnchorPos = 0
    /** 最近一次计算出的目标位置，松手时落定用 */
    private var lastSeekTarget = Int.MIN_VALUE
    /** 屏幕拖动快进进行中：此期间定时刷新不得覆盖进度条 */
    private var isSeekingByGesture = false
    /** 拖动前控制栏是否可见，用于松手后恢复 */
    private var controlsVisibleBeforeSeek = true
    /** 松手后延时收起控制栏（拖动前是隐藏状态时才收） */
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
    private var isUserSeeking = false      // 用户是否正在拖动进度条
    private var seekCompleted = true
    /** 弹幕渲染层：由宿主在 initialize 时传入（放在视频之上、控制栏之下） */
    private var danmakuView: DanmakuView? = null
    /** 弹幕拉取的请求序号，防止切集时旧请求覆盖新集的弹幕 */
    private var danmakuRequestId = 0
    /** 在途弹幕请求：切集时用于真正取消（仅靠序号丢弃结果，请求本身仍会跑到超时） */
    private var danmakuRequest: BiliDanmakuLoader.Request? = null
    /** 弹幕时间轴的高频校正任务（见 [startDanmakuSync]） */
    private var danmakuSyncRunnable: Runnable? = null
    /** 当前视频的 cid：弹幕关闭期间也要记下，重新打开时才知道去哪拉 */
    private var danmakuCid: Long = 0L
    /** 当前是否已有弹幕数据（用于判断重新打开开关时需要补拉还是续播） */
    private var hasDanmakuData = false
    /** 弹幕开关（对应播放器上的弹幕按钮） */
    private var danmakuEnabled: Boolean = true
    private val longPressRunnable = Runnable {
        // 双保险：ACTION_DOWN 时已在播放，但这 800ms 内可能被暂停/播完
        // （缓冲停滞、切后台、播放结束）。此时不加速，也不置 longPressTriggered，
        // 于是松手时不会去"恢复"一个从未施加过的倍速。
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

    /**
     * 本次播放是否已挂出视频前台服务通知。
     *
     * 仅在「完整B站源」模式下为真：通知与音乐通知同形态（MediaStyle + 播放/暂停），
     * 起播时同时暂停音乐，关闭时撤下。非该模式下播放器完全不碰服务与通知。
     */
    private var videoNotificationActive = false
    /** 当前视频的 UP 主（通知大视图里显示在标题下方） */
    private var currentVideoAuthor: String = ""

companion object {
    private const val GESTURE_NONE = 0
    private const val GESTURE_SEEK = 1
    private const val GESTURE_VERTICAL = 2
    /** 手势方向判定阈值（px）：超过才认定为横向快进或纵向切集 */
    private const val GESTURE_SLOP_PX = 30f
    /** 松手后延时收起控制栏 */
    private const val HIDE_CONTROLS_DELAY_MS = 1500L
    /** 上下切换时画面平移/回弹的动画时长 */
    private const val SWITCH_ANIM_MS = 180L
    /**
     * 等待"新视频首帧上屏"的超时。
     *
     * 正常情况首帧在 prepared 后几十毫秒内就到；超过这个时间说明解码/流出了问题，
     * 此时无条件归位，避免封面永久占屏。
     */
    private const val SWITCH_SETTLE_TIMEOUT_MS = 1200L
    /** 该方向没有更多视频时，拖动位移的阻尼系数（橡皮筋手感） */
    private const val EDGE_RESISTANCE = 0.12f

    /**
     * 弹幕时间轴的校正周期。
     *
     * 50ms 远小于一帧的观感阈值，能把弹幕与画面的同步误差压到 ~50ms
     * （旧实现是 500ms，平均迟到 250ms）。这个定时器只读一次
     * `currentPosition` 并喂给弹幕层，开销可以忽略。
     */
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
        // 关键点：TextureView 必须不可点击。
        // 若它 clickable，触摸会先被子 View 消费，永远轮不到全屏容器的触摸监听，
        // 表现为「播放中点击/滑动全都没反应」。
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
                // 拖动时实时更新显示的时间，不更新播放器位置
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
                    // 暂时不改变 isUserSeeking 状态，等待 OnSeekCompleteListener 回调
                }
            }
        })

        // 全屏容器自带触摸处理：
        // 播放器作为「页面内的一个 View」存在时，没有 Activity.dispatchTouchEvent 可转发，
        // 而 TextureView 自身 clickable 却不做任何手势处理，事件就此被吞掉。
        // 这里直接在容器上接管，并同时吃掉 ACTION_DOWN，阻断父容器（分页）的拖拽判定。
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
            // 新视频已经真正渲染出画面：此刻才能把"滑出屏幕 + 封面顶替"的
            // 中间态收尾。在此之前保持封面可见，避免闪黑。
            if (pendingSwitchSettle) {
                pendingSwitchSettle = false
                if (switchCommitted) resetSwitchDrag(force = true, animate = false)
            }
        }
    }

private fun updateLayoutForOrientation() {
    val params = textureView.layoutParams as? FrameLayout.LayoutParams ?: return

    // 视频尺寸未就绪时，先铺满（后续会再次更新）
    if (videoWidth <= 0 || videoHeight <= 0) {
        params.width = FrameLayout.LayoutParams.MATCH_PARENT
        params.height = FrameLayout.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.CENTER
        textureView.layoutParams = params
        return
    }

    // 获取父容器的实际宽高
    val parentWidth = layoutFullscreen.width
    val parentHeight = layoutFullscreen.height
    if (parentWidth <= 0 || parentHeight <= 0) {
        // 尚未测量，延迟重试
        textureView.post { updateLayoutForOrientation() }
        return
    }

    // 计算缩放比例，取较小值保证视频完整可见
    val scaleX = parentWidth.toFloat() / videoWidth
    val scaleY = parentHeight.toFloat() / videoHeight
    val scale = Math.min(scaleX, scaleY)

    // 设置视图尺寸为缩放后的尺寸，并居中显示
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
    // 关闭时必须让画面与预览层彻底归位：否则下次打开视频会带着上一次的 translationY，
    // 或残留一张目标视频封面。这里直接赋值（不走动画），因为即将整体隐藏。
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

    /**
     * 顶部区域不参与长按加速的高度（px）。
     *
     * 顶部下拉是呼出通知栏的系统手势，在这个区域内不武装长按加速，
     * 避免"按住顶部下拉"被误判成长按。
     */
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
            // 新的一次手势：先清掉上一次可能残留的纵向拖动状态
            resetSwitchDrag()
            handler.removeCallbacks(hideControlsAfterSeekRunnable)
            if (touchDownRawY < topThreshold) {
                // 顶部区域不处理长按加速
            } else if (mediaPlayer?.isPlaying == true) {
                // 只有正在播放时才允许长按加速。暂停时"长按快进"没有意义，
                // 且 setPlaybackParams 有把暂停态拉起来播放的副作用（见 setPlaybackSpeed），
                // 干脆在源头不武装该手势。
                handler.postDelayed(longPressRunnable, 800)
            }
        }

        MotionEvent.ACTION_MOVE -> {
            // 没有配对的 DOWN：坐标是上一次手势的残留，任何判定都不可信
            if (!gestureActive) return true
            val dx = ev.x - touchStartX
            val dy = ev.y - touchStartY
            if (Math.abs(dx) > GESTURE_SLOP_PX || Math.abs(dy) > GESTURE_SLOP_PX) {
                handler.removeCallbacks(longPressRunnable)
            }
            // 长按加速已生效：本次手势被"锁定"为加速，不再参与快进/切集判定。
            //
            // 这是必须的：长按加速要求手指**先静止 800ms**，之后用户往往会顺势
            // 左右滑动。若不在这里拦截，那次滑动会被下面的方向判定认成
            // GESTURE_SEEK，松手时执行 seekTo —— 表现就是"加速时左右滑动会改变进度"。
            // 长按已经触发说明用户要的是"快进观看"，此时横向位移不应有其它语义。
            if (longPressTriggered) return true
            // 方向一旦判定就不再改变，避免拖到一半在横纵之间抖动
            if (seekGestureMode == GESTURE_NONE) {
                when {
                    Math.abs(dx) > GESTURE_SLOP_PX && Math.abs(dx) > Math.abs(dy) * 1.2f ->
                        seekGestureMode = GESTURE_SEEK
                    Math.abs(dy) > GESTURE_SLOP_PX && Math.abs(dy) > Math.abs(dx) * 1.2f ->
                        seekGestureMode = GESTURE_VERTICAL
                }
            }
            // 纵向：进入"跟手拖动切换"模式。
            //
            // 与旧实现的本质区别：旧实现只在 ACTION_UP 里按位移判断，
            // 一松手就切换，过程中没有任何视觉反馈；现在拖动期间视频画面
            // 1:1 跟随手指平移，露出下层的目标视频预览，松手时再按阈值决定
            // 提交切换还是回弹。
            if (seekGestureMode == GESTURE_VERTICAL) {
                updateSwitchDrag(dy)
                return true
            }
            if (seekGestureMode == GESTURE_SEEK) {
                val duration = mediaPlayer?.duration ?: 0
                if (duration > 0) {
                    // 进入快进的那一瞬建立锚点：之后每次 MOVE 都基于「按下锚点 + 当前偏移」
                    // 计算绝对目标位置，而不是在上一个落点上继续累加。
                    // 旧实现每累计 30px 才 seek 一次并重置起点，位移被量化成台阶
                    //（1080px 屏 + 6 分钟视频 ≈ 10s/步），表现为「一次跳十几秒」。
                    if (lastSeekTarget == Int.MIN_VALUE) {
                        isSeekingByGesture = true
                        seekAnchorX = touchStartX
                        seekAnchorPos = mediaPlayer?.currentPosition ?: 0
                        controlsVisibleBeforeSeek = isControlsVisible
                        // 播放开始后控制栏是隐藏的，若拖动时不显示，
                        // 用户只能看到画面跳变、看不到进度与时间 → 正是「一次跳十秒」的观感来源
                        if (!isControlsVisible) showAllControls()
                    }
                    val viewWidth = textureView.width.takeIf { it > 0 } ?: return true
                    val travel = ev.x - seekAnchorX
                    val newPos = (seekAnchorPos + travel / viewWidth * duration).toInt()
                        .coerceIn(0, duration)
                    lastSeekTarget = newPos
                    // 拖动期间只做预览（进度条 + 时间文本），不调用 seekTo：
                    // 播放保持继续，避免反复定位造成的卡顿与画面闪烁；
                    // 真正跳转放在松手时执行一次（与拖动 SeekBar 的行为一致）。
                    updateSeekPreview(newPos, duration)
                }
            }
        }

        // ACTION_CANCEL 必须与 ACTION_UP 分开处理，绝不能共用一套收尾逻辑。
        //
        // 这是「从底部上滑退出时误触发视频切换」的根因：当 SystemUI 接管手势
        //（呼出状态栏/导航栏、返回手势）时，应用收到的是 ACTION_DOWN 之后紧跟
        // 一个 **ACTION_CANCEL**，而不是 ACTION_UP。旧实现把两者合并，
        // 于是被系统吃掉的手势照样跑完了「上下滑动切换视频」等分支。
        //
        // 取消语义 = 本次手势作废：只做状态复位，不产生任何副作用。
        MotionEvent.ACTION_CANCEL -> {
            handler.removeCallbacks(longPressRunnable)
            if (longPressTriggered) {
                setPlaybackSpeed(1.0f)
                longPressTriggered = false
            }
            // 手势被系统接管：纵向拖动回弹复位，进度预览丢弃。
            // 两者都**不提交**任何切换/跳转——这正是"系统手势不会换视频"的保证。
            cancelSwitchDrag()
            cancelGestureState()
            gestureActive = false
            return true
        }

        MotionEvent.ACTION_UP -> {
            handler.removeCallbacks(longPressRunnable)
            // 没有配对的 DOWN：同上，必须丢弃。否则 SystemUI 吞掉 DOWN 后，
            // 一个孤立的 UP 会拿残留坐标触发切集/快进。
            if (!gestureActive) return true
            gestureActive = false
            // 本次手势是否属于"长按加速"。必须在复位前取下来：
            // 加速期间用户顺势的横/纵滑动都只应结束加速，**不得**再产生
            // 快进（seekTo）或切换上/下一条视频等副作用。
            val wasLongPress = longPressTriggered
            if (wasLongPress) {
                setPlaybackSpeed(1.0f)
                longPressTriggered = false
            }

            val dx = ev.x - touchStartX
            val dy = ev.y - touchStartY

            // 纵向拖动松手：按位移阈值决定"提交切换"还是"回弹复位"。
            // 只有在 MOVE 阶段被判定为纵向时才会走到这里（方向不再重判）。
            //
            // 注意这里**不再**做顶部/底部防误触判定：系统手势（下拉通知栏、
            // 上滑退出）的位移通常远小于阈值，会自然回弹；保留坐标保护反而
            // 会在横屏等场景下误伤正常操作。
            if (seekGestureMode == GESTURE_VERTICAL) {
                finishSwitchDrag(dy)
                return true
            }

            // 长按加速手势到此为止：只负责恢复倍速，其余判定全部跳过。
            // （ACTION_MOVE 已锁定该手势，这里再兜一次，防止松手瞬间的位移被误判）
            if (wasLongPress) {
                lastSeekTarget = Int.MIN_VALUE
                seekGestureMode = GESTURE_NONE
                isSeekingByGesture = false
                return true
            }

            // 快进手势结束：此处才真正跳转（拖动全程只预览、播放不中断）
            if (seekGestureMode == GESTURE_SEEK && lastSeekTarget != Int.MIN_VALUE) {
                val duration = mediaPlayer?.duration ?: 0
                if (duration > 0) {
                    val finalPos = lastSeekTarget.coerceIn(0, duration)
                    if (finalPos != (mediaPlayer?.currentPosition ?: -1)) {
                        mediaPlayer?.seekTo(finalPos)
                    }
                    updateSeekPreview(finalPos, duration)
                    // 手势快进同样走 seek 语义（清屏重排），否则弹幕会卡在旧时间点
                    danmakuView?.seek(finalPos.toLong())
                }
                lastSeekTarget = Int.MIN_VALUE
                seekGestureMode = GESTURE_NONE
                isSeekingByGesture = false
                // seekTo 是异步的：在 OnSeekComplete 之前不要让定时刷新覆盖进度条，
                // 否则最后一帧会被拉回旧位置，产生「松手后回跳」的错觉
                seekCompleted = false
                // 恢复原状：仅当拖动前控制栏是隐藏的，才延时收起；原本可见则保持可见
                handler.removeCallbacks(hideControlsAfterSeekRunnable)
                if (!controlsVisibleBeforeSeek) {
                    handler.postDelayed(hideControlsAfterSeekRunnable, HIDE_CONTROLS_DELAY_MS)
                }
                return true
            }

            // 轻点判断（dx, dy 均小于 10 像素）
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
    /**
     * 手势被取消（ACTION_CANCEL）时的状态复位。
     *
     * 只复位状态，**不执行任何动作**：不 seek、不切集、不切换播放。
     * 系统接管手势（呼出状态栏/导航栏、返回手势）时应用收到的就是 CANCEL，
     * 把它当 UP 处理会让"从底部上滑"这类系统操作误触发播放器的副作用。
     *
     * 预览（进度条/时间文本）会被复位到真实播放位置：
     * 拖动期间只做预览不 seekTo，若取消后不还原，进度条会停在预览位置，
     * 与画面实际进度不一致。
     */
    private fun cancelGestureState() {
        if (isSeekingByGesture) {
            val mp = mediaPlayer
            if (mp != null) {
                runCatching { updateSeekPreview(mp.currentPosition, mp.duration) }
            }
            // 取消后控制栏的恢复规则与"拖动结束"一致
            handler.removeCallbacks(hideControlsAfterSeekRunnable)
            if (!controlsVisibleBeforeSeek) {
                handler.postDelayed(hideControlsAfterSeekRunnable, HIDE_CONTROLS_DELAY_MS)
            }
        }
        lastSeekTarget = Int.MIN_VALUE
        seekGestureMode = GESTURE_NONE
        isSeekingByGesture = false
    }

    // ==================== 上下滑动切换（跟手拖动） ====================
    //
    // 设计目标：把"上下滑切集"从「一滑就切」改成「跟手拖动 + 过阈值才切」。
    //
    // 为什么这样更稳：切换动作只在**手指抬起且位移超过阈值**时发生。
    // 下拉通知栏、上滑退出等系统手势即便被识别成纵向滑动，位移通常也不足以越过
    // 阈值，松手后只会回弹复位，不会真的换视频——因此不再依赖"防误触区"那套
    // 脆弱的坐标保护，行为对用户也更好预测。
    //
    // 预览层位于视频之下：视频画面平移后，下层的目标视频从被让出的边缘露出。

    /** 纵向位移映射到画面平移量：向下拖（dy>0）看的是**上一条**，画面下移 */
    private fun updateSwitchDrag(dy: Float) {
        // 已提交切换、正在取链/缓冲：画面与预览层都处于"呈现代替品"的中间态，
        // 此时不允许新的拖动去挪动它，否则会把预览层推走、露出黑底。
        if (switchCommitted) return
        if (layoutFullscreen.height <= 0) return
        // -1 = 上一条（手指下滑，画面下移），+1 = 下一条（手指上滑，画面上移）
        val direction = if (dy > 0) -1 else 1
        if (direction != switchDirection) {
            switchDirection = direction
            showSwitchPreview(direction)
        }
        // 该方向没有更多视频时加阻尼：只让出一点点，给出"到头了"的触感反馈，
        // 松手后必然回弹（finishSwitchDrag 会因 neighbor == null 而拒绝提交）。
        val hasNeighbor = callback?.onRequestNeighbor(direction) != null
        switchOffsetY = if (hasNeighbor) dy else dy * EDGE_RESISTANCE
        applySwitchTransform()
    }

    /** 松手：位移越过阈值则提交切换，否则回弹复位 */
    private fun finishSwitchDrag(dy: Float) {
        // 已经提交过一次切换（正在取链/缓冲）：本次手势不得再触发第二次切换。
        // 这里必须显式拦截——ACTION_DOWN 里的 resetSwitchDrag 在提交期间是空操作，
        // switchDirection 会被保留，仅靠 updateSwitchDrag 的守卫不足以拦住本方法。
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
            // 提交切换。
            //
            // 关键：这里**不能**在同一帧里既设置位移又复位——那样 View 还没
            // 重新绘制就被归位，动画根本不会出现（画面直接硬切）。
            // 正确做法是把视频滑出屏幕、让已显示目标封面的预览层滑到正中，
            // 并一直停在这个状态，等新视频起播时再由 [onVideoSwitched] 收尾。
            // 于是"取链 + 缓冲"这段时间里用户看到的是目标视频的封面，
            // 而不是黑屏或上一集的残影。
            switchCommitted = true
            switchCommitDirection = dir
            switchOffsetY = if (dy > 0) containerH.toFloat() else -containerH.toFloat()
            // 立刻清掉方向：否则松手后若还有残留的 MOVE/UP，
            // finishSwitchDrag 会拿着同一个方向再切一次（连跳两集）。
            switchDirection = 0
            animateSwitchOffsetTo(switchOffsetY, dir)
            if (dir > 0) callback?.onRequestSwitchNext()
            else callback?.onRequestSwitchPrevious()
            return
        }
        resetSwitchDrag()
    }

    /** 取消：无条件回弹复位，绝不提交切换 */
    private fun cancelSwitchDrag() {
        resetSwitchDrag()
    }

    /**
     * 新视频已就绪：把滑出屏幕的画面归位，并收起预览层。
     *
     * **必须是"瞬间归位"而不是动画**：提交切换时已经播过一次"视频滑出、封面滑入"
     * 的动画（[finishSwitchDrag]）。如果这里再 animate 着滑回来，用户就会看到
     * 一次滑动触发**两段**转场动画（先滑出、再滑回），非常突兀。
     *
     * 提交后的画面状态是"视频整屏停在屏幕外、预览层正好居中"，
     * 而归位后的目标是"视频居中、预览层滑走"。因为此时屏幕上的可见内容
     * 会从"封面"直接换成"新视频第一帧"，所以归位动作要在
     * **新视频首帧真正渲染出来之后**再做，否则会闪一下黑屏。
     * 这个时机由 [onSurfaceTextureUpdated] 给出（见 [pendingSwitchSettle]）。
     *
     * @param firstFrameReady 新视频首帧是否已经上屏
     */
    fun onVideoSwitched(firstFrameReady: Boolean = false) {
        if (!switchCommitted) {
            // 并未处于切换中间态（例如普通起播、或加载失败后的兜底调用）：
            // 直接清理，不做任何动画
            resetSwitchDrag(force = true, animate = false)
            return
        }
        if (!firstFrameReady) {
            // 还不能归位（首帧未上屏）：先挂起，等 onSurfaceTextureUpdated 再收尾。
            // 期间封面继续顶替画面，用户看不到黑屏。
            pendingSwitchSettle = true
            // 兜底：若因解码异常/流卡住而始终没有首帧，不能让封面永久占屏。
            // 超时后无条件归位（此时即便短暂黑屏，也比卡死在一张静态图上好）。
            handler.removeCallbacks(switchSettleTimeout)
            handler.postDelayed(switchSettleTimeout, SWITCH_SETTLE_TIMEOUT_MS)
            return
        }
        pendingSwitchSettle = false
        handler.removeCallbacks(switchSettleTimeout)
        resetSwitchDrag(force = true, animate = false)
    }

    /** 首帧超时兜底：无条件结束切换中间态（见 [onVideoSwitched]） */
    private val switchSettleTimeout = Runnable {
        if (pendingSwitchSettle) {
            pendingSwitchSettle = false
            resetSwitchDrag(force = true, animate = false)
        }
    }

    /**
     * 复位纵向拖动的全部状态：画面归位、预览层收起。
     *
     * @param force 已提交切换但新视频尚未起播时（[switchCommitted] == true），
     *        画面本来就该停在屏幕外、由预览层顶替。此时任何"顺手"的复位
     *        （例如缓冲期间用户又点了一下屏幕触发的 ACTION_DOWN）都必须被忽略，
     *        否则会把已经释放掉的上一集画面拉回来盖住预览层。
     *        只有 [onVideoSwitched] 会以 `force = true` 真正执行复位。
     * @param animate 是否播放"滑回原位"的过渡。
     *        提交切换后的收尾必须传 `false`：那时已经播过一段转场动画，
     *        再 animate 回来会让一次滑动出现两段动画。
     */
    private fun resetSwitchDrag(force: Boolean = false, animate: Boolean = true) {
        if (switchCommitted && !force) return
        // 收尾方向：优先用提交时记录的方向。提交后 switchDirection 已清零、
        // switchOffsetY 也即将归零，两者都推断不出方向，否则预览层会朝反方向滑走。
        val dir = if (switchCommitDirection != 0) switchCommitDirection else switchDirection
        val hadPreview = layoutSwitchPreview?.visibility == View.VISIBLE
        val hadOffset = switchOffsetY != 0f
        switchOffsetY = 0f
        switchDirection = 0
        switchCommitted = false
        switchCommitDirection = 0
        pendingSwitchSettle = false
        // 中间态已经结束：撤销可能仍在排队的首帧超时兜底，避免它在后续手势上误触发
        handler.removeCallbacks(switchSettleTimeout)
        // 本来就没有位移也没有预览层：无需动画，直接返回
        if (!hadOffset && !hadPreview) return
        if (!animate) {
            // 瞬间归位：视频与预览层在同一帧内到位，不产生第二段动画。
            // 直接把预览层隐藏即可（此刻新视频首帧已经上屏）。
            cancelSwitchAnims()
            textureView.translationY = 0f
            danmakuView?.translationY = 0f
            layoutSwitchPreview?.translationY = 0f
            layoutSwitchPreview?.visibility = View.GONE
            return
        }
        if (hadPreview) {
            // 视频滑回原位的同时预览层滑走，动画结束后再隐藏：
            // 提前隐藏会露出容器黑底，看起来像"闪了一下黑屏"。
            animateSwitchOffsetTo(0f, dir) {
                if (!switchCommitted) layoutSwitchPreview?.visibility = View.GONE
            }
        } else {
            animateSwitchOffsetTo(0f, dir)
        }
    }

    /**
     * 把视频层（含弹幕层、预览层）平滑移动到指定纵向位移。
     *
     * @param targetY 视频层的目标位移（0=归位，±H=完全移出屏幕）
     * @param direction 本次纵向拖动的方向；为 0 时按 [targetY] 符号推断
     */
    private fun animateSwitchOffsetTo(targetY: Float, direction: Int = 0, onEnd: (() -> Unit)? = null) {
        val previewTarget = previewOffsetFor(targetY, direction)
        textureView.animate()
            .translationY(targetY)
            .setDuration(SWITCH_ANIM_MS)
            .withEndAction { onEnd?.invoke() }
            .start()
        // 弹幕层跟随视频一起平移，否则视频动了弹幕不动会很割裂
        danmakuView?.animate()
            ?.translationY(targetY)
            ?.setDuration(SWITCH_ANIM_MS)
            ?.start()
        layoutSwitchPreview?.animate()
            ?.translationY(previewTarget)
            ?.setDuration(SWITCH_ANIM_MS)
            ?.start()
    }

    /** 拖动过程中即时跟手（不走动画，保证 1:1 跟随手指） */
    private fun applySwitchTransform() {
        cancelSwitchAnims()
        textureView.translationY = switchOffsetY
        danmakuView?.translationY = switchOffsetY
        // 预览层同样平移，做出"下一条从边缘滑进来"的效果：
        // 若预览层固定不动、只挪视频，看到的是"掀开幕布露出静止画面"，
        // 与"下一个视频被拉进来"的直觉不符。
        layoutSwitchPreview?.translationY = previewOffsetFor(switchOffsetY)
    }

    /**
     * 预览层应处的纵向位置。
     *
     * 让预览层的边缘始终贴住视频让出的那一条：
     *  - 上滑看下一条（offsetY<0）：预览从**屏幕下方**滑入，offsetY=-H 时正好归位 0；
     *  - 下滑看上一条（offsetY>0）：预览从**屏幕上方**滑入，offsetY=+H 时归位 0。
     *
     * `offsetY == 0` 时方向无从判断（回弹终点），因此额外接受 [direction]：
     * 回弹/提交动画必须显式提供方向，否则预览会朝**相反**的一侧滑走。
     */
    private fun previewOffsetFor(offsetY: Float, direction: Int = 0): Float {
        val h = layoutFullscreen.height.toFloat()
        if (h <= 0) return 0f
        // 方向优先取显式参数；未提供时退回"由位移符号推断"
        val dir = if (direction != 0) direction else if (offsetY < 0) 1 else -1
        return if (dir > 0) h + offsetY else -h + offsetY
    }
    private fun cancelSwitchAnims() {
        textureView.animate().cancel()
        danmakuView?.animate()?.cancel()
        layoutSwitchPreview?.animate()?.cancel()
    }

    /** 显示目标视频的预览层（封面 + 标题/作者） */
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
        // 信息条吸附到"被让出"的那一侧：上滑看下一条→视频上移、下方露出→信息条在底部。
        // 用 layout_gravity 切换，保证它始终出现在可见的那半屏。
        val params = layoutSwitchInfo?.layoutParams as? FrameLayout.LayoutParams
        if (params != null) {
            params.gravity = if (direction > 0) Gravity.BOTTOM else Gravity.TOP
            layoutSwitchInfo?.layoutParams = params
        }
    }

    /**
     * 拖动期间同步进度条与时间文本。
     *
     * 必须同时绕开定时刷新（isUserSeeking）与 seek 完成回调，
     * 否则拖到一半会被定时更新拉回播放器的实际位置，产生「跳回去」的观感。
     */
    private fun updateSeekPreview(pos: Int, duration: Int) {
        if (seekBar.max != duration) seekBar.max = duration
        seekBar.progress = pos.coerceIn(0, duration)
        tvTime.text = context.getString(
            R.string.player_time_format,
            formatTime(pos),
            formatTime(duration)
        )
    }

    // ==================== 弹幕 ====================

    /** 弹幕当前是否开启（供宿主同步按钮图标） */
    fun isDanmakuEnabled(): Boolean = danmakuEnabled

    /**
     * 切换弹幕显示并持久化，下次进入视频沿用。
     *
     * 重新开启时**必须重新走一次 start()**（而不是指望绘制循环还活着）：
     * 关闭时调了 stop()，它把 `running` 置 false 并移除了回调，
     * 光把 View 设成 VISIBLE 是不会有任何东西被画出来的。
     */
    fun setDanmakuEnabled(enabled: Boolean) {
        if (enabled == danmakuEnabled) return
        danmakuEnabled = enabled
        SpUtils.setDanmakuEnabled(activity, enabled)
        applyDanmakuVisibility()
        // 蓝色=开，白色=关，与其它控制按钮的取色习惯一致
        btnDanmakuToggle?.setColorFilter(
            if (enabled) Color.parseColor("#2196F3") else Color.WHITE
        )
        if (!enabled) {
            danmakuView?.stop()
            danmakuView?.clear()
            return
        }
        val view = danmakuView ?: return
        // 数据可能还没拉（进入视频时开关是关的，loadDanmaku 当时直接跳过了网络请求）
        if (danmakuCid > 0 && !hasDanmakuData) {
            loadDanmaku(danmakuCid, SpUtils.getBiliCookie(activity), playRequestId.get())
            return
        }
        // 刚从 GONE 切回 VISIBLE，此刻 View 还没走完 layout（width/height 仍是 0），
        // 直接 seek 会因为量不到尺寸而回填不出任何东西。等布局就绪再回填。
        view.post {
            view.seek(getCurrentPosition().toLong())
            view.start()
            if (mediaPlayer?.isPlaying != true) view.setPaused(true)
        }
    }

    /** 弹幕层只在开启时挂载，关闭时彻底移出视图层级 */
    private fun applyDanmakuVisibility() {
        danmakuView?.visibility = if (danmakuEnabled) View.VISIBLE else View.GONE
    }

    /**
     * 拉取弹幕。在 play() 拿到 cid 后调用；网络在后台线程，结果回主线程装载。
     *
     * 双重失效判定：
     *  - [myId] 防止连点下一个视频时「先发后到」的请求覆盖最新一集的弹幕；
     *  - requestId 防止视频已切换后旧请求仍然装载。
     */
    private fun loadDanmaku(cid: Long, cookie: String, requestId: Int) {
        val view = danmakuView ?: return
        val myId = ++danmakuRequestId
        danmakuCid = cid
        hasDanmakuData = false
        view.clear()          // 只清屏幕，已加载的数据在 setData 时才替换
        if (!danmakuEnabled) return   // 关着就不发请求，等打开时再补拉
        // 取消上一个仍在途的请求：否则切集后旧请求会继续占着 IO 线程跑到超时
        cancelDanmakuRequest()
        val request = BiliDanmakuLoader.Request()
        danmakuRequest = request
        AppExecutors.io.execute {
            val items = BiliDanmakuLoader.fetch(cid, cookie, request)
            handler.post {
                if (danmakuRequest === request) danmakuRequest = null
                if (myId != danmakuRequestId) return@post        // 已被更新的请求取代
                if (requestId != playRequestId.get()) return@post // 视频已切换
                if (request.cancelled) return@post
                // 拉取失败（超时/风控/无网络）与"该视频确实没有弹幕"必须区分：
                // 旧实现把两者都标记成 hasDanmakuData = true，一次网络抖动就会让
                // 这个视频整个会话再也拉不到弹幕（反复开关也没用）。
                if (items == null) {
                    hasDanmakuData = false   // 允许下次打开开关时重试
                    return@post
                }
                view.setData(items)
                hasDanmakuData = true
                // 拉取期间用户可能又关掉了开关，这里必须尊重最新状态
                if (!danmakuEnabled) return@post
                // 直接 seek：尺寸未就绪时 DanmakuView 会自己挂起并在
                // onSizeChanged 里补做（首次播放时本方法先于
                // `layoutFullscreen.visibility = VISIBLE` 执行）。
                view.seek(getCurrentPosition().toLong())
                view.start()
            }
        }
    }

    /** 取消在途的弹幕请求；没有在途请求时空转 */
    private fun cancelDanmakuRequest() {
        danmakuRequest?.cancel()
        danmakuRequest = null
    }

    /**
     * 弹幕时间轴的高频校正（每 50ms）。
     *
     * 旧实现只有 500ms 的进度条刷新顺带校正一次，于是弹幕时间轴最坏滞后 500ms、
     * 平均迟到约 250ms（`startClock` 取的是投放那一刻，而投放由 `videoTimeMs` 决定）。
     * 对卡点、音乐类视频这是肉眼可见的不同步。
     *
     * 这里把校正拆出来单独跑高频定时器：**只喂弹幕时间轴**，不碰进度条/时间文本
     * （那些保持 500ms，避免无谓的 UI 开销）。`updatePosition` 内部有双向阈值，
     * 小抖动不会触发重排，因此高频调用是安全的。
     */
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

    /** 用播放器的真实位置校正弹幕时钟（内部有阈值，小幅漂移不会触发清屏） */
    private fun syncDanmakuToPosition(posMs: Int) {
        danmakuView?.updatePosition(posMs.toLong())
    }

    /**
     * 宿主（页面/Activity）退到后台时调用。
     *
     * 弹幕渲染由 Choreographer 帧回调驱动，页面不可见时**必须停掉**：
     * 视频通知是前台服务、进程不会被回收，于是帧回调会在后台一直空转，
     * 每秒 60 次全屏重绘纯粹耗电。这里只冻结弹幕（保留屏上内容与时钟），
     * 不触碰播放状态——是否继续出声由音频焦点/用户决定。
     */
    fun onHostPause() {
        danmakuView?.setPaused(true)
    }

    /** 宿主回到前台时调用：仅当播放器确实在播放时才恢复弹幕渲染 */
    fun onHostResume() {
        danmakuView?.setPaused(mediaPlayer?.isPlaying != true)
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
            // 恢复播放时以播放器真实位置校正一次，抵消暂停期间弹幕时钟的停滞
            danmakuView?.updatePosition(mp.currentPosition.toLong())
            danmakuView?.setPaused(false)
        }
        syncVideoNotificationState()
    }

    // ==================== 视频播放服务通知 ====================

    /**
     * 起播成功后挂出视频通知（标题 = 视频标题）并托管控制面。
     *
     * 挂在「prepare 完成、mp.start() 之后」而不是 play() 开头：
     * 取链可能失败（Toast + 无播放），那时不该留下一条空通知。
     */
    private fun attachVideoNotification() {
        if (videoNotificationActive) return
        // 任何模式下都挂前台服务通知（含进度条）：
        // 原先仅「完整B站源」模式才挂，导致普通模式下看视频完全没有通知，
        // 也无法从锁屏/通知栏控制或拖动进度。
        videoNotificationActive = true
        VideoPlaybackController.attach(this)
        VideoPlaybackService.show(activity, currentVideoTitle, currentVideoAuthor)
    }

    /** 收起通知并摘掉控制面；通知本就没挂出时空转 */
    private fun detachVideoNotification() {
        VideoPlaybackController.detach(this)
        // 在途的歌词请求一并作废：本视频的通知都要撤了，
        // 它的歌词自然也不该再推给下一条通知
        cancelLyricLoad()
        if (!videoNotificationActive) return
        videoNotificationActive = false
        VideoPlaybackService.stop(activity)
    }

    /** 播放器自身状态变化（画面上点击暂停/继续）后同步通知文案与按钮 */
    private fun syncVideoNotificationState() {
        if (!videoNotificationActive) return
        VideoPlaybackService.updateState(activity, mediaPlayer?.isPlaying == true)
    }

    // ==================== 歌词 ====================

    /**
     * 拉取当前视频的歌词，成功后交给视频通知服务。
     *
     * 在**弹幕之后紧跟着**发起（都在同一个 io 线程里）：
     * 歌词与弹幕一样不在"正在等画面"的关键路径上，慢一点不影响起播。
     *
     * [BiliLyricHelper] 主链路走的是「B 站音乐曲库」，与视频有没有字幕、
     * 有没有弹幕都无关，也不要求登录态，因此大多数情况下能直接命中。
     *
     * @param bvid        稿件号
     * @param cid         当前分 P 的 cid（曲库歌词只对应 P1，即顶层 cid）
     * @param durationSec 视频总时长（秒），仅用于时间轴合理性兜底校验
     * @param requestId   发起时的播放请求序号，用于丢弃过期的歌词
     */
    private fun loadLyric(bvid: String, cid: Long, durationSec: Int, requestId: Int) {
        // 同一视频只拉一次：setOnPreparedListener 与 setOnCompletionListener
        // 都会走到 attachVideoNotification → 这里是它们的下游，
        // 而单曲循环播完一轮不该把歌词接口重打一遍
        if (lyricInFlight) return
        lyricInFlight = true
        AppExecutors.io.execute {
            val lyric = BiliLyricHelper.fetch(
                bvid = bvid,
                cid = cid,
                durationSec = durationSec,
                // 字幕兜底需要登录态（不带 Cookie 时 wbi/v2 的轨道恒为 0）；
                // 曲库链路用不到它，但传上去没有副作用
                cookie = SpUtils.getBiliCookie(activity)
            )
            handler.post {
                // 已切视频 / 播放器已关闭：丢弃，别把旧歌词推给新通知。
                // 与弹幕的校验方式一致（都拿 playRequestId 比对），
                // 因为歌词同样可能比切视频的收尾晚回来。
                if (requestId != playRequestId.get()) return@post
                if (!videoNotificationActive) return@post
                // 传 null 也是有效信息：服务据此明确"没有歌词"，退回默认文案
                VideoPlaybackService.attachLyric(activity, lyric)
            }
        }
    }

    /**
     * 作废在途的歌词请求并允许下一次重新拉取。
     *
     * 在途请求的回调会拿 [playRequestId] 比对（与弹幕同一套校验），
     * 切视频时那个序号已被顶掉，因此这里的清标记不会让旧结果漏进来。
     */
    private fun cancelLyricLoad() {
        lyricInFlight = false
    }

    /** 在途歌词请求标记：仅用于避免同一视频重复发起 */
    private var lyricInFlight = false

    // ---------- VideoPlaybackController.Target ----------

    // isPlaying() 本类已有（供搜索页查询播放态），签名与 Target 一致，直接复用，
    // 不再重复声明，否则同名同参会被判定为冲突重载。

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
        // 播放器已释放/处于 Idle：读位置会抛异常，按 0 处理让通知不画进度
        0
    }

    override fun getDuration(): Int = try {
        mediaPlayer?.duration ?: 0
    } catch (_: IllegalStateException) {
        0
    }

    /**
     * 通知拖动进度条 → 定位。
     *
     * 复用播放器已有的 seek 语义（`mediaPlayer.seekTo` + OnSeekComplete 收尾），
     * 不额外维护一套状态：OnSeekComplete 会把 isUserSeeking/seekCompleted 复位，
     * 并顺带校正弹幕时钟与进度条，与用户直接在画面上拖动完全一致。
     *
     * 刻意**不**设置 isUserSeeking = true：那个标志是「屏幕上的 SeekBar 正在被拖」
     * 的语义，用于阻止定时刷新覆盖画面控件；通知拖动不碰画面，
     * 置位反而会让画面进度条在视频播放期间僵住（定时器一直被挡）。
     */
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
        // 弹幕开关与其它控制按钮同排，必须一起显隐；
        // 但关闭弹幕期间不显示该按钮（B 站：弹幕关了，按钮仍在与不在由控制栏决定，
        // 这里选择跟随控制栏，保持"控制栏出现时所有开关都在"的可预期行为）
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
        // setPlaybackParams 有一个反直觉的副作用：**播放器处于暂停态时，只要传入非 0 速度，
        // 它会顺带把播放器拉起来开始播放**（Android 文档原文："If the object is in paused
        // state, calling this method with a non-zero speed will resume the playback"）。
        // 这正是"暂停状态下长按会直接开始加速播放"的根因。
        // 这里先记住原状态，赋值后若被意外拉起就立刻恢复暂停，
        // 保证任何调用路径都不会因为改倍速而改变播放/暂停状态。
        val wasPlaying = runCatching { mp.isPlaying }.getOrDefault(false)
        runCatching { mp.playbackParams = PlaybackParams().setSpeed(speed) }
        if (!wasPlaying && runCatching { mp.isPlaying }.getOrDefault(false)) {
            runCatching { mp.pause() }
        }
    }
    // 弹幕必须跟着倍速走：否则长按 2 倍速时视频在跑、弹幕仍按 1 倍速飘，
    // 观感上弹幕相对画面慢了一半（B 站是同倍速的）。
    danmakuView?.setPlaybackSpeed(speed)
}

    private fun stopProgressSaver() {
        progressSaveRunnable?.let { handler.removeCallbacks(it) }
        progressSaveRunnable = null
    }

    private fun startProgressUpdater() {
        stopProgressUpdater()
        // 弹幕时间轴单独跑 50ms 高频校正（进度条仍 500ms）
        startDanmakuSync()
        progressUpdater = object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                // 只有不在用户拖动中（含屏幕拖动快进），且 seek 已完成时才更新进度条
                if (mp.isPlaying && !isUserSeeking && seekCompleted && !isSeekingByGesture) {
                    // 每 500ms 用播放器真实位置校正弹幕时钟：
                    // 缓冲、丢帧都会让弹幕自己的累加时钟逐渐跑偏
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

    /**
     * 弹幕状态复位（切集/关闭播放器共用）。
     *
     * 原先 `close()` 与 `stopPlaybackAndClear()` 各自抄了一份完全相同的 5 行，
     * 极易漏改（例如新增"取消在途请求"时就只会在其中一处生效）。统一到这里。
     */
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

    /** 一次解析同时拿到播放地址与 cid（cid 是拉取弹幕的必需参数） */
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

            // ---------- 新增：监听 seek 完成 ----------
            setOnSeekCompleteListener { mp ->
                isUserSeeking = false
                seekCompleted = true
                // 跳转完成后同步弹幕，避免弹幕停留在旧时间点
                syncDanmakuToPosition(mp.currentPosition)
                // 跳转完成后立即手动刷新进度条和时间
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
                // 上下切换的收尾：prepared 只代表可以播了，**画面还没渲染出第一帧**，
                // 所以这里先"挂起"——封面继续顶替，等 onSurfaceTextureUpdated
                // 确认新视频首帧上屏后才瞬间归位。这样既不会闪黑，
                // 也只会有一段转场动画（滑出的那段）。
                onVideoSwitched(firstFrameReady = false)
                // 真正开始播放：挂出视频通知（标题为视频标题），并暂停正在播放的音乐
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
            }

            setOnErrorListener { _, what, extra ->
                // 播放出错：若此时正处于"已提交上下切换"的中间态（画面在屏幕外、
                // 只显示目标封面），必须把画面带回来，否则用户会卡在一张静态封面上。
                // 这里**不能**走 onVideoSwitched()：那条路会等"新视频首帧上屏"，
                // 而播放已经出错、不会再有帧到来，等到最后就是永久卡住。
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
                    // 已被更新的播放请求取代：本请求不会再起播，确保通知不残留
                    detachVideoNotification()
                    return@post
                }
                loadingDialog.dismiss()
                if (info == null) {
                    // 切换失败：必须把上滑/下滑时移出屏幕的画面归位、收起预览层，
                    // 否则播放器会停留在"视频已滑走、只显示目标封面"的状态。
                    // 同样不能走 onVideoSwitched()——它要等新视频首帧，而这里
                    // 根本没有新视频，等下去就是永久卡在封面上。
                    resetSwitchDrag(force = true, animate = false)
                    Toast.makeText(activity, LanguageUtils.getString(activity, R.string.get_link_failed), Toast.LENGTH_SHORT).show()
                    // 取链失败 = 没有播放发生：撤掉可能残留的通知，别留下空壳
                    detachVideoNotification()
                    return@post
                }
                val url = info.url

                currentVideoTitle = video.title
                currentVideoAuthor = video.author
                currentVideoUrl = url
                // 弹幕早于 prepareMediaPlayer 启动：MediaPlayer 缓冲期间弹幕已在跑，
                // 观感上就是"打开即有弹幕"，而不是等画面出来才冒第一条。
                loadDanmaku(info.cid, cookie, thisRequestId)
                // 歌词同样是"取到就顺带刷一下通知"，不与起播抢时间：
                // 它跑在同一个 io 线程里、排在弹性最大的弹幕请求之后。
                // 时长用稿件时长（BiliVideo.duration 是 "MM:SS"，解析成秒）做兜底校验。
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

    /** 是否正在播放。签名与 [VideoPlaybackController.Target.isPlaying] 一致，一并作为其实现 */
    override fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    /**
     * 把 `BiliVideo.duration`（`"MM:SS"` 或 `"HH:MM:SS"`）解析成秒。
     *
     * 与 [BiliHistoryHelper] 里的同类解析口径一致；非法值按 0 处理，
     * 0 会让歌词的时间轴校验自行跳过（宁可显示也不误丢）。
     */
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
