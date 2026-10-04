package com.tilixibiesi.service
import com.tilixibiesi.bili.BiliHistoryHelper
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.R
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.PlaybackDetails
import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.bili.BiliApiHelper
import com.tilixibiesi.bili.BiliLyric
import com.tilixibiesi.bili.BiliLyricHelper
import com.tilixibiesi.bili.BiliSearchHelper
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.AudioFocusController
import com.tilixibiesi.util.CacheManager
import com.tilixibiesi.util.PlaybackStatsWriter
import com.tilixibiesi.util.MediaSessionManager
import com.tilixibiesi.util.NotificationHelper
import com.tilixibiesi.util.MediaSessionCallback
import com.tilixibiesi.ui.MainPagerActivity

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.widget.Toast
import java.io.IOException
import java.util.Random
import java.io.File
import android.annotation.SuppressLint

@SuppressLint("WakelockTimeout")
class MusicPlayerService : Service(), MediaPlayer.OnPreparedListener,
    MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener,
    MediaSessionCallback {

    companion object {
        const val CHANNEL_ID = "com.tilixibiesi.MUSIC_CHANNEL"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "com.tilixibiesi.ACTION_PLAY"
        const val ACTION_PAUSE = "com.tilixibiesi.ACTION_PAUSE"
        const val ACTION_PREV = "com.tilixibiesi.ACTION_PREV"
        const val ACTION_NEXT = "com.tilixibiesi.ACTION_NEXT"
        const val ACTION_STOP = "com.tilixibiesi.ACTION_STOP"
        const val ACTION_SWITCH_MODE = "com.tilixibiesi.ACTION_SWITCH_MODE"
        const val ACTION_SEEK = "com.tilixibiesi.ACTION_SEEK"
        const val ACTION_PLAY_FILE = "com.tilixibiesi.ACTION_PLAY_FILE"
        const val ACTION_REQUEST_PROGRESS = "com.tilixibiesi.ACTION_REQUEST_PROGRESS"
        const val EXTRA_POSITION = "EXTRA_POSITION"
        const val EXTRA_MODE = "EXTRA_MODE"
        const val EXTRA_SEEK_POSITION = "EXTRA_SEEK_POSITION"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        const val EXTRA_DISPLAY_NAME = "EXTRA_DISPLAY_NAME"
        const val BROADCAST_PLAY_STATE = "com.tilixibiesi.PLAY_STATE"
        const val EXTRA_IS_PLAYING = "EXTRA_IS_PLAYING"
        const val EXTRA_MUSIC_NAME = "EXTRA_MUSIC_NAME"
        const val EXTRA_PLAY_MODE = "EXTRA_PLAY_MODE"
        const val EXTRA_CURRENT_POSITION = "CURRENT_POSITION"
        const val EXTRA_DURATION = "DURATION"
        private const val CACHE_DIR_REL = "system/axeron/long/Android/cache"
        var musicList: MutableList<MusicBean>? = null
        const val ACTION_UPDATE_FOCUS_MODE = "com.tilixibiesi.ACTION_UPDATE_FOCUS_MODE"
        /** 视频起播时用它让音乐让位（见 [pauseForVideoPlayback]） */
        const val ACTION_PAUSE_FOR_VIDEO = "com.tilixibiesi.ACTION_PAUSE_FOR_VIDEO"
        /** 「通知进度条」开关变化后立即刷新（见 [refreshProgressSetting]） */
        const val ACTION_REFRESH_PROGRESS_SETTING = "com.tilixibiesi.ACTION_REFRESH_PROGRESS_SETTING"
        var isPlaying = false
        var currentPlayingName: String? = null
        var currentPlayingIndex = -1
        private var errorCount = 0

        /**
         * 服务是否在运行中（供外部判断「有没有音乐可暂停」）。
         *
         * 只在本服务的 onCreate/onDestroy 里翻转：进程被杀时静态量一并重置，
         * 因此不会出现「标记说在跑、实际早就没了」的错位。
         */
        @Volatile
        var isServiceRunning = false

        /**
         * 应用界面当前是否可见（有 Activity 处于 started）。
         *
         * 由 [com.tilixibiesi.MyApplication] 的 ActivityLifecycleCallbacks 维护，
         * 供进度上报判断「有没有人在听这个广播」：界面不可见时唯一的订阅者
         * SongsPage 收不到也无所谓，可直接跳过每秒一次的 sendBroadcast。
         *
         * 默认 true（进程刚起、还没收到任何回调时按可见处理，宁可多发不可少发）。
         */
        @Volatile
        var appVisible = true

        /**
         * B 站视频起播时让音乐暂停：两条音轨同时出声是谁也不想听到的混音。
         *
         * 两个关键取舍：
         *  - **服务没在跑就直接返回**。startService 会把服务拉起来，
         *    而它的 onCreate 里 restorePlayState() 会立刻挂出一条「无播放」音乐通知
         *    ——用户只是看个视频，不该凭空多出一条通知。
         *  - **走一次 Intent 而不是直接调方法**。真正的暂停统一落在
         *    [pausePlay]（用户按暂停的同一条路径）：进度保存、通知、MediaSession、
         *    电量/WiFi 锁与播放状态持久化全部照旧，
         *    不会产生「页面显示还在播、实际已停」的错位。
         *
         * 音乐本就没在播时：pausePlay() 自带 isPlaying 判断，自然空转。
         */
        fun pauseForVideoPlayback(context: Context) {
            if (!isServiceRunning) return
            runCatching {
                val intent = Intent(context, MusicPlayerService::class.java).apply {
                    action = ACTION_PAUSE_FOR_VIDEO
                }
                context.startService(intent)
            }
        }

        /**
         * 「通知进度条」开关变化后立即刷新音乐通知的进度显示。
         *
         * 不刷新的话，开关只写进 SharedPreferences，要等下一次状态变化
         * （暂停/切歌/每秒 tick）才生效，用户会以为没起作用。
         * 服务没在跑时直接返回——没必要为了刷新把服务拉起来。
         */
        fun refreshProgressSetting(context: Context) {
            if (!isServiceRunning) return
            runCatching {
                val intent = Intent(context, MusicPlayerService::class.java).apply {
                    action = ACTION_REFRESH_PROGRESS_SETTING
                }
                context.startService(intent)
            }
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 音频焦点：申请/释放与参数构建全部封装在 AudioFocusController（由 onCreate 初始化） */
    private val audioFocus by lazy { AudioFocusController(this, this, mainHandler) }
    private var currentPosition = -1
    /**
     * 每次真正发起一次播放请求时自增。异步回调（B 站取链、错误重试）在回主线程执行前
     * 会校验该令牌，避免「用户已切歌，旧请求才返回」导致对已进入 Preparing/Started 的
     * MediaPlayer 再次 setDataSource —— 那会抛 IllegalStateException 直接崩溃。
     */
    private var playGeneration = 0
    private var isPrepared = false
    private var isPaused = false
    private var isUserPaused = false
    private var currentPlayMode: SpUtils.PlayMode = SpUtils.PlayMode.SINGLE_LOOP
    private val random = Random()
    private var progressUpdateRunnable: Runnable? = null

    private var isFileMode = false
    private var filePlayList: List<String> = emptyList()
    private var filePlayIndex = -1
    private var currentDisplayName: String? = null

    private lateinit var cacheManager: CacheManager
    private lateinit var statsManager: PlaybackStatsWriter
    private lateinit var mediaSessionManager: MediaSessionManager

    // B 站历史专用导航
    private var biliHistoryList: List<MusicBean> = emptyList()
    private var biliHistoryIndex: Int = -1

    // B 站备用链接缓存及重试状态
    private val biliAudioUrlCache = mutableMapOf<String, List<String>>()  // bvid -> 所有可用音频链接
    private val biliRetryIndex = mutableMapOf<String, Int>()              // bvid -> 当前尝试到的索引
    private var currentBvid: String? = null                                // 正在播放的 B 站视频 bvid
    private var currentMusicBean: MusicBean? = null                        // 正在播放的 MusicBean

    // ---------- 歌词（通知文案用） ----------

    /**
     * 当前曲目的歌词；null 表示这首歌没有可用歌词（或还没加载完）。
     *
     * 只在**主线程**读写（加载完成后 post 回来），因此无需加锁。
     * 为 null 时通知照旧显示「正在播放 / 已暂停」——这是绝大多数情况下的正常路径，
     * 不是异常（实测 B 站音乐稿件只有约四成能拿到歌词）。
     */
    private var currentLyric: BiliLyric? = null

    /**
     * 当前正在加载歌词的 bvid。
     *
     * 用来避免重复加载：曲目未变时（暂停→恢复、拖动进度、切播放模式）
     * 不该把三个接口重打一遍。
     */
    private var lyricLoadingBvid: String? = null

    /**
     * 上一次真正写进通知的副标题文案（歌词某句，或回退文案）。
     *
     * 进度循环每秒跑一次，但通知**只在文案真的变化时才重建**：
     * 重建整条通知会让 SystemUI 重绘大视图（表现为通知闪烁），
     * 这与 [MediaSessionManager.setTrackMetadata] 里刻意避免重复 setMetadata 是同一个考量。
     *
     * 由 [buildNotification] 统一写入 —— 它是"通知上到底显示了什么"的唯一真相来源。
     * 让写入点跟着真正构建通知的地方走，可以避免这个缓存与通知实际内容脱节。
     */
    private var lastContentText: String? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        DataFileUtils.initRenameMap()

        cacheManager = CacheManager(StoragePaths.resolveWrite(CACHE_DIR_REL).absolutePath)
        statsManager = PlaybackStatsWriter(StoragePaths.resolveWrite(PlaybackDetails.DIR_REL).absolutePath)
        mediaSessionManager = MediaSessionManager()

        initMediaPlayer()
        audioFocus.init()
        initWakeLock()
        initWifiLock()
        createNotificationChannel()
        mediaSessionManager.init(this, this)

        if (musicList.isNullOrEmpty()) {
            musicList = buildFullList()
        }
        currentPlayMode = SpUtils.PlayMode.values()[SpUtils.getPlayMode(this)]
        restorePlayState()
    }

    private fun initMediaPlayer() {
        mediaPlayer?.release()
        val usage = when (SpUtils.getAudioFocusMode(this)) {
            SpUtils.AUDIO_FOCUS_CALL_LEVEL   -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            SpUtils.AUDIO_FOCUS_FULL_EXCLUSIVE -> AudioAttributes.USAGE_MEDIA
            SpUtils.AUDIO_FOCUS_TRANSIENT    -> AudioAttributes.USAGE_MEDIA
            else -> AudioAttributes.USAGE_MEDIA
        }
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener(this@MusicPlayerService)
            setOnCompletionListener(this@MusicPlayerService)
            setOnErrorListener(this@MusicPlayerService)
            setWakeMode(this@MusicPlayerService, PowerManager.PARTIAL_WAKE_LOCK)
            isLooping = false
        }
    }
    private fun initWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TilixiBiesiMusic:WakeLock")
        wakeLock.setReferenceCounted(false)
    }

    private fun initWifiLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TilixiBiesiMusic:WifiLock")
        wifiLock.setReferenceCounted(false)
    }

    private fun createNotificationChannel() {
        NotificationHelper.createMediaChannel(this, CHANNEL_ID, R.string.channel_music, R.string.channel_music_desc)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_FOCUS_MODE) {
            audioFocus.abandon()
            audioFocus.init()
            updateMediaPlayerUsage()
            if (currentPosition != -1 && mediaPlayer?.isPlaying == true) {
                audioFocus.request()
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_REQUEST_PROGRESS) {
            // 无论播放还是暂停都回传一次进度：
            // 暂停时页面同样要显示进度条并允许拖动，若只查询不回传，
            // 页面拿不到时长就只能把进度条重置为不可拖动。
            //
            // 关键：只有在播放器确实进入 Prepared 之后才能安全读取 duration/currentPosition。
            // 服务刚被拉起（restorePlayState 只恢复“上一首”的名字，并未 prepare）时，
            // MediaPlayer 仍处于 Idle，此时调用 getDuration() 会走 MediaPlayerNative 报
            // error(-38,0)，进而触发 onError() 里的重试逻辑 playMusic(...) ——
            // 表现就是「每次进入应用会自动开始播放（且播放几秒后被暂停）」。
            // 因此这里先判空 + 校验已 Prepared，否则只回传已知的“上一首”信息。
            val mp = mediaPlayer
            val prepared = isPrepared && mp != null
            var dur = if (prepared) try { mp.duration } catch (e: IllegalStateException) { 0 } else 0
            if (dur <= 0) {
                val bean = musicList?.getOrNull(currentPlayingIndex)
                if (bean != null && bean.duration > 0) {
                    dur = bean.duration * 1000
                }
            }
            val cur = if (prepared) try { mp.currentPosition } catch (e: IllegalStateException) { 0 } else 0
            val playing = prepared && (try { mp.isPlaying } catch (e: IllegalStateException) { false })
            Intent(BROADCAST_PLAY_STATE).apply {
                putExtra(EXTRA_CURRENT_POSITION, cur)
                putExtra(EXTRA_DURATION, dur)
                putExtra(EXTRA_IS_PLAYING, playing)
                setPackage(packageName)
                sendBroadcast(this)
            }
            return START_STICKY
        }

        if (intent == null || intent.action == null) {
            if (musicList.isNullOrEmpty()) {
                musicList = buildFullList()
            }
            restorePlayState()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_PLAY -> {
                val position = intent.getIntExtra(EXTRA_POSITION, -1)
                isUserPaused = false
                when {
                    position != -1 -> playMusic(position)
                    isPaused -> resumePlay()
                    mediaPlayer?.isPlaying == true -> { /* 已播放，无动作 */ }
                    isFileMode && currentDisplayName != null -> {
                        val filePath = filePlayList.getOrNull(filePlayIndex)
                        if (filePath != null) playFile(filePath, currentDisplayName)
                    }
                    currentPlayingName != null -> {
                        val index = musicList?.indexOfFirst {
                            DataFileUtils.getDisplayName(it.musicName) == currentPlayingName
                        } ?: -1
                        if (index != -1) {
                            playMusic(index)
                        } else {
                            playSavedBiliMusic()
                        }
                    }
                    else -> { /* 无任何记录，不执行 */ }
                }
            }
            ACTION_PAUSE -> {
                isUserPaused = true
                pausePlay()
            }
            ACTION_PREV -> {
                isUserPaused = false
                playPrev()
            }
            ACTION_NEXT -> {
                isUserPaused = false
                playNext()
            }
            ACTION_STOP -> stopPlay()
            ACTION_PAUSE_FOR_VIDEO -> pausePlay()
            ACTION_REFRESH_PROGRESS_SETTING -> refreshProgressDisplay()
            ACTION_SWITCH_MODE -> {
                val mode = intent.getIntExtra(EXTRA_MODE, SpUtils.PlayMode.SINGLE_LOOP.ordinal)
                switchPlayMode(SpUtils.PlayMode.values()[mode])
            }
            ACTION_SEEK -> {
                val seekPos = intent.getIntExtra(EXTRA_SEEK_POSITION, -1)
                if (seekPos >= 0) {
                    // 只定位，不改变播放状态：暂停时拖完仍保持暂停
                    mediaPlayer?.seekTo(seekPos)
                    // seekTo 是异步的，立即读可能拿到旧位置；延后一拍再回传，
                    // 让页面进度条落在用户拖到的位置而不是跳回原处
                    mainHandler.postDelayed({ broadcastProgressOnce() }, 80)
                }
            }
            ACTION_PLAY_FILE -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
                if (!filePath.isNullOrEmpty()) {
                    playFile(filePath, displayName)
                }
            }
        }
        return START_STICKY
    }

    // ---------- 播放核心 ----------

    private fun playSavedBiliMusic() {
        AppExecutors.io.execute {
            val bean = findBiliBeanByDisplayName(currentPlayingName ?: "")
            mainHandler.post {
                if (bean != null) {
                    playMusicByBean(bean)
                } else {
                    Toast.makeText(this@MusicPlayerService, LanguageUtils.getString(this@MusicPlayerService, R.string.song_expired), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 构造与「歌曲页展示列表」结构完全一致的播放列表：B 站历史条目在前，本地歌曲在后。
     *
     * 为什么必须一致：页面侧 SongsPage.musicList 会被 applyBiliBeans 插入 B 站条目
     * （addAll(0, ...)，即置于最前），而持久化时又用 filter { !it.isBilibili } 剥离，
     * 于是同一个整数索引在「展示列表」与「持久化/服务列表」两个基准上含义不同，
     * 相差的正是 B 站条目数量——表现为重新进入应用后选中项整体偏移
     * （例如第 100 首变成第 72 首，差值 = B 站历史条数）。
     * 让服务侧也按同样顺序拼装，整数索引即可在两侧通用，无需任何偏移换算。
     */
    private fun buildFullList(): MutableList<MusicBean> {
        val local = SpUtils.getMusicList(this)
        val bili = loadBiliHistoryList()
        val full = ArrayList<MusicBean>(bili.size + local.size)
        full.addAll(bili)
        full.addAll(local)
        return full
    }

    private fun loadBiliHistoryList(): List<MusicBean> = try {
        BiliHistoryHelper.loadAllNormalized()
    } catch (e: Exception) {
        emptyList()
    }

    private fun loadAndSetBiliHistory() {
        biliHistoryList = loadBiliHistoryList()
        biliHistoryIndex = -1
    }

    private fun playMusicByBean(bean: MusicBean) {
        // 加载完整的 B 站历史列表并定位当前歌曲索引
        biliHistoryList = loadBiliHistoryList()
        biliHistoryIndex = biliHistoryList.indexOfFirst {
            it.bvid == bean.bvid && it.musicName == bean.musicName
        }
        val gen = preparePlay(bean, position = -1)
        playResolved(bean, gen, autoCache = false)
    }

    private fun playMusic(position: Int) {
        resetBiliState()
        biliHistoryList = emptyList()
        biliHistoryIndex = -1

        val list = musicList ?: return
        if (position < 0 || position >= list.size) return
        val musicBean = list[position]

        val gen = preparePlay(musicBean, position = position)
        playResolved(musicBean, gen, autoCache = true)
    }

    /** 数据源解析前的统一状态重置；返回本次播放代次。 */
    private fun preparePlay(bean: MusicBean, position: Int): Int {
        val gen = ++playGeneration
        isFileMode = false
        filePlayList = emptyList()
        filePlayIndex = -1
        currentDisplayName = null
        currentPosition = position
        currentPlayingIndex = position
        currentPlayingName = DataFileUtils.getDisplayName(bean.musicName)
        isPlaying = true
        errorCount = 0
        stopProgressUpdates()
        resetPlayState()
        // 切歌：上一首的歌词必须立刻作废，否则新歌起播到歌词加载完之间
        // 通知上会挂着上一首的歌词（错得比"正在播放"更离谱）
        clearLyricState()
        audioFocus.request()
        return gen
    }

    /**
     * 把 MusicBean 解析成本地文件 / 缓存 / B 站音频 / 普通网络地址并起播。
     *
     * @param autoCache 普通网络地址是否顺带触发后台缓存；B 站历史直播
     *                  （[playMusicByBean]）保持原行为，不自动缓存。
     */
    private fun playResolved(bean: MusicBean, gen: Int, autoCache: Boolean) {
        fun doPlay(path: String) {
            if (autoCache && path.startsWith("http") && SpUtils.isAutoCacheEnabled(this@MusicPlayerService)) {
                cacheManager.startBackgroundCache(bean, this@MusicPlayerService)
            }
            setupDataSourceAndPlay(path, bean)
        }

        val localPath = bean.localPath
        if (localPath != null && File(localPath).exists()) {
            bean.isDownloaded = true
            doPlay(localPath)
            return
        }

        val cachedPath = cacheManager.getCachedFilePath(bean)
        if (cachedPath != null) {
            doPlay(cachedPath)
            return
        }

        // B 站音频：快速获取第一个链接播放，并后台填充备用列表
        //
        // 全程**不使用登录 Cookie**：官方 playurl 对公开稿件不要求登录态，
        // 只要带 Referer 就能取到 dash.audio 并直接拉流（已实测 206 可下载）。
        // 也不再经过任何第三方解析站——那类服务的可用性不受本项目控制，
        // 不适合作为「能不能播」的前置条件。
        if (bean.isBilibili && !bean.bvid.isNullOrEmpty()) {
            val bvid = bean.bvid!!
            currentBvid = bvid
            currentMusicBean = bean

            fun fillBackupUrls(firstUrl: String?) {
                AppExecutors.io.execute {
                    // getAudioUrls 返回「低→高」升序列表，重试游标即从 0 起步：
                    // 当前档失效时自动升到更高一档，而不是重新回到已失败的最低档。
                    val urls = BiliSearchHelper.getAudioUrls(bvid)
                    biliAudioUrlCache[bvid] =
                        if (firstUrl != null && !urls.contains(firstUrl)) listOf(firstUrl) + urls
                        else urls
                    biliRetryIndex[bvid] = 0
                }
            }

            if (!bean.musicUrl.isNullOrEmpty()) {
                doPlay(bean.musicUrl)
                if (!biliAudioUrlCache.containsKey(bvid)) {
                    fillBackupUrls(null)
                }
                return
            }

            AppExecutors.io.execute {
                // 默认取最低音质（升序列表第一个）：优先省流量、起播更快。
                // 该链接失效时由 fillBackupUrls 填好的升序列表逐档重试。
                val firstUrl = BiliSearchHelper.getPreferredAudioUrl(bvid)
                mainHandler.post {
                    // 用户可能已切歌：丢弃过期结果，避免对当前播放器重复 setDataSource
                    if (gen != playGeneration) return@post
                    if (firstUrl != null) {
                        bean.musicUrl = firstUrl
                        doPlay(firstUrl)
                        fillBackupUrls(firstUrl)
                    } else {
                        Toast.makeText(
                            this@MusicPlayerService,
                            LanguageUtils.getString(this@MusicPlayerService, R.string.video_url_unavailable),
                            Toast.LENGTH_SHORT
                        ).show()
                        playNext()
                    }
                }
            }
            return
        }

        // 普通网络音乐；网络地址为空时直接下一首（与历史直播路径保持一致）
        if (!bean.musicUrl.isNullOrEmpty()) {
            doPlay(bean.musicUrl)
        } else {
            playNext()
        }
    }

    // ---------- 备用链接工具方法 ----------

    /**
     * 解析「当前正在播放歌曲」在 [musicList] 中的真实索引。
     *
     * 为什么不能直接用缓存的 currentPosition：歌曲页会在服务启动后往同一个列表对象
     * 头部插入 B 站历史（addAll(0, ...)），此刻 currentPosition 记录的还是插入前的基准，
     * 于是按整数索引取歌会整体偏移「B 站历史条数」。用歌名重新定位即可与展示列表对齐。
     */
    private fun resolveCurrentIndex(): Int {
        val name = currentPlayingName ?: return -1
        val list = musicList ?: return -1
        return list.indexOfFirst {
            DataFileUtils.getDisplayName(it.musicName) == DataFileUtils.getDisplayName(name)
        }
    }

    private fun getCurrentBiliUrl(bvid: String): String? {
        val urls = biliAudioUrlCache[bvid] ?: return null
        val idx = biliRetryIndex[bvid] ?: 0
        return if (idx < urls.size) urls[idx] else null
    }

    private fun advanceBiliRetry(bvid: String) {
        biliRetryIndex[bvid] = (biliRetryIndex[bvid] ?: 0) + 1
    }

    private fun resetBiliState() {
        biliAudioUrlCache.clear()
        biliRetryIndex.clear()
        currentBvid = null
        currentMusicBean = null
    }

    // ---------- 文件播放 ----------

    private fun buildFilePlayList(): List<String> {
        val cacheDir = StoragePaths.resolveWrite(CACHE_DIR_REL)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return emptyList()
        return cacheDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") && it.name != "cache_map.json" }
            ?.sortedBy { it.name }
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    private fun setupDataSourceAndPlay(playPath: String, musicBean: MusicBean) {
        val gen = playGeneration
        try {
            // 播放器可能已被后续的播放请求 reset/释放，或正处于 Preparing；
            // 此时 setDataSource 会抛 IllegalStateException。先确保处于 Idle 状态。
            if (mediaPlayer == null) return
            mediaPlayer?.reset()
            val isNetwork = playPath.startsWith("http")
            val headers = HashMap<String, String>()
            if (isNetwork && BiliApiHelper.isBiliUrl(playPath)) {
                // Referer 是硬性要求：缺它会 403（已实测）。
                // 不再附带登录 Cookie —— 主页面播放本就无需登录态。
                headers["Referer"] = "https://www.bilibili.com/"
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            }
            if (isNetwork && headers.isNotEmpty()) {
                mediaPlayer?.setDataSource(this, Uri.parse(playPath), headers)
            } else if (isNetwork) {
                mediaPlayer?.setDataSource(playPath)
            } else {
                mediaPlayer?.setDataSource(playPath)
            }
            mediaPlayer?.isLooping = currentPlayMode == SpUtils.PlayMode.SINGLE_LOOP
            mediaPlayer?.prepareAsync()
        } catch (e: Exception) {
            // 任何异步/状态异常都不应让服务崩溃
            if (gen != playGeneration) return
            if (e is IOException || e is IllegalStateException) {
                playNext()
            }
        }
    }

    private fun getDisplayNameFromCache(filePath: String): String? {
        val cacheMap = cacheManager.loadCacheMap()
        val md5Name = File(filePath).name
        return cacheMap.entries.find { it.value == md5Name }?.key
    }

    private fun playFile(filePath: String, displayName: String?) {
        resetBiliState()
        stopProgressUpdates()
        resetPlayState()
        audioFocus.request()
        filePlayList = buildFilePlayList()
        filePlayIndex = filePlayList.indexOf(filePath)
        if (filePlayIndex == -1) {
            filePlayList = listOf(filePath)
            filePlayIndex = 0
        }
        isFileMode = true
        val rawName = displayName
            ?: getDisplayNameFromCache(filePath)
            ?: File(filePath).name
        currentDisplayName = DataFileUtils.getDisplayName(rawName)
        currentPosition = -1
        currentPlayingIndex = -1
        currentPlayingName = currentDisplayName
        isPlaying = true
        try {
            mediaPlayer?.setDataSource(filePath)
            mediaPlayer?.isLooping = currentPlayMode == SpUtils.PlayMode.SINGLE_LOOP
            mediaPlayer?.prepareAsync()
            startForegroundWithType(NOTIFICATION_ID, buildNotification(currentDisplayName!!, true))
            sendPlayStateBroadcast(true, currentDisplayName!!)
            if (!wakeLock.isHeld) wakeLock.acquire()
            if (!wifiLock.isHeld) wifiLock.acquire()
        } catch (e: IOException) {
            playNextFile()
        }
    }

    private fun playNextFile() {
        if (filePlayList.isEmpty()) {
            stopPlay()
            return
        }
        filePlayIndex = (filePlayIndex + 1) % filePlayList.size
        val nextPath = filePlayList[filePlayIndex]
        val rawName = getDisplayNameFromCache(nextPath) ?: File(nextPath).name
        playFile(nextPath, rawName)
    }

    private fun playPrevFile() {
        if (filePlayList.isEmpty()) {
            stopPlay()
            return
        }
        filePlayIndex = if (filePlayIndex - 1 < 0) filePlayList.size - 1 else filePlayIndex - 1
        val prevPath = filePlayList[filePlayIndex]
        val rawName = getDisplayNameFromCache(prevPath) ?: File(prevPath).name
        playFile(prevPath, rawName)
    }

    private fun playNext() {
        if (isFileMode) {
            playNextFile()
            return
        }

        if (biliHistoryList.isNotEmpty() && biliHistoryIndex != -1) {
            val nextIndex = when (currentPlayMode) {
                SpUtils.PlayMode.RANDOM -> random.nextInt(biliHistoryList.size)
                else -> biliHistoryIndex + 1
            }
            if (nextIndex < biliHistoryList.size) {
                playMusicByBean(biliHistoryList[nextIndex])
            } else {
                biliHistoryList = emptyList()
                biliHistoryIndex = -1
                val list = musicList ?: return
                if (list.isNotEmpty()) {
                    playMusic(0)
                } else {
                    loadAndSetBiliHistory()
                    if (biliHistoryList.isNotEmpty()) playMusicByBean(biliHistoryList[0])
                }
            }
            return
        }

        val list = musicList ?: return
        if (list.isEmpty()) return
        val baseIndex = resolveCurrentIndex()
        val nextPos = when (currentPlayMode) {
            SpUtils.PlayMode.RANDOM -> random.nextInt(list.size)
            else -> if (baseIndex >= 0) baseIndex + 1 else 0
        }
        if (nextPos < list.size) {
            playMusic(nextPos)
        } else {
            loadAndSetBiliHistory()
            if (biliHistoryList.isNotEmpty()) {
                playMusicByBean(biliHistoryList[0])
            } else {
                playMusic(0)
            }
        }
    }

    private fun playPrev() {
        if (isFileMode) {
            playPrevFile()
            return
        }

        if (biliHistoryList.isNotEmpty() && biliHistoryIndex != -1) {
            val prevIndex = when (currentPlayMode) {
                SpUtils.PlayMode.RANDOM -> random.nextInt(biliHistoryList.size)
                else -> biliHistoryIndex - 1
            }
            if (prevIndex >= 0) {
                playMusicByBean(biliHistoryList[prevIndex])
            } else {
                biliHistoryList = emptyList()
                biliHistoryIndex = -1
                val list = musicList ?: return
                if (list.isNotEmpty()) {
                    playMusic(list.size - 1)
                } else {
                    loadAndSetBiliHistory()
                    if (biliHistoryList.isNotEmpty()) playMusicByBean(biliHistoryList.last())
                }
            }
            return
        }

        val list = musicList ?: return
        if (list.isEmpty()) return
        val baseIndex = resolveCurrentIndex()
        val prevPos = when (currentPlayMode) {
            SpUtils.PlayMode.RANDOM -> random.nextInt(list.size)
            else -> if (baseIndex >= 0) baseIndex - 1 else list.size - 1
        }
        if (prevPos >= 0) {
            playMusic(prevPos)
        } else {
            loadAndSetBiliHistory()
            if (biliHistoryList.isNotEmpty()) {
                playMusicByBean(biliHistoryList.last())
            } else {
                playMusic(list.size - 1)
            }
        }
    }

    // ---------- MediaPlayer 回调 ----------

    override fun onCompletion(mp: MediaPlayer) {
        flushStatsIfReady()
        stopProgressUpdates()

        if (isFileMode) {
            when (currentPlayMode) {
                SpUtils.PlayMode.SINGLE_LOOP -> {
                    mp.seekTo(0)
                    mp.start()
                    audioFocus.request()
                    startProgressUpdates()
                    mediaSessionManager.updatePlaybackState(PlaybackState.STATE_PLAYING, 0)
                }
                SpUtils.PlayMode.RANDOM -> {
                    if (filePlayList.size > 1) {
                        var nextIndex: Int
                        do { nextIndex = random.nextInt(filePlayList.size) } while (nextIndex == filePlayIndex)
                        filePlayIndex = nextIndex
                    }
                    val nextPath = filePlayList[filePlayIndex]
                    val rawName = getDisplayNameFromCache(nextPath) ?: File(nextPath).name
                    playFile(nextPath, rawName)
                }
                else -> playNextFile()
            }
            return
        }

        if (biliHistoryList.isNotEmpty() && biliHistoryIndex != -1) {
            when (currentPlayMode) {
                SpUtils.PlayMode.SINGLE_LOOP -> {
                    mp.seekTo(0)
                    mp.start()
                    audioFocus.request()
                    startProgressUpdates()
                    mediaSessionManager.updatePlaybackState(PlaybackState.STATE_PLAYING, 0)
                }
                else -> playNext()
            }
            return
        }

        when (currentPlayMode) {
            SpUtils.PlayMode.SEQUENCE -> playNext()
            SpUtils.PlayMode.RANDOM -> playNext()
            SpUtils.PlayMode.SINGLE_LOOP -> {
                mp.seekTo(0)
                mp.start()
                audioFocus.request()
                startProgressUpdates()
                mediaSessionManager.updatePlaybackState(PlaybackState.STATE_PLAYING, 0)
            }
        }
    }

    override fun onPrepared(mp: MediaPlayer) {
        isPrepared = true
        mp.start()
        isPlaying = true
        startProgressUpdates()
        val name = currentPlayingName ?: ""
        // 写入标题 + 总时长：SystemUI 拿到 METADATA_KEY_DURATION 才会画进度条
        mediaSessionManager.setTrackMetadata(name, resolveDurationMs())
        mediaSessionManager.updatePlaybackState(PlaybackState.STATE_PLAYING, 0, 1.0f)
        sendPlayStateBroadcast(true, name)
        startForegroundWithType(NOTIFICATION_ID, buildNotification(name, true))
        if (!wakeLock.isHeld) wakeLock.acquire()
        if (!wifiLock.isHeld) wifiLock.acquire()
        savePlayState()
        // 起播即开始后台取歌词；取到后会主动刷新一次通知。
        // 放在这里而不是 playResolved：只有真正起播（Prepared）了才值得为它花三个请求，
        // 取链失败自动跳下一首的情况不该产生任何歌词请求。
        currentBvid?.takeIf { it.isNotEmpty() }?.let { bvid ->
            startLyricLoad(bvid, (resolveDurationMs() / 1000L).toInt())
        }
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        flushStatsIfReady()
        stopProgressUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        initMediaPlayer()
        isPrepared = false
        isPaused = false
        mediaSessionManager.updatePlaybackState(PlaybackState.STATE_ERROR, 0)

        val bvid = currentBvid
        if (bvid != null) {
            advanceBiliRetry(bvid)
            val nextUrl = getCurrentBiliUrl(bvid)
            if (nextUrl != null) {
                currentMusicBean?.let { bean ->
                    bean.musicUrl = nextUrl
                    errorCount = 0
                    setupDataSourceAndPlay(nextUrl, bean)
                }
                return true
            } else {
                AppExecutors.io.execute {
                    // 兜底重取同样不用 Cookie、不走第三方；取最低档重新尝试
                    val freshUrl = BiliSearchHelper.getPreferredAudioUrl(bvid)
                    mainHandler.post {
                        if (freshUrl != null) {
                            val urls = (biliAudioUrlCache[bvid] ?: mutableListOf<String>()).toMutableList()
                            urls.add(freshUrl)
                            biliAudioUrlCache[bvid] = urls
                            biliRetryIndex[bvid] = urls.size - 1
                            currentMusicBean?.musicUrl = freshUrl
                            errorCount = 0
                            setupDataSourceAndPlay(freshUrl, currentMusicBean!!)
                        } else {
                            biliAudioUrlCache.remove(bvid)
                            biliRetryIndex.remove(bvid)
                            currentBvid = null
                            currentMusicBean = null
                            if (errorCount < 2) {
                                errorCount++
                                val retryIndex = resolveCurrentIndex()
                                if (retryIndex != -1) playMusic(retryIndex) else playNext()
                            } else {
                                errorCount = 0
                                playNext()
                            }
                        }
                    }
                }
                return true
            }
        }

        if (errorCount < 2) {
            errorCount++
            val retryIndex = resolveCurrentIndex()
            if (retryIndex != -1) playMusic(retryIndex) else playNext()
        } else {
            errorCount = 0
            playNext()
        }
        return true
    }

    // ---------- 音频焦点 ----------

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (mediaPlayer?.isPlaying == false && !isUserPaused) {
                    mediaPlayer?.start()
                    mediaPlayer?.let {
                        mediaSessionManager.updatePlaybackState(PlaybackState.STATE_PLAYING, it.currentPosition.toLong())
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                    isPaused = true
                    mediaPlayer?.let {
                        mediaSessionManager.updatePlaybackState(PlaybackState.STATE_PAUSED, it.currentPosition.toLong(), 0f)
                    }
                }
                mainHandler.postDelayed({
                    audioFocus.request()
                    if (isPaused && !isUserPaused) {
                        mediaPlayer?.start()
                        isPaused = false
                        mediaPlayer?.let {
                            mediaSessionManager.updatePlaybackState(PlaybackState.STATE_PLAYING, it.currentPosition.toLong())
                        }
                    }
                }, 300)
            }
        }
    }

    // ---------- 播放控制 ----------

    private fun resumePlay() {
        if (isPaused && mediaPlayer != null) {
            audioFocus.request()
            mediaPlayer?.start()
            isPaused = false
            isUserPaused = false
            isPlaying = true
            mediaPlayer?.let {
                mediaSessionManager.updatePlaybackState(PlaybackState.STATE_PLAYING, it.currentPosition.toLong(), 1.0f)
            }
            val name = currentPlayingName ?: ""
            sendPlayStateBroadcast(true, name)
            // 恢复播放：位置没变，通知上的那句歌词也没变。这里照常重建通知
            // （按钮要从「播放」翻成「暂停」），而 [lastContentText] 会由
            // buildNotification 算出与上次相同的文案，不会额外引发重绘。
            updateNotification(name, true)
            startProgressUpdates()
            if (!wakeLock.isHeld) wakeLock.acquire()
            if (!wifiLock.isHeld) wifiLock.acquire()
            savePlayState()
        }
    }

    private fun pausePlay() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            isPaused = true
            isUserPaused = true
            stopProgressUpdates()
            // 暂停期间位置不变，但文案必须按"暂停这一刻的位置"重算一次：
            // 若暂停恰好发生在两次每秒刷新之间，通知上可能还是上上一句。
            // 不用在这里手动置空缓存——紧接着的 updateNotification 会重建通知，
            // 由 buildNotification 重新计算并刷新缓存。
            mediaPlayer?.let {
                // 暂停时速度必须为 0：否则 SystemUI 会按上一档速度继续插值推进进度条
                mediaSessionManager.updatePlaybackState(PlaybackState.STATE_PAUSED, it.currentPosition.toLong(), 0f)
            }
            val name = currentPlayingName ?: ""
            sendPlayStateBroadcast(false, name)
            updateNotification(name, false)
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
            savePlayState()
            flushStatsIfReady()
        }
    }

    private fun stopPlay() {
        stopProgressUpdates()
        resetPlayState()
        resetBiliState()
        clearLyricState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        flushStatsIfReady()
        isPlaying = false
        currentPlayingName = null
        currentPlayingIndex = -1
        currentPosition = -1
        biliHistoryList = emptyList()
        biliHistoryIndex = -1
        SpUtils.savePlayState(this, null, false)
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        audioFocus.abandon()
        mediaSessionManager.updatePlaybackState(PlaybackState.STATE_STOPPED, 0)
        mediaSessionManager.clearTrackMetadata()
        sendPlayStateBroadcast(false, "")
    }

    private fun resetPlayState() {
        mediaPlayer?.reset()
        isPrepared = false
        isPaused = false
    }

    // ---------- 通知与进度 ----------

    /**
     * 通知副标题文案 —— 有歌词且当前时刻有词时显示这句，否则回退
     * 「正在播放 / 已暂停」。
     *
     * @param positionMs 取歌词用的播放位置。默认取播放器当前值；
     *                   暂停/恢复等时刻调用方也可显式传入。
     */
    private fun notificationContentText(isPlaying: Boolean, positionMs: Long): String {
        val lyric = currentLyric
        if (lyric != null) {
            val text = lyric.textAt(positionMs)
            if (!text.isNullOrEmpty()) return text
        }
        return LanguageUtils.getString(
            this,
            if (isPlaying) R.string.text_playing else R.string.text_paused
        )
    }

    /**
     * 起播/切歌时加载歌词。**不阻塞播放**：后台取，取到后回主线程刷新一次通知。
     *
     * 失败（无歌词、断网、接口改版）一律静默：通知退回默认文案即可，
     * 用户不该因为歌词取不到而看到任何错误提示。
     *
     * cid 在这里现取（[BiliSearchHelper.resolveCid] 内部有内存缓存，
     * 同一 bvid 只会真正请求一次）——本服务播放 B 站音频时只记录了 bvid，
     * 并没有存下 cid，而歌词与字幕都必须按 cid 定位到具体那个分 P。
     *
     * @param durationSec 视频总时长（秒），传给 helper 做时间轴合理性兜底校验
     */
    private fun startLyricLoad(bvid: String, durationSec: Int) {
        // 这首歌已加载完（不论有没有结果）就不再重复请求：
        // 暂停→恢复、拖动进度、切换播放模式都会走到这里，重复打接口纯属浪费。
        if (lyricFinishedBvid == bvid) return

        lyricLoadingBvid = bvid
        currentLyric = null
        // 置空以强制下一次构建通知时重新计算文案（新歌可能没有歌词）
        lastContentText = null

        AppExecutors.io.execute {
            val cid = BiliSearchHelper.resolveCid(bvid) ?: 0L
            if (cid <= 0L) {
                mainHandler.post { if (lyricLoadingBvid == bvid) lyricFinishedBvid = bvid }
                return@execute
            }
            // 只走曲库链路，不传任何登录态：本项目播放 B 站音频全程不用登录态
            // （见 playResolved 的注释），歌词同样没必要破例。
            val lyric = BiliLyricHelper.fetch(
                bvid = bvid,
                cid = cid,
                durationSec = durationSec,
                // 字幕兜底需要登录态（不带 Cookie 时 wbi/v2 的轨道恒为 0）。
                // 注：本服务播放音频本身不用登录态，但**歌词的字幕兜底**需要——
                // 用户没登录时只是少一层兜底，曲库链路照常工作。
                cookie = SpUtils.getBiliCookie(this@MusicPlayerService)
            )
            mainHandler.post {
                // 期间用户可能已切歌：丢弃过期结果，别把旧歌的歌词贴到新歌上
                if (lyricLoadingBvid != bvid) return@post
                lyricFinishedBvid = bvid
                currentLyric = lyric
                // 立即刷新一次：等下一次每秒 tick 的话，最多要 1 秒后才出现歌词
                val name = currentPlayingName ?: return@post
                updateNotification(name, isPlaying())
            }
        }
    }

    /** 已完成加载（不论有无结果）的 bvid，避免对"没有歌词"的歌反复重试 */
    private var lyricFinishedBvid: String? = null

    /**
     * 通知文案的推进：每秒由进度循环调用（拖动进度时也会即时调用）。
     *
     * 只要**算出来的文案**与上次写进通知的不同就重建通知——这样
     * "进入前奏 / 唱完最后一句 / 单曲循环回到开头"这几种情况都会
     * 自动退回「正在播放」，而不会把上一句歌词永久冻在通知上。
     */
    private fun tickNotificationLyric(positionMs: Long) {
        val name = currentPlayingName ?: return
        val next = notificationContentText(isPlaying(), positionMs)
        if (next == lastContentText) return
        updateNotification(name, isPlaying())
    }

    /** 切歌/停止时清空歌词状态，避免上一首的歌词残留 */
    private fun clearLyricState() {
        currentLyric = null
        lyricLoadingBvid = null
        lyricFinishedBvid = null
        lastContentText = null
    }

    private fun buildNotification(musicName: String, isPlaying: Boolean): Notification {
        val contentIntent = Intent(this, MainPagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainPagerActivity.EXTRA_TARGET_PAGE, MainPagerActivity.PAGE_SONGS)
        }
        // minSdk 30：直接使用 FLAG_IMMUTABLE
        val baseFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingContentIntent = PendingIntent.getActivity(this, 0, contentIntent, baseFlags)
        val playIntent = Intent(this, MusicPlayerService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val piPlay = PendingIntent.getService(this, 1, playIntent, baseFlags)
        val prevIntent = Intent(this, MusicPlayerService::class.java).apply { action = ACTION_PREV }
        val piPrev = PendingIntent.getService(this, 2, prevIntent, baseFlags)
        val nextIntent = Intent(this, MusicPlayerService::class.java).apply { action = ACTION_NEXT }
        val piNext = PendingIntent.getService(this, 3, nextIntent, baseFlags)
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        mediaSessionManager.getSessionToken()?.let { token -> style.setMediaSession(token) }

        // minSdk 30：直接使用 2 参构造器（带通知渠道）
        val builder: Notification.Builder = Notification.Builder(this, CHANNEL_ID)
        // 先算出文案，再连同"它已经上屏"这件事一起记下来。
        // 让唯一的写入点紧贴真正的构建处，可以保证 [lastContentText]
        // 与通知上显示的内容永远一致（回调、暂停、拖动等路径都从这里过）。
        val contentText = notificationContentText(isPlaying, currentLyricPosition())
        lastContentText = contentText
        builder
            .setContentTitle(musicName)
            // 副标题优先显示歌词（见 notificationContentText）；没有歌词时
            // 退回「正在播放 / 已暂停」，也就是改动前的行为。
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(pendingContentIntent)
            .setStyle(style)

        NotificationHelper.addAction(this, builder, android.R.drawable.ic_media_previous, LanguageUtils.getString(this, R.string.btn_previous_short), piPrev)
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) LanguageUtils.getString(this, R.string.btn_pause) else LanguageUtils.getString(this, R.string.btn_play)
        NotificationHelper.addAction(this, builder, playPauseIcon, playPauseTitle, piPlay)
        NotificationHelper.addAction(this, builder, android.R.drawable.ic_media_next, LanguageUtils.getString(this, R.string.btn_next_short), piNext)
        return builder.build()
    }

    /**
     * 当前播放位置（毫秒），读不到时返回 0。
     *
     * 取歌词必须用真实位置，不能用 [currentPosition]（那是**列表下标**，含义完全不同）。
     *
     * **必须先用 [isPrepared] 拦住，不能只靠 try/catch。** 播放器处于 Idle
     * （服务刚被拉起、[restorePlayState] 只恢复了"上一首"的名字但没 prepare）时读位置，
     * MediaPlayerNative 会报 `error(-38,0)`，而那会走 [onError] —— 它内部有
     * "重新取链并播放"的重试逻辑，于是**每次进入应用都会自动开始播放**。
     * 这个坑在 [onStartCommand] 的 `ACTION_REQUEST_PROGRESS` 分支里已经写明，
     * 那里同样是先判 `isPrepared` 再读；此处保持一致。
     *
     * 注意 try/catch 拦不住它：异常虽被吞掉，但 native 层的 error 回调已经发出，
     * 副作用（触发重试播放）照样发生。所以判 `isPrepared` 是**必须**的前置条件。
     */
    private fun currentLyricPosition(): Long {
        val mp = mediaPlayer ?: return 0L
        if (!isPrepared) return 0L
        return try { mp.currentPosition.toLong() } catch (_: IllegalStateException) { 0L }
    }

    private fun updateNotification(musicName: String, isPlaying: Boolean) {
        val notification = buildNotification(musicName, isPlaying)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
    private fun sendPlayStateBroadcast(isPlaying: Boolean, musicName: String) {
        Intent(BROADCAST_PLAY_STATE).apply {
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            putExtra(EXTRA_MUSIC_NAME, musicName)
            setPackage(packageName)
            sendBroadcast(this)
        }
    }

    private fun sendPlayModeBroadcast() {
        Intent(BROADCAST_PLAY_STATE).apply {
            putExtra(EXTRA_PLAY_MODE, currentPlayMode.ordinal)
            setPackage(packageName)
            sendBroadcast(this)
        }
    }
    private fun startForegroundWithType(id: Int, notification: Notification) {
        NotificationHelper.startForegroundWithMediaPlayback(this, id, notification)
    }

    /**
     * 当前曲目的总时长（毫秒）。
     *
     * 优先取播放器真实时长；某些网络流在 prepared 后仍报 0，
     * 此时退回 B 站 bean 里记录的秒数（×1000），与进度广播用的是同一套兜底逻辑。
     */
    /**
     * 开关变化后重写一次 session 的元数据与播放状态，使设置立即生效。
     *
     * 关键点：**不能**通过重启每秒刷新循环来实现——
     * 那个循环还兼着「播放时长统计」（statsManager.addOneSecond），
     * 关掉进度条不该顺带把统计也停掉。这里只重写一次 session 即可，
     * 位置与速度沿用播放器当前真实值。
     */
    private fun refreshProgressDisplay() {
        val mp = mediaPlayer ?: return
        val name = currentPlayingName ?: return
        // force = true：开关变了但标题/时长都没变，必须绕过"无变化则跳过"的判断。
        // 不用 clearTrackMetadata() 是因为它会先 setMetadata(null)，
        // 造成通知上曲目信息闪一下再回来。
        mediaSessionManager.setTrackMetadata(name, resolveDurationMs(), force = true)
        val playing = try { mp.isPlaying } catch (_: IllegalStateException) { false }
        val pos = try { mp.currentPosition.toLong() } catch (_: IllegalStateException) { 0L }
        mediaSessionManager.updatePlaybackState(
            if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            pos,
            if (playing) 1.0f else 0f
        )
    }

    private fun resolveDurationMs(): Long {
        val fromPlayer = runCatching { mediaPlayer?.duration ?: 0 }.getOrDefault(0)
        if (fromPlayer > 0) return fromPlayer.toLong()
        val bean = musicList?.getOrNull(currentPlayingIndex)
        return if (bean != null && bean.duration > 0) bean.duration * 1000L else 0L
    }

    private fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressUpdateRunnable = object : Runnable {
            override fun run() {
                try {
                    val mp = mediaPlayer ?: return
                    if (mp.isPlaying) {
                        val current = mp.currentPosition
                        var duration = mp.duration
                        if (duration <= 0) {
                            val bean = musicList?.getOrNull(currentPlayingIndex)
                            if (bean != null && bean.duration > 0) {
                                duration = bean.duration * 1000
                            }
                        }
                        // 界面不可见时没人接收这个广播（唯一订阅者 SongsPage 只在页面存活期间
                        // 注册 receiver，且本应用没有其它进程/监听者）。回前台时
                        // SongsPage.onResume → syncPlayerControlState() 会主动发一次
                        // ACTION_REQUEST_PROGRESS 把进度补齐，因此后台跳过不会造成显示不同步。
                        if (appVisible) {
                            Intent(BROADCAST_PLAY_STATE).apply {
                                putExtra(EXTRA_CURRENT_POSITION, current)
                                putExtra(EXTRA_DURATION, duration)
                                setPackage(packageName)
                                sendBroadcast(this)
                            }
                        }
                        // 每秒把真实位置写回 MediaSession：
                        // SystemUI 会在两次更新之间按 speed 插值推进进度条，
                        // 这里负责纠偏（拖动、缓冲、倍速都会让位置跳变），
                        // 不写的话进度条会与实际播放越飘越远。
                        //
                        // 「通知进度条」关闭时，MediaSession 的 position 本就是 UNKNOWN、
                        // 通知里没有任何会变化的进度，这次每秒写入纯属浪费
                        // （一次 binder 调用 + SystemUI 重绘），故跳过。
                        // 注意：addOneSecond 是播放时长统计，与进度条无关，必须照常执行。
                        if (SpUtils.isVideoNotifyProgressEnabled(this@MusicPlayerService)) {
                            mediaSessionManager.updatePlaybackState(
                                PlaybackState.STATE_PLAYING, current.toLong(), 1.0f
                            )
                        }
                        currentPlayingName?.let { statsManager.addOneSecond(it) }
                        // 歌词换句时重建通知。放在统计之后：即使通知刷新抛错，
                        // 播放时长也已经记上了。
                        tickNotificationLyric(current.toLong())
                    }
                } catch (e: IllegalStateException) {
                } finally {
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
        mainHandler.post(progressUpdateRunnable!!)
    }

    /** 单独回传一次当前进度与时长（供 seek / 暂停等场合立即刷新界面） */
    private fun broadcastProgressOnce() {
        mediaPlayer?.let { mp ->
            var dur = mp.duration
            if (dur <= 0) {
                val bean = musicList?.getOrNull(currentPlayingIndex)
                if (bean != null && bean.duration > 0) {
                    dur = bean.duration * 1000
                }
            }
            Intent(BROADCAST_PLAY_STATE).apply {
                putExtra(EXTRA_CURRENT_POSITION, mp.currentPosition)
                putExtra(EXTRA_DURATION, dur)
                putExtra(EXTRA_IS_PLAYING, mp.isPlaying)
                setPackage(packageName)
                sendBroadcast(this)
            }
            // 拖动进度会让时间轴跳变，通知上的歌词句必须立刻跟着跳，
            // 而不是等下一次每秒 tick（拖动时可能一秒内跳好几句）。
            tickNotificationLyric(mp.currentPosition.toLong())
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        progressUpdateRunnable = null
    }

    private fun switchPlayMode(newMode: SpUtils.PlayMode) {
        currentPlayMode = newMode
        SpUtils.savePlayMode(this, newMode.ordinal)
        sendPlayModeBroadcast()
        // 立即把 MediaPlayer 的循环标志应用到当前播放器（单曲循环时才内部循环）
        mediaPlayer?.isLooping = newMode == SpUtils.PlayMode.SINGLE_LOOP
        val name = currentPlayingName ?: ""
        updateNotification(name, isPlaying())
    }

    // ---------- 状态恢复 ----------

    private fun restorePlayState() {
        val savedDisplayName = SpUtils.getCurrentMusicName(this)
        val list = musicList ?: return
        if (savedDisplayName.isNullOrEmpty()) {
            startForegroundWithType(NOTIFICATION_ID, buildNotification(LanguageUtils.getString(this, R.string.no_playing), false))
            return
        }
        val index = list.indexOfFirst {
            DataFileUtils.getDisplayName(it.musicName) == savedDisplayName
        }
        if (index != -1) {
            currentPosition = index
            currentPlayingName = savedDisplayName
            isPlaying = false
            biliHistoryList = emptyList()
            biliHistoryIndex = -1
            startForegroundWithType(NOTIFICATION_ID, buildNotification(savedDisplayName, false))
            sendPlayStateBroadcast(false, savedDisplayName)
            savePlayState()
            return
        }
        AppExecutors.io.execute {
            val biliBean = findBiliBeanByDisplayName(savedDisplayName)
            mainHandler.post {
                if (biliBean != null) {
                    biliHistoryList = loadBiliHistoryList()
                    biliHistoryIndex = biliHistoryList.indexOfFirst {
                        it.musicName == savedDisplayName
                    }
                    currentPlayingName = savedDisplayName
                    isPlaying = false
                    currentPosition = -1
                    currentPlayingIndex = -1
                    startForegroundWithType(NOTIFICATION_ID, buildNotification(savedDisplayName, false))
                    sendPlayStateBroadcast(false, savedDisplayName)
                    savePlayState()
                } else {
                    SpUtils.savePlayState(this, null, false)
                    startForegroundWithType(NOTIFICATION_ID, buildNotification(LanguageUtils.getString(this, R.string.song_not_exists), false))
                }
            }
        }
    }

    private fun findBiliBeanByDisplayName(displayName: String): MusicBean? = try {
        BiliHistoryHelper.findByDisplayName(displayName)
    } catch (e: Exception) {
        null
    }

    private fun flushStatsIfReady() {
        if (this::statsManager.isInitialized) {
            statsManager.flush()
        }
    }

    private fun savePlayState() {
        SpUtils.savePlayState(this, currentPlayingName, isPlaying())
    }

    private fun updateMediaPlayerUsage() {
        val usage = when (SpUtils.getAudioFocusMode(this)) {
            SpUtils.AUDIO_FOCUS_CALL_LEVEL   -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            SpUtils.AUDIO_FOCUS_FULL_EXCLUSIVE -> AudioAttributes.USAGE_MEDIA
            SpUtils.AUDIO_FOCUS_TRANSIENT    -> AudioAttributes.USAGE_MEDIA
            else -> AudioAttributes.USAGE_MEDIA
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        mediaPlayer?.setAudioAttributes(attributes)
    }

    // ---------- 其他生命周期 ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        flushStatsIfReady()
        super.onDestroy()
        stopProgressUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        if (this::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        if (this::wifiLock.isInitialized && wifiLock.isHeld) wifiLock.release()
        audioFocus.abandon()
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSessionManager.release()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        savePlayState()
        val restartIntent = Intent(this, MusicPlayerService::class.java)
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            0,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = System.currentTimeMillis() + 300L
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
    }

    // ---------- MediaSession 回调 ----------

    override fun onPlayRequested() {
        if (isPaused) {
            resumePlay()
        } else if (!musicList.isNullOrEmpty()) {
            val index = resolveCurrentIndex()
            if (index != -1) playMusic(index) else playMusic(0)
        }
    }

    override fun onPauseRequested() {
        if (mediaPlayer?.isPlaying == true) {
            pausePlay()
        }
    }

    override fun onSkipToPreviousRequested() {
        playPrev()
    }

    override fun onSkipToNextRequested() {
        playNext()
    }

    override fun onStopRequested() {
        stopPlay()
    }

    override fun onSeekToRequested(pos: Long) {
        mediaPlayer?.seekTo(pos.toInt())
        // 立刻把新位置写回 session，否则进度条要等下一次定时刷新才跟上手指
        val playing = isPlaying()
        mediaSessionManager.updatePlaybackState(
            if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            pos,
            if (playing) 1.0f else 0f
        )
        // 从通知的进度条拖动（走 MediaSession 回调，不经过 ACTION_SEEK）时，
        // 通知上的歌词句也要立刻跳到新位置对应的一句
        tickNotificationLyric(pos)
    }
}
