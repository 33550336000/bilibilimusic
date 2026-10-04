package com.tilixibiesi.service

import com.tilixibiesi.R
import com.tilixibiesi.bili.BiliLyric
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.ui.MainPagerActivity
import com.tilixibiesi.util.NotificationHelper
import com.tilixibiesi.util.VideoPlaybackController

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * 视频播放前台服务（完整B站源模式）。
 *
 * 与 [MusicPlayerService] 的关系：两者互不干扰、各持一条通知、各自的通知 id 也不同。
 * B 站视频是「画面 + 声音」的播放，视图（TextureView）必须留在
 * [com.tilixibiesi.bili.BiliVideoPlayer] 所在的 Activity 里，
 * 因此这里**不托管 MediaPlayer**，只做三件事：
 *  1. 起播时挂出一条与音乐通知同样形态的前台通知（MediaStyle + 播放/暂停 + 上下集），
 *     标题为视频标题，用户可在通知上暂停后再继续播放；
 *  2. 起播时让音乐让位（[MusicPlayerService.pauseForVideoPlayback]），
 *     避免视频人声与音乐叠在一起；
 *  3. 把通知上的指令通过 [VideoPlaybackController] 转交给真正的播放器。
 *
 * 播放器关闭（close）时本服务随之撤通知 + stopSelf，不留残影。
 *
 * 关于 MediaSession：这里**必须**挂一个自己的 session。
 * MediaStyle 只有在 `setMediaSession(token)` 之后才会展开成大视图
 * （有 token 时 SystemUI 才知道该按哪条媒体会话去画大图/标题/控制器，
 * 没有就退化成一行普通通知，且不会自动展开）。
 * 之前刻意不挂是为了不抢音乐服务的媒体控制中心，
 * 但视频起播时音乐已被暂停（STATE_PAUSED），系统优先级会落在正在播放的会话上，
 * 因此不会冲突；视频收尾时 `isActive = false`，控制权自然还给音乐。
 */
class VideoPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "com.tilixibiesi.VIDEO_CHANNEL"
        const val NOTIFICATION_ID = 1002

        /**
         * 唯一的展示指令：带标题与「是否正在播放」，用于挂出/刷新通知。
         *
         * 播放与暂停也复用它（只改 isPlaying）：播放器的暂停/恢复是异步生效的，
         * 由播放器在状态真正变化后主动再发一次本指令最准；
         * 这里收到后统一按最新状态重画通知，不会出现「通知写着播放、实际已暂停」。
         */
        const val ACTION_SHOW = "com.tilixibiesi.VIDEO_SHOW"
        /** 通知上的播放/暂停控件：转交给播放器 */
        const val ACTION_TOGGLE = "com.tilixibiesi.VIDEO_TOGGLE"
        /** 通知上的上一集 / 下一集 */
        const val ACTION_PREV = "com.tilixibiesi.VIDEO_PREV"
        const val ACTION_NEXT = "com.tilixibiesi.VIDEO_NEXT"
        /** 收尾：撤通知 + 结束服务 */
        const val ACTION_STOP = "com.tilixibiesi.VIDEO_STOP"
        /** 通知上拖动进度条 */
        const val ACTION_SEEK = "com.tilixibiesi.VIDEO_SEEK"

        const val EXTRA_TITLE = "EXTRA_VIDEO_TITLE"
        const val EXTRA_SUBTITLE = "EXTRA_VIDEO_SUBTITLE"
        const val EXTRA_IS_PLAYING = "EXTRA_VIDEO_IS_PLAYING"
        const val EXTRA_SEEK_POSITION = "EXTRA_VIDEO_SEEK_POSITION"

        /** 进度条推进的刷新间隔。1 秒是「肉眼连续 + 后台开销可接受」的折中 */
        private const val PROGRESS_TICK_MS = 1000L

        /**
         * 本服务是否已挂出通知。
         *
         * 这是「是否还可以刷新」的唯一判据：未挂出时收到任何刷新/控件指令都直接结束自己，
         * 避免一条被系统重启的残留指令凭空把服务拉起来并挂出空通知。
         */
        @Volatile
        private var active = false

        /** 最近一次的通知标题（视频标题）；刷新播放状态时需要复用 */
        @Volatile
        private var currentTitle: String = ""
        /** 最近一次的副标题（UP 主）；大视图里显示在标题下方 */
        @Volatile
        private var currentSubtitle: String = ""

        /**
         * 当前视频的歌词；null 表示没有可用歌词（或还没加载完）。
         *
         * 由播放器加载后经 [attachLyric] 塞进来，而不是本服务自己取：
         * bvid/cid 的身份归播放器所有（[VideoPlaybackController] 也是同一套单向桥思路），
         * 服务只管渲染。设为 [Volatile] 是因为它会被播放器的后台线程写入、
         * 由本服务的主线程读取。
         */
        @Volatile
        private var currentLyric: BiliLyric? = null

        /**
         * 播放器歌词加载完成后调用。
         *
         * 传 null 也表示"这首歌没有歌词"，服务会退回原来的文案
         * （UP 主名，再退「正在播放 / 已暂停」）。
         */
        fun attachLyric(context: Context, lyric: BiliLyric?) {
            currentLyric = lyric
            // 没挂通知时什么都不用做（例如歌词比播放先到，或播放早已结束）
            if (!active) return
            // 复用 ACTION_SHOW 这条现成的重绘路径：它会按最新状态整条重建通知。
            // 歌名/位置都不用改，因此这里不传 title，由服务沿用 [currentTitle]。
            deliver(context, ACTION_SHOW, null, VideoPlaybackController.isPlaying())
        }

        /**
         * 视频真正开始播放：挂出/刷新前台通知，并让音乐暂停。
         *
         * 必须在起播（onPrepared → start）之后调用：取链失败时不该留下空通知。
         * 走 startForegroundService：新建服务后 onStartCommand 里会立即 startForeground，
         * 不会触发 ANR；已在前台运行时再调用同样安全。
         */
        fun show(context: Context, title: String, subtitle: String = "") {
            active = true
            currentTitle = title
            currentSubtitle = subtitle
            deliver(context, ACTION_SHOW, title, true, subtitle, asForeground = true)
            MusicPlayerService.pauseForVideoPlayback(context)
        }

        /** 播放器自身状态变化（画面上点击暂停/继续）后同步通知，不反向指挥播放器 */
        fun updateState(context: Context, playing: Boolean) {
            if (!active) return
            // 服务此刻必然已在运行（active 为真即说明它挂过前台），
            // 用普通 startService 即可，不必再走一次前台启动。
            deliver(context, ACTION_SHOW, null, playing)
        }

        /**
         * 「视频通知进度条」开关变化后立即刷新。
         *
         * 不刷新的话，切换只写在 SharedPreferences 里，要等下一次状态变化
         * （进度推进器 tick / 暂停 / 切集）才会体现在通知上——
         * 用户会以为"开关没生效"。这里主动重发一次展示指令，
         * 让 service 用最新的开关值重建 metadata 与 PlaybackState。
         */
        fun refreshProgressSetting(context: Context) {
            if (!active) return
            deliver(context, ACTION_SHOW, null, VideoPlaybackController.isPlaying())
        }

        /** 视频关闭 / 取链失败：撤掉通知并结束服务 */
        fun stop(context: Context) {
            if (!active) return
            active = false
            currentTitle = ""
            deliver(context, ACTION_STOP)
        }

        private fun deliver(
            context: Context,
            action: String,
            title: String? = null,
            playing: Boolean? = null,
            subtitle: String? = null,
            asForeground: Boolean = false
        ) {
            val intent = Intent(context, VideoPlaybackService::class.java).apply {
                this.action = action
                if (title != null) putExtra(EXTRA_TITLE, title)
                if (playing != null) putExtra(EXTRA_IS_PLAYING, playing)
                if (subtitle != null) putExtra(EXTRA_SUBTITLE, subtitle)
            }
            runCatching {
                // 只有「首次挂出通知」必须以 startForegroundService 发起：
                // 那时服务可能还没起来，而前台服务身份必须在启动的那一次调用里确立
                // （之后紧接着的 onStartCommand 会立即 startForeground，不会触发 ANR）。
                // 其余指令服务必然已在运行，用普通 startService，
                // 免得反复触发「必须在 5 秒内 startForeground」的前台启动约束。
                if (asForeground) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 本视频自己的 MediaSession。
     *
     * 存在的唯一硬性理由：MediaStyle 只有在拿到 session token 时才会展开成大视图。
     * 没有它，通知就是一行普通文本、且不会自动展开（与音乐那条能展开的区别就在这里）。
     */
    private var mediaSession: MediaSession? = null
    /** 本服务实例的当前播放状态（MediaSession 需要最新的 state 才能正确渲染控制器） */
    @Volatile
    private var currentPlaying = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
    }

    /**
     * 确保 session 存在。
     *
     * 不能只在 onCreate 里建：本服务是 START_STICKY 且 stopSelf() 是异步的，
     * 一次播放结束（finishSelf 已 release 掉 session）到服务真正销毁之间，
     * 新的 show() 可能就到了——那时 onCreate 不会再跑，
     * 通知就会拿着 null token 重建，又变回不会展开的普通通知。
     */
    private fun ensureMediaSession() {
        if (mediaSession == null) initMediaSession()
    }

    private fun initMediaSession() {
        releaseMediaSession()
        val session = MediaSession(this, "VideoPlaybackService").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (!VideoPlaybackController.isAttached()) {
                        finishSelf()
                        return
                    }
                    VideoPlaybackController.resume()
                    syncStateSoon()
                    restartProgressTicker()
                }

                override fun onPause() {
                    VideoPlaybackController.pause()
                    syncStateSoon()
                }

                override fun onSeekTo(pos: Long) {
                    if (!VideoPlaybackController.isAttached()) {
                        finishSelf()
                        return
                    }
                    VideoPlaybackController.seekTo(pos.toInt())
                    syncStateSoon()
                }

                override fun onSkipToPrevious() {
                    VideoPlaybackController.switchPrevious()
                }

                override fun onSkipToNext() {
                    VideoPlaybackController.switchNext()
                }

                override fun onStop() {
                    finishSelf()
                }
            })

            setPlaybackState(buildPlaybackState(currentPlaying))
            // 必须显式激活：未激活的 session 不会被 SystemUI 采纳，
            // 通知也就无从展开。这常见于「只 new 了 MediaSession 却忘了 isActive」。
            isActive = true
        }
        mediaSession = session
    }

    private fun buildPlaybackState(
        playing: Boolean,
        positionMs: Long = 0,
        durationMs: Long = 0
    ): PlaybackState {
        // 判据是「时长是否已知」而不是「位置是否 > 0」：
        // 位置 0 是刚起播时的合法值，若按 >0 判断，头一秒会误退化成 UNKNOWN、进度条不出现。
        val known = durationMs > 0 && isProgressEnabled()
        val builder = PlaybackState.Builder()
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                if (known) positionMs.coerceAtLeast(0) else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                // 速度决定 SystemUI 如何在两次更新之间插值推进进度条：
                // 暂停时必须为 0，否则它会在暂停期间继续「走」
                if (playing) 1.0f else 0f
            )
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_STOP or
                    // 有它进度条才可拖动；关闭进度条功能时一并去掉，
                    // 免得 SystemUI 画出一条拖了没反应的条
                    if (isProgressEnabled()) PlaybackState.ACTION_SEEK_TO else 0
            )
        return builder.build()
    }

    /**
     * 同步 MediaSession 的元数据与播放状态。
     *
     * 元数据只在标题/副标题变化时重写：每次都 setMetadata 会让 SystemUI 反复重绘大视图，
     * 表现为通知闪烁；播放状态则每次都写（它决定控制器显示「暂停」还是「播放」）。
     *
     * 本类的这些方法都只在主线程执行（onCreate / onStartCommand / MediaSession.Callback
     * 均派发到创建 session 的线程），因此无需额外加锁。
     */
    private fun updateSession(
        title: String,
        subtitle: String,
        playing: Boolean,
        positionMs: Long = 0,
        durationMs: Long = 0
    ) {
        val session = mediaSession ?: return
        runCatching {
            // 进度条开关关闭时，元数据里也**不能**带 METADATA_KEY_DURATION：
            // 否则 SystemUI 拿到了"满量程"，即使 state 里的 position 是 UNKNOWN，
            // 某些 ROM 仍会画出一条不能动、也拖不了的静态进度条（僵尸条）。
            // 因此这里的时长要跟着开关一起"消失"，做到彻底不显示。
            val effectiveDuration = if (isProgressEnabled()) durationMs else 0L

            // 总时长是关键：SystemUI 只有拿到 METADATA_KEY_DURATION 才知道进度条的满量程，
            // 没有它就没法画（更谈不上拖）。时长在一次播放内不变，故只在>0且变化时才写。
            if (effectiveDuration > 0 && sessionLastDuration != effectiveDuration) {
                sessionLastDuration = effectiveDuration
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, effectiveDuration)
                        .build()
                )
                sessionLastTitle = title
                sessionLastSubtitle = subtitle
            } else if (sessionLastTitle != title || sessionLastSubtitle != subtitle ||
                sessionLastDuration != effectiveDuration
            ) {
                // 注意这里也要比 duration：关闭开关时 effectiveDuration 变 0，
                // 若只比标题/副标题，就会因"标题没变"而跳过重写，导致旧的 DURATION 残留。
                sessionLastTitle = title
                sessionLastSubtitle = subtitle
                sessionLastDuration = effectiveDuration
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                        .apply { if (effectiveDuration > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, effectiveDuration) }
                        .build()
                )
            }
            // 进度条能不能拖，取决于两件事：state 里带着真实 position，
            // 且 actions 里有 ACTION_SEEK_TO。两者都在这里给足。
            session.setPlaybackState(buildPlaybackState(playing, positionMs, durationMs))
        }
    }

    /** 进度条开关：关闭后不再有定时刷新，通知退回「标题 + 播放/暂停」 */
    private fun isProgressEnabled(): Boolean = SpUtils.isVideoNotifyProgressEnabled(this)

    /**
     * 通知副标题文案 —— 有歌词且当前时刻有词时显示这句，否则沿用改动前的两级回退。
     *
     * 注意回退顺序与改动前完全一致：**先 UP 主名、再「正在播放 / 已暂停」**，
     * 只是把歌词插在了最前面。这样"没歌词"的视频观感与改动前没有任何差别。
     */
    private fun notificationContentText(positionMs: Long, subtitle: String, playing: Boolean): String {
        val lyric = currentLyric
        if (lyric != null) {
            val text = lyric.textAt(positionMs)
            if (!text.isNullOrEmpty()) return text
        }
        return subtitle.takeIf { it.isNotEmpty() }
            ?: if (playing) LanguageUtils.getString(this, R.string.text_playing)
            else LanguageUtils.getString(this, R.string.text_paused)
    }

    /**
     * 当前视频播放位置（毫秒）。
     *
     * 直接读播放器而不是缓存——拖动、缓冲、倍速都会让位置跳变，
     * 自算必然对不上。播放器未就绪时 [VideoPlaybackController] 自己会兜底为 0。
     */
    private fun videoPositionMs(): Long = VideoPlaybackController.getPosition().toLong()

    /**
     * 通知副标题是否需要更新（歌词换句、或前奏/尾奏处需要退回 UP 主名）。
     *
     * SystemUI 已经拿到播放进度，会按 speed 自行插值推进进度条，所以**不需要**
     * 为了进度每秒重发通知；但副标题是通知里的静态文本，SystemUI 推不动它，
     * 只能由这里在文案变化时主动重发一次。
     *
     * 判据用"算出来的文案"而不是"歌词对象里的句子"：这样"唱到前奏/尾奏"
     * 也会被识别成一次变化，从而正确退回 UP 主名，而不是把最后一句冻在通知上。
     *
     * @return true 表示文案确实变了、需要重发通知
     */
    private fun contentTextChanged(positionMs: Long, subtitle: String, playing: Boolean): Boolean {
        // 只比较、不写：[lastContentText] 的唯一写入点是 buildNotification，
        // 这样"缓存"始终等于"通知上真正显示的内容"。
        return notificationContentText(positionMs, subtitle, playing) != lastContentText
    }

    /**
     * 上一次写进通知的副标题。
     *
     * 唯一写入点是 [buildNotification]，与音乐侧保持一致：这样这个缓存
     * 永远等于"通知上真正显示的内容"，不会出现缓存说"变了"、而通知其实
     * 因为某条路径没走构建器而没变（或反之）的脱节。
     * 撤通知时必须置空，否则下一个视频若恰好落在同一句上会被误判为"没变化"。
     */
    private var lastContentText: String? = null

    private fun releaseMediaSession() {
        stopProgressTicker()
        // 整条通知即将被撤掉，上一首的"上一句歌词"必须一起作废。
        lastContentText = null
        mediaSession?.let { session ->
            runCatching {
                // 置为非活动：视频不播了，系统媒体控制中心应把控制权交还给音乐服务
                session.isActive = false
                session.release()
            }
        }
        mediaSession = null
        sessionLastTitle = null
        sessionLastSubtitle = null
        sessionLastDuration = 0L
    }

    /** 上一次写入 session 的标题/副标题，用于避免重复 setMetadata 造成通知闪烁 */
    private var sessionLastTitle: String? = null
    private var sessionLastSubtitle: String? = null
    private var sessionLastDuration: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: currentTitle
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: currentSubtitle
                currentTitle = title
                currentSubtitle = subtitle
                val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, VideoPlaybackController.isPlaying())
                currentPlaying = playing
                ensureMediaSession()
                updateSession(
                    title,
                    subtitle,
                    playing,
                    VideoPlaybackController.getPosition().toLong(),
                    VideoPlaybackController.getDuration().toLong()
                )
                // 每次都走 startForeground：它本身就会用新通知替换旧的，
                // 同时保证「startForegroundService 之后必须先 startForeground」的约束
                // 一定被满足（哪怕下面判断要立刻收掉自己，也不会触发前台启动超时）。
                startForegroundWithType(NOTIFICATION_ID, buildNotification(title, subtitle, playing))
                // 无播放器托管（例如播放器已关闭、或这条指令是系统重启服务带来的残留）：
                // 立刻收掉，而不是挂出一条点不动的僵尸通知
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                } else {
                    restartProgressTicker()
                }
            }
            ACTION_TOGGLE -> {
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                    return START_STICKY
                }
                val wasPlaying = VideoPlaybackController.isPlaying()
                if (wasPlaying) {
                    VideoPlaybackController.pause()
                } else {
                    VideoPlaybackController.resume()
                    // 恢复播放后要重新起推进器：上一轮循环在暂停时就自行退出了
                    restartProgressTicker()
                }
                // 播放器的暂停/恢复异步生效，稍等一拍再按真实状态重画通知。
                // 播放器侧（togglePlayPause）也会主动回传一次状态，两路最终一致。
                syncStateSoon()
            }
            ACTION_PREV -> {
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                    return START_STICKY
                }
                VideoPlaybackController.switchPrevious()
            }
            ACTION_NEXT -> {
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                    return START_STICKY
                }
                VideoPlaybackController.switchNext()
            }
            ACTION_SEEK -> {
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                    return START_STICKY
                }
                val pos = intent.getIntExtra(EXTRA_SEEK_POSITION, -1)
                if (pos >= 0) {
                    VideoPlaybackController.seekTo(pos)
                    syncStateSoon()
                }
            }
            ACTION_STOP -> finishSelf()
            else -> finishSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        active = false
        currentTitle = ""
        currentSubtitle = ""
        currentLyric = null
        releaseMediaSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /** 稍等一拍后按播放器真实状态刷新通知；播放器已关闭则收掉自己 */
    private fun syncStateSoon() {
        mainHandler.postDelayed({
            if (!active || !VideoPlaybackController.isAttached()) {
                finishSelf()
                return@postDelayed
            }
            currentPlaying = VideoPlaybackController.isPlaying()
            updateSession(
                currentTitle,
                currentSubtitle,
                currentPlaying,
                VideoPlaybackController.getPosition().toLong(),
                VideoPlaybackController.getDuration().toLong()
            )
            pushNotification(currentTitle, currentSubtitle, currentPlaying)
        }, 200)
    }

    /**
     * 进度条推进器 / 歌词推进器。
     *
     * 两个理由之一成立就值得跑起这个每秒循环：
     *  - **进度条开着**：每秒把真实位置写回 session。通知本身不重发
     *    （MediaStyle 绑了 token 后 SystemUI 会按 speed 自行插值推进并纠偏），
     *    但拖动、缓冲、倍速都会让位置跳变，这层纠偏不能省。
     *  - **有歌词**：歌词是通知里的静态文本，SystemUI 推不动它，
     *    只能在换句时主动重发一次通知。
     *
     * 两者都不成立时（没歌词 + 关了进度条）仍然直接返回，不做无谓的后台唤醒。
     *
     * 每轮都向播放器要最新位置，而不是自己累加 —— 用户拖动、缓冲、
     * 倍速播放都会让位置跳变，自算必然对不上。
     */
    private fun restartProgressTicker() {
        stopProgressTicker()
        if (!isProgressEnabled() && currentLyric == null) return
        progressTicker = object : Runnable {
            override fun run() {
                if (!active || !VideoPlaybackController.isAttached()) {
                    finishSelf()
                    return
                }
                val playing = VideoPlaybackController.isPlaying()
                currentPlaying = playing
                val pos = videoPositionMs()
                val dur = VideoPlaybackController.getDuration().toLong()
                // 进度条关闭时不写 session：那时通知里没有任何会变化的进度，
                // 每秒一次 binder 调用 + SystemUI 重绘纯属浪费。
                if (isProgressEnabled()) {
                    updateSession(currentTitle, currentSubtitle, playing, pos, dur)
                }
                if (playing) {
                    // 只在副标题文案真的变了（换句，或唱到前奏/尾奏要退回 UP 主名）
                    // 时才重发通知：每秒无条件重发会让 SystemUI 反复重绘大视图，
                    // 用户看到的就是通知一直在闪。
                    if (contentTextChanged(pos, currentSubtitle, playing)) {
                        pushNotification(currentTitle, currentSubtitle, playing)
                    }
                    mainHandler.postDelayed(this, PROGRESS_TICK_MS)
                } else {
                    // 只在自己仍是当前推进器时清空引用：期间可能已被 restartProgressTicker 换掉，
                    // 无条件置 null 会把新推进器的引用抹掉，导致它再也无法被 stop 掉。
                    if (progressTicker === this) progressTicker = null
                    // 暂停时补发一次通知，把按钮从「暂停」翻成「播放」
                    // （按钮来自通知自身的 actions，不随 session 变化，必须重发）。
                    // 文案由 pushNotification → buildNotification 按当前位置重算，
                    // 因此暂停那一刻会落在正确的歌词句上。
                    pushNotification(currentTitle, currentSubtitle, playing)
                }
            }
        }
        mainHandler.postDelayed(progressTicker!!, PROGRESS_TICK_MS)
    }

    private fun stopProgressTicker() {
        progressTicker?.let { mainHandler.removeCallbacks(it) }
        progressTicker = null
    }

    private var progressTicker: Runnable? = null

    private fun pushNotification(title: String, subtitle: String, playing: Boolean) {
        ensureMediaSession()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(title, subtitle, playing))
        }
    }

    /** 收尾：撤通知 + 结束服务 */
    private fun finishSelf() {
        active = false
        currentTitle = ""
        currentSubtitle = ""
        currentLyric = null
        releaseMediaSession()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun createNotificationChannel() {
        NotificationHelper.createMediaChannel(this, CHANNEL_ID, R.string.channel_video, R.string.channel_video_desc)
    }

    /**
     * 与音乐通知同形态：MediaStyle + 上一集 / 暂停(播放) / 下一集 三个紧凑动作。
     *
     * 必须 `setMediaSession(token)`：没有 token 时 MediaStyle 不会展开成大视图，
     * 通知就只是普通的一行（这正是「不会自动展开」的根因）。
     */
    private fun buildNotification(title: String, subtitle: String, playing: Boolean): Notification {
        val contentIntent = Intent(this, MainPagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainPagerActivity.EXTRA_TARGET_PAGE, MainPagerActivity.PAGE_SEARCH)
        }
        val baseFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingContentIntent = PendingIntent.getActivity(this, 0, contentIntent, baseFlags)
        val toggleIntent = Intent(this, VideoPlaybackService::class.java).apply { action = ACTION_TOGGLE }
        val piToggle = PendingIntent.getService(this, 1, toggleIntent, baseFlags)
        val prevIntent = Intent(this, VideoPlaybackService::class.java).apply { action = ACTION_PREV }
        val piPrev = PendingIntent.getService(this, 2, prevIntent, baseFlags)
        val nextIntent = Intent(this, VideoPlaybackService::class.java).apply { action = ACTION_NEXT }
        val piNext = PendingIntent.getService(this, 3, nextIntent, baseFlags)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            // 与音乐侧同一套做法：先算出文案，再连同"它已经上屏"一起记下来。
            // 让唯一的写入点紧贴真正的构建处，[lastContentText] 就永远与
            // 通知上显示的内容一致——ACTION_SHOW / 暂停 / 进度推进 / 歌词到达
            // 全都经过这里，不存在"缓存与通知脱节"的窗口。
            .setContentText(
                notificationContentText(videoPositionMs(), subtitle, playing).also {
                    lastContentText = it
                }
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(pendingContentIntent)
            .setStyle(
                Notification.MediaStyle()
                    // 关键：绑定本服务的 MediaSession，通知才会展开成大视图
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        NotificationHelper.addAction(
            this, builder,
            android.R.drawable.ic_media_previous,
            LanguageUtils.getString(this, R.string.btn_prev_video_short),
            piPrev
        )
        val playPauseIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (playing) LanguageUtils.getString(this, R.string.btn_pause) else LanguageUtils.getString(this, R.string.btn_play)
        NotificationHelper.addAction(this, builder, playPauseIcon, playPauseLabel, piToggle)
        NotificationHelper.addAction(
            this, builder,
            android.R.drawable.ic_media_next,
            LanguageUtils.getString(this, R.string.btn_next_video_short),
            piNext
        )
        return builder.build()
    }

    private fun startForegroundWithType(id: Int, notification: Notification) {
        NotificationHelper.startForegroundWithMediaPlayback(this, id, notification)
    }
}
