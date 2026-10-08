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
        const val EXTRA_TOGGLE_IF_CURRENT = "EXTRA_TOGGLE_IF_CURRENT"
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
        const val ACTION_PAUSE_FOR_VIDEO = "com.tilixibiesi.ACTION_PAUSE_FOR_VIDEO"
        const val ACTION_REFRESH_PROGRESS_SETTING = "com.tilixibiesi.ACTION_REFRESH_PROGRESS_SETTING"
        var isPlaying = false
        var currentPlayingName: String? = null
        var currentPlayingRawName: String? = null
        var currentPlayingIndex = -1
        private var errorCount = 0

        @Volatile
        var isServiceRunning = false

        @Volatile
        var appVisible = true

        fun pauseForVideoPlayback(context: Context) {
            if (!isServiceRunning) return
            runCatching {
                val intent = Intent(context, MusicPlayerService::class.java).apply {
                    action = ACTION_PAUSE_FOR_VIDEO
                }
                context.startService(intent)
            }
        }

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

    private val audioFocus by lazy { AudioFocusController(this, this, mainHandler) }
    private var currentPosition = -1
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

    private var biliHistoryList: List<MusicBean> = emptyList()
    private var biliHistoryIndex: Int = -1

    private val biliAudioUrlCache = mutableMapOf<String, List<String>>()
    private val biliRetryIndex = mutableMapOf<String, Int>()
    private var currentBvid: String? = null
    private var currentMusicBean: MusicBean? = null


    private var currentLyric: BiliLyric? = null

    private var lyricLoadingBvid: String? = null

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
                val toggleIfCurrent = intent.getBooleanExtra(EXTRA_TOGGLE_IF_CURRENT, false)
                isUserPaused = false
                when {
                    toggleIfCurrent && position != -1 && !isFileMode &&
                        resolveCurrentIndex() == position -> toggleCurrentPlayback()
                    position != -1 -> playMusic(position)
                    isPaused -> resumePlay()
                    mediaPlayer?.isPlaying == true -> {  }
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
                    else -> {  }
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
                    mediaPlayer?.seekTo(seekPos)
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

    private fun preparePlay(bean: MusicBean, position: Int): Int {
        val gen = ++playGeneration
        isFileMode = false
        filePlayList = emptyList()
        filePlayIndex = -1
        currentDisplayName = null
        currentPosition = position
        currentPlayingIndex = position
        currentPlayingRawName = bean.musicName
        currentPlayingName = DataFileUtils.getDisplayName(bean.musicName)
        isPlaying = true
        errorCount = 0
        stopProgressUpdates()
        resetPlayState()
        clearLyricState()
        audioFocus.request()
        return gen
    }

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

        if (bean.isBilibili && !bean.bvid.isNullOrEmpty()) {
            val bvid = bean.bvid!!
            currentBvid = bvid
            currentMusicBean = bean

            fun fillBackupUrls(firstUrl: String?) {
                AppExecutors.io.execute {
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
                val firstUrl = BiliSearchHelper.getPreferredAudioUrl(bvid)
                mainHandler.post {
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

        if (!bean.musicUrl.isNullOrEmpty()) {
            doPlay(bean.musicUrl)
        } else {
            playNext()
        }
    }


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
            if (mediaPlayer == null) return
            mediaPlayer?.reset()
            val isNetwork = playPath.startsWith("http")
            val headers = HashMap<String, String>()
            if (isNetwork && BiliApiHelper.isBiliUrl(playPath)) {
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
        val cachedRawName = getDisplayNameFromCache(filePath)
        val rawName = displayName
            ?: cachedRawName
            ?: File(filePath).name
        currentDisplayName = DataFileUtils.getDisplayName(rawName)
        currentPosition = -1
        currentPlayingIndex = -1
        currentPlayingName = currentDisplayName
        currentPlayingRawName = cachedRawName ?: rawName
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
        mediaSessionManager.setTrackMetadata(name, resolveDurationMs())
        mediaSessionManager.updatePlaybackState(PlaybackState.STATE_PLAYING, 0, 1.0f)
        sendPlayStateBroadcast(true, name)
        startForegroundWithType(NOTIFICATION_ID, buildNotification(name, true))
        if (!wakeLock.isHeld) wakeLock.acquire()
        if (!wifiLock.isHeld) wifiLock.acquire()
        savePlayState()
        currentMusicBean?.bvid?.takeIf { it.isNotEmpty() }?.let { bvid ->
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


    private fun toggleCurrentPlayback() {
        when {
            mediaPlayer?.isPlaying == true -> pausePlay()
            isPaused && mediaPlayer != null -> resumePlay()
            else -> {
                val index = resolveCurrentIndex()
                if (index != -1) playMusic(index)
            }
        }
    }

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
            isPlaying = false
            stopProgressUpdates()
            mediaPlayer?.let {
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
        currentPlayingRawName = null
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

    private fun startLyricLoad(bvid: String, durationSec: Int) {
        if (lyricFinishedBvid == bvid) return

        lyricLoadingBvid = bvid
        currentLyric = null
        lastContentText = null

        AppExecutors.io.execute {
            val cid = BiliSearchHelper.resolveCid(bvid) ?: 0L
            if (cid <= 0L) {
                mainHandler.post { if (lyricLoadingBvid == bvid) lyricFinishedBvid = bvid }
                return@execute
            }
            val lyric = BiliLyricHelper.fetch(
                bvid = bvid,
                cid = cid,
                durationSec = durationSec,
                cookie = SpUtils.getBiliCookie(this@MusicPlayerService)
            )
            mainHandler.post {
                if (lyricLoadingBvid != bvid) return@post
                lyricFinishedBvid = bvid
                currentLyric = lyric
                val name = currentPlayingName ?: return@post
                updateNotification(name, isPlaying())
            }
        }
    }

    private var lyricFinishedBvid: String? = null

    private fun tickNotificationLyric(positionMs: Long) {
        val name = currentPlayingName ?: return
        val next = notificationContentText(isPlaying(), positionMs)
        if (next == lastContentText) return
        updateNotification(name, isPlaying())
    }

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

        val builder: Notification.Builder = Notification.Builder(this, CHANNEL_ID)
        val contentText = notificationContentText(isPlaying, currentLyricPosition())
        lastContentText = contentText
        builder
            .setContentTitle(musicName)
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

    private fun refreshProgressDisplay() {
        val mp = mediaPlayer ?: return
        val name = currentPlayingName ?: return
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
                        if (appVisible) {
                            Intent(BROADCAST_PLAY_STATE).apply {
                                putExtra(EXTRA_CURRENT_POSITION, current)
                                putExtra(EXTRA_DURATION, duration)
                                setPackage(packageName)
                                sendBroadcast(this)
                            }
                        }
                        if (SpUtils.isVideoNotifyProgressEnabled(this@MusicPlayerService)) {
                            mediaSessionManager.updatePlaybackState(
                                PlaybackState.STATE_PLAYING, current.toLong(), 1.0f
                            )
                        }
                        (currentPlayingRawName ?: currentPlayingName)
                            ?.let { statsManager.addOneSecond(it) }
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
        mediaPlayer?.isLooping = newMode == SpUtils.PlayMode.SINGLE_LOOP
        val name = currentPlayingName ?: ""
        updateNotification(name, isPlaying())
    }


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
            currentPlayingRawName = list[index].musicName
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
                    currentPlayingRawName = biliBean.musicName
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
        val playing = isPlaying()
        mediaSessionManager.updatePlaybackState(
            if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            pos,
            if (playing) 1.0f else 0f
        )
        tickNotificationLyric(pos)
    }
}
