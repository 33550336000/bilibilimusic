package com.tilixibiesi.ui.page
import com.tilixibiesi.ui.SongDetailDialogs
import com.tilixibiesi.util.PlayTimer
import com.tilixibiesi.util.PlaylistDialogHelper
import com.tilixibiesi.util.ToastUtils

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import com.tilixibiesi.R
import com.tilixibiesi.bili.BiliHistoryHelper
import com.tilixibiesi.bili.BiliVideoPlayer
import com.tilixibiesi.data.AppPermissionsBridge
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.PlaybackStatsManager
import com.tilixibiesi.data.PlaylistManager
import com.tilixibiesi.data.ProtectedWords
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.network.HttpUtils
import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.ui.AccessibleTextView
import com.tilixibiesi.ui.MainPagerActivity
import com.tilixibiesi.ui.NowPlayingPage
import com.tilixibiesi.ui.adapter.MusicAdapter
import com.tilixibiesi.util.BackgroundHelper
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.DialogHelper
import java.io.File
import java.lang.ref.WeakReference

/**
 * 歌曲列表页（原 MainActivity）。
 *
 * 与原先的关键差异：
 *  - 由 Activity 变为 [BasePage]，与搜索 / 播放列表 / 设置页共处同一个 Window，
 *    因此左右滑动由 HorizontalPager 统一接管，页面本身不再注册 GestureDetector；
 *  - 原 onFling 里「左滑去搜索/设置、右滑去播放列表」的逻辑删除，改由分页顺序天然实现；
 *  - 广播、服务、定时器等仍运行在本页，宿主 Activity 销毁时统一 onDestroy。
 */
class SongsPage(base: Context) : BasePage(base) {

    companion object {
        /** 搜索页选中歌曲后投递的播放请求：位置 + 列表 */
        internal var pendingPlay: Pair<Int, List<MusicBean>>? = null
        /** 设置页请求进入「悬浮按钮调整模式」 */
        internal var pendingAdjustMode: Boolean = false

        var musicList = mutableListOf<MusicBean>()

        private const val MUSIC_DIR_REL = "system/axeron/long/Android/TilixiBiesiMusic"

        private const val IC_PLAY = android.R.drawable.ic_media_play
        private const val IC_PAUSE = android.R.drawable.ic_media_pause

        private const val IC_MODE_SEQUENCE = android.R.drawable.ic_menu_sort_by_size
        private const val IC_MODE_RANDOM = android.R.drawable.ic_menu_rotate
        private const val IC_MODE_SINGLE_LOOP = android.R.drawable.ic_menu_revert

        private const val DEFAULT_SEEK_MAX = 100
        /** 底部控制栏估算高度（dp）：标题 + 进度条 + 按钮行 + 上下 padding */
        private const val CONTROL_BAR_HEIGHT_DP = 132
    }

    private var originalSearchBg: Drawable? = null
    private val handler = Handler(Looper.getMainLooper())


    private lateinit var btnTimer: ImageButton

    private lateinit var lvMusic: ListView
    private lateinit var musicAdapter: MusicAdapter
    private lateinit var playerControlLayout: View
    private lateinit var tvCurrentMusic: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPlayMode: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var playStateReceiver: PlayStateReceiver
    private lateinit var btnSearchFloat: AccessibleTextView
    private lateinit var tvTodayDuration: TextView
    private lateinit var tvTotalTodayDuration: TextView
    private lateinit var btnDetail: Button

    /** 定时停止播放：状态与计时全部在 PlayTimer 内，页面只负责触发与 UI */
    private val playTimer by lazy {
        PlayTimer(this) {
            sendServiceAction(MusicPlayerService.ACTION_STOP)
            refreshPlayerUIForStop()
        }
    }

    private var isPlaying = false
    private var currentPlayPosition = -1
    private lateinit var currentPlayMode: SpUtils.PlayMode
    private var isAdjustMode = false
    private var isDragging = false
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var downRawX = 0f
    private var downRawY = 0f

    private var hasValidDuration = false
    private var isUserSeeking = false
    private val biliHistoryLock = Any()
    private var isLoadingBiliHistory = false

    /**
     * 「正在播放」覆盖层。懒创建：绝大多数会话不会用到它，
     * 而它内部要 inflate 整页布局并注册广播，没必要在页面创建时就付这份开销。
     */
    private var nowPlaying: NowPlayingPage? = null

    /** 当前正在播放的条目，供正在播放页展示标题/艺术家/专辑/封面 */
    private var currentPlayBean: MusicBean? = null

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        DataFileUtils.initRenameMap()
        startMusicService()
        AppPermissionsBridge.request(this)
        initView()
        applyTitleStyle()
        initReceiver()
        loadMusicList()
        SpUtils.clearCacheFilesOnly(applicationContext)
    }

    override fun onPageShow() {
        applyPendingRequests()
    }

    /**
     * 进入本页时的状态同步。
     *
     * **只做一次全量刷新**：这里原先在开头刷一次、syncPlayerControlState() 内又刷一次，
     * 同一帧内 ListView 被全量重绑两遍（每个 item 都要走 getView → getDisplayName），
     * 200 首歌规模下肉眼可感地卡一下。现在所有"改数据"的操作先做完，最后统一刷一次。
     */
    override fun onResume() {
        stopBiliBackgroundPlayback()
        applyBackgroundSettings()
        // 本页成为当前页：背景视频恢复播放并出声
        BackgroundHelper.setActive(findViewById(R.id.main_bg_host)!!, true)
        applyTitleStyle()
        updateSearchFloatVisibility()
        btnSearchFloat.post {
            applySearchButtonPosition()
            applySearchButtonStyle()
        }
        loadBiliHistoryToMusicList()
        // 只同步数据/控件，不刷新列表（刷新在下面统一做）
        syncPlayerControlState(notifyList = false)
        PlaybackStatsManager.refresh()
        if (MusicPlayerService.isPlaying && MusicPlayerService.currentPlayingName != null) {
            PlaybackStatsManager.startUpdater()
        } else {
            PlaybackStatsManager.stopUpdater()
        }
        musicAdapter.notifyDataSetChanged()
        // 回到本页时「正在播放」页可能仍开着（例如去设置页改了背景再滑回来）：
        // 重新同步一次，避免它停在离开前的旧状态。
        nowPlaying?.takeIf { it.isVisible }?.let { page ->
            resolveCurrentBean()?.let { bean ->
                if (bean !== currentPlayBean) {
                    currentPlayBean = bean
                    page.updateTrack(bean)
                }
            }
            page.refresh()
            sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
        }
    }

    override fun onPause() {
        if (isAdjustMode) exitAdjustMode()
        PlaybackStatsManager.stopUpdater()
        // 离开本页：暂停背景视频并静音
        BackgroundHelper.setActive(findViewById(R.id.main_bg_host)!!, false)
    }

    /**
     * 屏幕旋转。
     *
     * 宿主声明了 configChanges=orientation|screenSize，旋转不会重建 Activity，
     * 页面也不会重新 inflate，因此这里要主动把新方向转给「正在播放」覆盖层
     * ——它挂在 decorView 上，不在本页视图树里，收不到任何自动刷新。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        nowPlaying?.onConfigurationChanged()
    }

    override fun onDestroy() {
        playTimer.cancel()
        songDetailDialogs.dismiss()
        // 正在播放页挂在 decorView 上，不在页面视图树里：不显式释放就是一处 Activity 泄漏
        nowPlaying?.release()
        nowPlaying = null
        PlaybackStatsManager.stopUpdater()
        PlaybackStatsManager.releaseViews()
        unregisterPageReceiver(playStateReceiver)
        // 释放背景视频解码器（VideoView 脱离视图树不会自动 release）
        findViewById<View>(R.id.main_bg_host)?.let { BackgroundHelper.release(it) }
        super.onDestroy()
    }

    /** 消费其它页投递过来的请求（搜索页选歌、设置页调整按钮） */
    private fun applyPendingRequests() {
        pendingPlay?.let { (pos, list) ->
            pendingPlay = null
            applyMusicListUpdate(list)
            checkLocalFiles()
            playAtPosition(pos, musicList)
            lvMusic.post { lvMusic.smoothScrollToPosition(pos) }
            loadBiliHistoryToMusicList()
        }
        if (pendingAdjustMode) {
            pendingAdjustMode = false
            enterAdjustMode()
        }
    }

    /** 宿主收到「调整悬浮按钮」的 Intent 时调用 */
    fun enterAdjustModeFromHost() {
        enterAdjustMode()
    }

    override fun onNewIntent(intent: Intent) {
        // 若携带 storage_root extra，应用新的存储路径（含原路径文件复制迁移）
        applyStorageRootFromIntent(intent)
        stopBiliBackgroundPlayback()
        if (intent.getBooleanExtra(MainPagerActivity.EXTRA_ADJUST_SEARCH_BUTTON, false)) {
            enterAdjustMode()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (SpUtils.getVolumeKeySwitch(this) && currentPlayPosition != -1 && musicList.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    btnPrev.performClick(); return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    btnNext.performClick(); return true
                }
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        AppPermissionsBridge.onResult(this, requestCode, permissions, grantResults)
    }

    // ==================== 初始化 ====================

    private fun startMusicService() {
        startForegroundService(Intent(this, MusicPlayerService::class.java))
    }

    private fun initView() {
        lvMusic = findViewById(R.id.lv_music)!!
        playerControlLayout = findViewById(R.id.layout_player_control)!!
        tvCurrentMusic = findViewById(R.id.tv_current_music)!!
        btnPlayPause = findViewById(R.id.btn_play_pause)!!
        btnPrev = findViewById(R.id.btn_prev)!!
        btnNext = findViewById(R.id.btn_next)!!
        btnPlayMode = findViewById(R.id.btn_play_mode)!!
        seekBar = findViewById(R.id.seekbar_progress)!!
        btnSearchFloat = findViewById(R.id.btn_search_float)!!
        originalSearchBg = btnSearchFloat.background
        btnTimer = findViewById(R.id.btn_timer)!!
        btnDetail = findViewById(R.id.btn_detail)!!
        tvTodayDuration = findViewById(R.id.tv_today_duration)!!
        tvTodayDuration.visibility = View.GONE
        tvTotalTodayDuration = findViewById(R.id.tv_total_today_duration)!!
        tvTotalTodayDuration.visibility = View.GONE

        PlaybackStatsManager.initViews(tvTodayDuration, tvTotalTodayDuration)

        btnDetail.setOnClickListener { songDetailDialogs.showDetailDialog() }
        btnTimer.setOnClickListener { showTimerDialog() }

        btnSearchFloat.setOnClickListener {
            if (isAdjustMode) exitAdjustMode()
            else gotoPage(MainPagerActivity.PAGE_SEARCH, true)
        }

        currentPlayMode = SpUtils.PlayMode.values()[SpUtils.getPlayMode(this)]
        updatePlayModeIcon()

        seekBar.visibility = View.VISIBLE
        seekBar.max = DEFAULT_SEEK_MAX
        seekBar.progress = 0
        seekBar.progressDrawable = resources.getDrawable(R.drawable.seekbar_track, null)
        seekBar.thumb = resources.getDrawable(R.drawable.seekbar_thumb, null)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                if (!hasValidDuration) {
                    ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.seek_not_available))
                    return
                }
                val targetMs = seekBar?.progress ?: 0
                // 只定位，不改变播放状态：暂停时拖动后仍保持暂停，
                // 由用户按播放键才从该位置继续。
                sendServiceAction(MusicPlayerService.ACTION_SEEK, MusicPlayerService.EXTRA_SEEK_POSITION to targetMs)
                // 立即回传一次进度，避免等到下一次每秒广播才更新进度条
                sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
            }
        })

        musicAdapter = MusicAdapter(this, musicList).apply { showDeleteButton = true }
        lvMusic.adapter = musicAdapter

        lvMusic.onItemClickListener =
            android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                if (position !in musicList.indices) return@OnItemClickListener
                // 点击列表项：若点的就是当前曲目，服务侧会切换播放/暂停并保留进度；
                // 点别的曲目则照常从头播放。
                playAtPosition(position, musicList, toggleIfCurrent = true)
            }

        lvMusic.onItemLongClickListener =
            android.widget.AdapterView.OnItemLongClickListener { _, _, position, _ ->
                if (position in musicList.indices) {
                    showLongPressActionDialog(musicList[position])
                }
                true
            }

        musicAdapter.onAddToPlaylistClickListener =
            object : MusicAdapter.OnAddToPlaylistClickListener {
                override fun onAddToPlaylistClick(position: Int, musicBean: MusicBean) {
                    PlaylistDialogHelper.showAddToPlaylistDialog(this@SongsPage, musicBean)
                }
            }
        musicAdapter.onDeleteClickListener = object : MusicAdapter.OnDeleteClickListener {
            override fun onDeleteClick(position: Int, musicBean: MusicBean) {
                showDeleteSongDialog(musicBean)
            }
        }

        updateSearchFloatVisibility()

        btnPlayPause.setOnClickListener {
            if (isPlaying) {
                sendServiceAction(MusicPlayerService.ACTION_PAUSE)
                updatePlayPauseButton(false)
            } else {
                if (MusicPlayerService.currentPlayingName != null) {
                    sendServiceAction(MusicPlayerService.ACTION_PLAY)
                    updatePlayPauseButton(true)
                } else {
                    ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.select_song_first))
                }
            }
        }

        btnPrev.setOnClickListener { playPrevious() }
        btnNext.setOnClickListener { playNext() }

        btnPlayMode.setOnClickListener {
            val nextModeOrdinal = (currentPlayMode.ordinal + 1) % SpUtils.PlayMode.values().size
            currentPlayMode = SpUtils.PlayMode.values()[nextModeOrdinal]
            SpUtils.savePlayMode(this, currentPlayMode.ordinal)
            sendServiceAction(MusicPlayerService.ACTION_SWITCH_MODE, MusicPlayerService.EXTRA_MODE to currentPlayMode.ordinal)
            updatePlayModeIcon()
            val modeName = when (currentPlayMode) {
                SpUtils.PlayMode.SEQUENCE -> LanguageUtils.getString(this@SongsPage, R.string.mode_sequence)
                SpUtils.PlayMode.RANDOM -> LanguageUtils.getString(this@SongsPage, R.string.mode_random)
                SpUtils.PlayMode.SINGLE_LOOP -> LanguageUtils.getString(this@SongsPage, R.string.mode_single_loop)
            }
            ToastUtils.show(this@SongsPage, modeName)
        }

        setPlayerControlVisible(true)
        playerControlLayout.isFocusable = true
        playerControlLayout.isFocusableInTouchMode = true
        applyBackgroundSettings()

        // 点底部控制栏进入「正在播放」页。
        //
        // 监听挂在控制栏容器与标题上，而不是每个按钮：按钮自己消费点击，
        // 事件不会冒泡到容器，因此点空白处与点歌名都能进页面，
        // 而点播放/上一首/下一首/定时器等按钮时行为完全不变。
        playerControlLayout.setOnClickListener { openNowPlaying() }
        tvCurrentMusic.setOnClickListener { openNowPlaying() }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun initReceiver() {
        playStateReceiver = PlayStateReceiver()
        val filter = IntentFilter().apply {
            addAction(MusicPlayerService.BROADCAST_PLAY_STATE)
        }
        registerPageReceiver(playStateReceiver, filter)
    }

    // ==================== 数据 ====================

    private fun loadMusicList() {
        val musicFile = StoragePaths.resolveRead("music.txt")
        val now = System.currentTimeMillis()
        val isCacheValid = musicFile.exists() && (now - musicFile.lastModified()) < 24 * 60 * 60 * 1000L

        if (isCacheValid) {
            val cachedList = DataFileUtils.loadMusicList().toMutableList()
            applyBlockedWordsFilter(cachedList)
            val deletedNames = DataFileUtils.loadDeletedMusicNames()
            cachedList.removeAll { deletedNames.contains(it.musicName) }
            applyMusicListUpdate(cachedList)
            checkLocalFiles()
            musicAdapter.notifyDataSetChanged()
            setPlayerControlVisible(true)
            seekBar.visibility = View.VISIBLE
            loadBiliHistoryToMusicList()
            return
        }

        val localList = DataFileUtils.loadMusicList().toMutableList()
        val deletedNames = DataFileUtils.loadDeletedMusicNames()
        localList.removeAll { deletedNames.contains(it.musicName) }
        if (localList.isNotEmpty()) {
            applyMusicListUpdate(localList)
            checkLocalFiles()
            musicAdapter.notifyDataSetChanged()
            setPlayerControlVisible(true)
            seekBar.visibility = View.VISIBLE
        }

        HttpUtils.getMusicListAsync(this, object : HttpUtils.OnMusicListLoadListener {
            override fun onSuccess(musicList: List<MusicBean>) {
                runOnUiThread {
                    val del = DataFileUtils.loadDeletedMusicNames()
                    val filtered = musicList.filter { !del.contains(it.musicName) }.toMutableList()
                    applyBlockedWordsFilter(filtered)
                    applyMusicListUpdate(filtered)
                    DataFileUtils.saveMusicList(filtered)
                    checkLocalFiles()
                    musicAdapter.notifyDataSetChanged()
                    ToastUtils.show(this@SongsPage, 
                        LanguageUtils.getString(this@SongsPage, R.string.music_list_updated, musicList.size)
                    )
                    loadBiliHistoryToMusicList()
                }
            }

            override fun onFailed(errorMsg: String) {
                runOnUiThread {
                    if (musicList.isEmpty()) {
                        ToastUtils.show(this@SongsPage, errorMsg)
                    }
                    loadBiliHistoryToMusicList()
                }
            }
        })
    }

    private fun applyMusicListUpdate(newList: List<MusicBean>) {
        musicList.clear()
        musicList.addAll(newList)
        MusicPlayerService.musicList = musicList
        val pureList = musicList.filter { !it.isBilibili }
        SpUtils.saveMusicList(this, pureList)
        DataFileUtils.saveMusicList(pureList)
    }

    private fun applyBlockedWordsFilter(list: MutableList<MusicBean>) {
        val blocked = DataFileUtils.loadBlockedWords()
        if (blocked.isEmpty()) return
        list.removeAll { bean -> blocked.any { word -> bean.musicName.contains(word, ignoreCase = true) } }
    }

    private fun checkLocalFiles() {
        StoragePaths.resolveWrite(MUSIC_DIR_REL).listFiles()?.forEach { f ->
            musicList.find { it.musicName == f.name }?.apply {
                isDownloaded = true
                localPath = f.absolutePath
            }
        }
    }

    private fun loadBiliHistoryToMusicList() {
        synchronized(biliHistoryLock) {
            if (isLoadingBiliHistory) return
            isLoadingBiliHistory = true
        }
        val pageRef = WeakReference<SongsPage>(this)
        val mainHandler = handler
        AppExecutors.io.execute {
            try {
                val biliBeans = BiliHistoryHelper.loadAll()
                if (biliBeans.isEmpty()) {
                    synchronized(biliHistoryLock) { isLoadingBiliHistory = false }
                    return@execute
                }
                mainHandler.post {
                    val page = pageRef.get()
                    if (page == null) {
                        synchronized(biliHistoryLock) { isLoadingBiliHistory = false }
                        return@post
                    }
                    page.applyBiliBeans(biliBeans)
                }
            } catch (e: Exception) {
                synchronized(biliHistoryLock) { isLoadingBiliHistory = false }
            }
        }
    }

    /** 在主线程把 B 站历史合并进列表并复位加载标记 */
    private fun applyBiliBeans(biliBeans: List<MusicBean>) {
        // 插入前先记住当前选中/播放的歌（按标识，不用索引），
        // 因为 addAll(0, ...) 会让所有本地歌曲的索引整体后移 biliBeans.size。
        val anchorName = currentPlayPosition.takeIf { it in musicList.indices }
            ?.let { musicList[it].musicName }
            ?: MusicPlayerService.currentPlayingName
        musicList.removeAll { it.isBilibili }
        musicList.addAll(0, biliBeans)
        MusicPlayerService.musicList = musicList
        // 按歌名重新定位，索引自动落在新的（含 B 站的）基准上
        if (anchorName != null) {
            val newPos = musicList.indexOfFirst { it.musicName == anchorName }
            if (newPos != -1) {
                currentPlayPosition = newPos
                musicList.forEachIndexed { i, bean -> bean.isPlaying = (i == newPos) }
            }
        }
        restorePlayingHighlight()
        musicAdapter.notifyDataSetChanged()
        synchronized(biliHistoryLock) { isLoadingBiliHistory = false }
    }

    private fun restorePlayingHighlight() {
        val playingName = MusicPlayerService.currentPlayingName ?: return
        val pos = musicList.indexOfFirst {
            DataFileUtils.getDisplayName(it.musicName) == playingName
        }
        if (pos != -1) {
            currentPlayPosition = pos
            musicList.forEachIndexed { i, bean -> bean.isPlaying = (i == pos) }
        }
    }

    /**
     * 播放列表中的某一项。
     *
     * @param toggleIfCurrent 点击列表项时传 true：若点的是**当前曲目**，
     *   则切换播放/暂停并保留进度，而不是从头重播。上一首/下一首传 false
     *   ——它们是"换一首"的语义，即便单曲列表下位置恰好相同也不该变成暂停。
     */
    private fun playAtPosition(position: Int, list: List<MusicBean>, toggleIfCurrent: Boolean = false) {
        if (position !in list.indices) return
        val bean = list[position]
        // 是否点的就是当前这首：用显示名比对，与 restorePlayingHighlight /
        // syncPlayerControlState 的判据一致（暂停时 currentPlayingName 仍在，故也成立）
        val isCurrentTrack = toggleIfCurrent &&
            MusicPlayerService.currentPlayingName != null &&
            DataFileUtils.getDisplayName(bean.musicName) == MusicPlayerService.currentPlayingName
        for (b in musicList) b.isPlaying = false
        bean.isPlaying = true
        // 交给服务的是「与展示列表同结构」的列表（B 站在前），
        // 使 position 在服务侧指向同一首歌；持久化仍只存本地歌曲。
        MusicPlayerService.musicList = list.toMutableList()
        SpUtils.saveMusicList(this, list.filter { !it.isBilibili })
        sendServiceAction(
            MusicPlayerService.ACTION_PLAY,
            MusicPlayerService.EXTRA_POSITION to position,
            MusicPlayerService.EXTRA_TOGGLE_IF_CURRENT to toggleIfCurrent
        )
        currentPlayPosition = position
        musicAdapter.notifyDataSetChanged()
        tvCurrentMusic.text = DataFileUtils.getDisplayName(bean.musicName)
        setPlayerControlVisible(true)
        seekBar.visibility = View.VISIBLE
        if (isCurrentTrack) {
            // 切换当前曲目：播放/暂停图标与进度都由服务回传的广播决定。
            // 这里**不能**写死 updatePlayPauseButton(true)（刚点了暂停却显示"暂停中"），
            // 更不能 resetSeekBar()（会把用户要保留的进度抹成 0 并禁用拖动）。
            sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
        } else {
            updatePlayPauseButton(true)
            resetSeekBar()
        }
    }

    // ==================== 播放控制 ====================

    /**
     * 上一首 / 下一首。
     *
     * 抽成独立方法而不是内联在按钮监听里：正在播放页的「上一首/下一首」
     * 也要走同一套定位规则（含首尾环绕），两份实现迟早会走岔。
     */
    private fun playPrevious() {
        if (currentPlayPosition == -1 || musicList.isEmpty()) return
        val prevPos = if (currentPlayPosition - 1 < 0) musicList.size - 1 else currentPlayPosition - 1
        playAtPosition(prevPos, musicList)
    }

    private fun playNext() {
        if (currentPlayPosition == -1 || musicList.isEmpty()) return
        val nextPos = (currentPlayPosition + 1) % musicList.size
        playAtPosition(nextPos, musicList)
    }

    private fun setPlayerControlVisible(visible: Boolean) {
        playerControlLayout.visibility = if (visible) View.VISIBLE else View.GONE
        val bottomPadding = if (visible) CONTROL_BAR_HEIGHT_DP else 0
        val px = (bottomPadding * resources.displayMetrics.density).toInt()
        if (lvMusic.paddingBottom != px) {
            lvMusic.setPadding(lvMusic.paddingLeft, lvMusic.paddingTop, lvMusic.paddingRight, px)
            lvMusic.clipToPadding = false
        }
    }

    private fun sendServiceAction(action: String, vararg extras: Pair<String, Any>) {
        val intent = Intent(this, MusicPlayerService::class.java).apply {
            this.action = action
            extras.forEach { (key, value) ->
                when (value) {
                    is Int -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                    is String -> putExtra(key, value)
                }
            }
        }
        startService(intent)
    }

    private fun updatePlayPauseButton(playing: Boolean) {
        isPlaying = playing
        btnPlayPause.setImageResource(if (playing) IC_PAUSE else IC_PLAY)
    }

    /**
     * 重置进度条。
     *
     * 注意：暂停**不应**走到这里。
     * 暂停只是播放状态变化，歌曲仍是同一首、时长依然有效，
     * 此时重置会让进度回到 0 并禁用拖动，用户无法跳到指定位置。
     */
    private fun resetSeekBar() {
        hasValidDuration = false
        seekBar.max = DEFAULT_SEEK_MAX
        seekBar.progress = 0
        seekBar.isEnabled = false
    }

    private fun updateSeekBarState(current: Int, duration: Int) {
        if (duration > 0) {
            seekBar.max = duration
            seekBar.isEnabled = true
            hasValidDuration = true
        }
        // 拖动中不覆盖，避免把用户正在拖的位置拉回播放器的旧位置
        if (!isUserSeeking) {
            seekBar.progress = current.coerceAtMost(seekBar.max)
        }
    }

    private fun updatePlayModeIcon() {
        val iconRes = when (currentPlayMode) {
            SpUtils.PlayMode.SEQUENCE -> IC_MODE_SEQUENCE
            SpUtils.PlayMode.RANDOM -> IC_MODE_RANDOM
            SpUtils.PlayMode.SINGLE_LOOP -> IC_MODE_SINGLE_LOOP
        }
        btnPlayMode.setImageResource(iconRes)
    }

    /**
     * 更新"当前播放"相关的 UI 与列表高亮。
     *
     * @param notifyList 是否在此处刷新列表。批量调用场景传 false，由调用方
     *   在所有数据改完后统一刷一次——notifyDataSetChanged 会让 ListView
     *   丢弃全部已绑定 View 并重走 getView，同一时间窗口内刷两次等于白跑一遍。
     */
    private fun updateCurrentMusicInfo(
        musicName: String?,
        position: Int?,
        notifyList: Boolean = true
    ) {
        if (musicName != null) {
            val nameChanged = DataFileUtils.getDisplayName(musicName) !=
                MusicPlayerService.currentPlayingName.let { DataFileUtils.getDisplayName(it ?: "") }
            tvCurrentMusic.text = DataFileUtils.getDisplayName(musicName)
            setPlayerControlVisible(true)
            seekBar.visibility = View.VISIBLE
            isUserSeeking = false
            // 只有真正切歌才清零进度；暂停/恢复的广播同样带歌名，
            // 若不判断就会把当前进度抹掉且禁用拖动。
            if (nameChanged) {
                resetSeekBar()
                // 立即取一次进度，暂停状态下也能马上拿到时长并恢复可拖动
                sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
            }
        }
        if (position != null && position != -1) {
            currentPlayPosition = position
            for (i in musicList.indices) musicList[i].isPlaying = (i == position)
            if (notifyList) musicAdapter.notifyDataSetChanged()
            lvMusic.smoothScrollToPosition(position)
        }
    }

    // ==================== 正在播放页 ====================

    /**
     * 打开「正在播放」页。
     *
     * 没有任何正在播放的曲目时不打开：否则用户会看到一个全是"未知"的空页面，
     * 比不响应更让人困惑。
     */
    private fun openNowPlaying() {
        val bean = resolveCurrentBean() ?: return
        currentPlayBean = bean
        val page = ensureNowPlaying()
        page.updateTrack(bean)
        page.show()
        // 页面里的进度条要立刻显示真实进度，而不是等下一次每秒广播
        sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
    }

    /**
     * 取当前播放条目。
     *
     * 优先用已记录的下标，但它可能因 B 站历史插入而整体偏移（见 applyBiliBeans），
     * 因此拿不到或对不上时回退按歌名定位——与 restorePlayingHighlight 同一套判据。
     */
    private fun resolveCurrentBean(): MusicBean? {
        musicList.getOrNull(currentPlayPosition)?.let { return it }
        val playingName = MusicPlayerService.currentPlayingName ?: return null
        val index = musicList.indexOfFirst {
            DataFileUtils.getDisplayName(it.musicName) == playingName
        }
        return musicList.getOrNull(index)
    }

    private fun ensureNowPlaying(): NowPlayingPage {
        nowPlaying?.let { return it }
        val page = NowPlayingPage(activity, object : NowPlayingPage.Commands {
            override fun onPlayPause() {
                // 复用底部控制栏的同一段逻辑，避免两处对"播放/暂停"的理解走岔
                btnPlayPause.performClick()
            }

            override fun onPrev() = playPrevious()
            override fun onNext() = playNext()
            override fun onSeek(positionMs: Int) {
                sendServiceAction(
                    MusicPlayerService.ACTION_SEEK,
                    MusicPlayerService.EXTRA_SEEK_POSITION to positionMs
                )
                sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
            }
        })
        nowPlaying = page
        return page
    }

    /**
     * 返回键：交给正在播放页消费。
     *
     * @return true 表示已消费（歌词页 → 正在播放页，或收起整页）
     */
    fun handleNowPlayingBack(): Boolean = nowPlaying?.handleBack() == true

    /**
     * 把播放广播转发给正在播放页；页面未创建时是空操作。
     *
     * @param listIndex  曲目在 [musicList] 中的下标；未知传 null
     * @param currentMs  播放位置（毫秒）；广播未携带时传 null（暂停广播就是这种）
     * @param durationMs 总时长（毫秒）；广播未携带时传 null
     */
    private fun syncNowPlaying(
        musicName: String?,
        listIndex: Int?,
        currentMs: Int?,
        durationMs: Int?,
        isPlaying: Boolean?
    ) {
        val page = nowPlaying ?: return
        // 歌名变化才重新解析元数据：进度广播每秒都来，
        // 每次都重算标题/发起封面请求会白耗流量与内存。
        if (!musicName.isNullOrEmpty()) {
            val bean = musicList.getOrNull(listIndex ?: -1)
                ?: musicList.firstOrNull {
                    DataFileUtils.getDisplayName(it.musicName) == musicName
                }
            if (bean != null && bean !== currentPlayBean) {
                currentPlayBean = bean
                page.updateTrack(bean)
            }
        }
        page.updateProgress(
            positionMs = currentMs,
            durationMs = durationMs,
            isPlaying = isPlaying ?: MusicPlayerService.isPlaying
        )
    }

    private fun refreshPlayerUIForStop() {
        updatePlayPauseButton(false)
        currentPlayPosition = -1
        tvCurrentMusic.text = ""
        setPlayerControlVisible(true)
        seekBar.visibility = View.GONE
        resetSeekBar()
    }

    /**
     * 同步播放器控件与列表的 isPlaying 标记。
     *
     * @param notifyList 是否在此处刷新列表。批量调用（如 onResume 内）传 false，
     *   由调用方在所有数据改完后统一刷一次，避免同一帧多次全量重绑。
     */
    private fun syncPlayerControlState(notifyList: Boolean = true) {
        val playingName = MusicPlayerService.currentPlayingName
        val isServicePlaying = MusicPlayerService.isPlaying
        if (playingName != null) {
            currentPlayPosition = musicList.indexOfFirst {
                DataFileUtils.getDisplayName(it.musicName) == playingName
            }
            tvCurrentMusic.text = playingName
            updatePlayPauseButton(isServicePlaying)
            musicList.forEachIndexed { i, bean -> bean.isPlaying = (i == currentPlayPosition) }
            if (notifyList) musicAdapter.notifyDataSetChanged()
            // 无论播放还是暂停都同步一次进度：暂停时进度条要保持显示且可拖动
            sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
        } else {
            refreshPlayerUIForStop()
            // 停止态同样改了 isPlaying 标记（refreshPlayerUIForStop 复位了
            // currentPlayPosition），原先依赖 onResume 开头那次刷新来反映，
            // 合并刷新后必须在这里补上，否则高亮状态不更新。
            if (notifyList) musicAdapter.notifyDataSetChanged()
        }
    }

    // ==================== 悬浮按钮调整模式 ====================

    private fun enterAdjustMode() {
        isAdjustMode = true
        btnSearchFloat.background = resources.getDrawable(R.drawable.search_button_adjust_bg, null)
        ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.adjust_mode_hint))
        setupDragForSearchButton()
        btnSearchFloat.visibility = View.VISIBLE
        applySearchButtonPosition()
    }

    private fun exitAdjustMode() {
        isAdjustMode = false
        lvMusic.isEnabled = true
        btnSearchFloat.background = originalSearchBg
            ?: resources.getDrawable(R.drawable.button_float_bg, null)
        btnSearchFloat.setOnTouchListener(null)
        ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.position_saved))
        updateSearchFloatVisibility()
    }

    private fun applySearchButtonPosition() {
        val x = SpUtils.getSearchButtonX(this)
        val y = SpUtils.getSearchButtonY(this)
        if (x == -1f || y == -1f) return
        val parent = btnSearchFloat.parent as View
        val originalLeft = btnSearchFloat.left - btnSearchFloat.translationX
        val originalTop = btnSearchFloat.top - btnSearchFloat.translationY
        val maxTx = parent.width - btnSearchFloat.width - originalLeft
        val maxTy = parent.height - btnSearchFloat.height - originalTop
        btnSearchFloat.translationX = x.coerceIn(-originalLeft, maxTx)
        btnSearchFloat.translationY = y.coerceIn(-originalTop, maxTy)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragForSearchButton() {
        btnSearchFloat.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    startTranslationX = v.translationX
                    startTranslationY = v.translationY
                    downRawX = event.rawX
                    downRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                        isDragging = true
                        val parent = v.parent as View
                        val originalLeft = v.left - v.translationX
                        val originalTop = v.top - v.translationY
                        val minTx = -originalLeft
                        val maxTx = parent.width - v.width - originalLeft
                        val minTy = -originalTop
                        val maxTy = parent.height - v.height - originalTop
                        v.translationX = (startTranslationX + deltaX).coerceIn(minTx, maxTx)
                        v.translationY = (startTranslationY + deltaY).coerceIn(minTy, maxTy)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    if (isDragging) {
                        SpUtils.saveSearchButtonPosition(this@SongsPage, v.translationX, v.translationY)
                        true
                    } else {
                        if (isAdjustMode) true else {
                            v.performClick()
                            true
                        }
                    }
                }
                else -> false
            }
        }
    }

    private fun updateSearchFloatVisibility() {
        btnSearchFloat.visibility =
            if (isAdjustMode || !SpUtils.getSearchMode(this)) View.VISIBLE else View.GONE
    }

    private fun applySearchButtonStyle() {
        btnSearchFloat.setTextColor(
            try {
                Color.parseColor(SpUtils.getFontColor(this))
            } catch (_: Exception) {
                Color.WHITE
            }
        )
        if (SpUtils.isSearchBtnTransparentStyle(this)) {
            btnSearchFloat.background = resources.getDrawable(R.drawable.search_button_adjust_bg, null)
        } else {
            originalSearchBg?.let { btnSearchFloat.background = it }
                ?: run { btnSearchFloat.background = resources.getDrawable(R.drawable.button_float_bg, null) }
        }
    }

    private fun applyBackgroundSettings() {
        val alphaPercent = SpUtils.getBackgroundAlpha(this)
        // 背景宿主是外层 FrameLayout：视频背景与内容层叠，而非把内容挤下去。
        BackgroundHelper.applyBackground(this, findViewById(R.id.main_bg_host)!!, alphaPercent)
    }

    private fun applyTitleStyle() {
        val titleTv = findViewById<TextView>(R.id.tv_title) ?: return
        val fontColor = try {
            Color.parseColor(SpUtils.getFontColor(this))
        } catch (_: Exception) {
            Color.WHITE
        }
        titleTv.text = LanguageUtils.getString(this@SongsPage, R.string.all_songs)
        btnDetail.text = LanguageUtils.getString(this@SongsPage, R.string.detail)
        btnSearchFloat.text = LanguageUtils.getString(this@SongsPage, R.string.search)
        titleTv.setTextColor(fontColor)
        titleTv.textSize = SpUtils.getFontSize(this).toFloat()
    }

    private fun stopBiliBackgroundPlayback() {
        try {
            BiliVideoPlayer.stopCurrentVideo()
        } catch (_: Exception) {
        }
    }

    // ==================== 存储根切换 ====================

    private fun applyStorageRootFromIntent(intent: Intent?) {
        if (intent == null) return
        val copyFrom = intent.getStringExtra(StoragePaths.EXTRA_COPY_FROM)
        val copyTo = intent.getStringExtra(StoragePaths.EXTRA_COPY_TO)
        if (!copyFrom.isNullOrBlank() && !copyTo.isNullOrBlank()) {
            val copied = StoragePaths.copyFileTo(copyFrom, copyTo)
            ToastUtils.show(this@SongsPage, 
                if (copied) LanguageUtils.getString(this@SongsPage, R.string.toast_file_copied)
                else LanguageUtils.getString(this@SongsPage, R.string.toast_file_copy_failed)
            )
            return
        }
        val path = intent.getStringExtra(StoragePaths.EXTRA_STORAGE_ROOT) ?: return
        if (path == StoragePaths.root()) return
        val success = StoragePaths.applyNewRoot(this, path)
        if (success) {
            ToastUtils.show(this@SongsPage, 
                LanguageUtils.getString(this@SongsPage, R.string.toast_storage_switched, StoragePaths.root())
            )
        }
    }

    // ==================== 对话框 ====================

    private fun showMaterialDialog(builder: AlertDialog.Builder): AlertDialog =
        DialogHelper.createStyledDialog(this, builder, makeMessageBoldItalic = true, overrideListViewItemColors = true)

    /** 播放详情两级弹窗（列表 → 每日明细），状态与流转封装在 SongDetailDialogs */
    private val songDetailDialogs by lazy {
        SongDetailDialogs(this) { showMaterialDialog(it) }
    }

    private fun showSimpleDialog(title: String, message: String, action: (() -> Unit)? = null) {
        val builder = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(LanguageUtils.getString(this@SongsPage, R.string.ok)) { _, _ -> action?.invoke() }
        showMaterialDialog(builder)
    }
    private fun showTimerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_timer, null)
        val etHour = dialogView.findViewById<EditText>(R.id.et_hour)
        val etMinute = dialogView.findViewById<EditText>(R.id.et_minute)
        val etSecond = dialogView.findViewById<EditText>(R.id.et_second)
        showMaterialDialog(
            AlertDialog.Builder(this, R.style.TransparentDialog)
                .setTitle(LanguageUtils.getString(this@SongsPage, R.string.timer_title))
                .setView(dialogView)
                .setPositiveButton(LanguageUtils.getString(this@SongsPage, R.string.ok)) { _, _ ->
                    val hour = etHour.text.toString().toIntOrNull() ?: 0
                    val minute = etMinute.text.toString().toIntOrNull() ?: 0
                    val second = etSecond.text.toString().toIntOrNull() ?: 0
                    val totalSeconds = hour * 3600 + minute * 60 + second
                    if (totalSeconds <= 0) {
                        ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.timer_zero_error))
                        return@setPositiveButton
                    }
                    playTimer.start(totalSeconds)
                }
                .setNegativeButton(LanguageUtils.getString(this@SongsPage, R.string.cancel), null)
                .setNeutralButton(LanguageUtils.getString(this@SongsPage, R.string.timer_cancel_btn)) { _, _ -> playTimer.cancel() }
        )
    }
    private fun showLongPressActionDialog(bean: MusicBean) {
        showMaterialDialog(
            AlertDialog.Builder(this, R.style.TransparentDialog)
                .setTitle(DataFileUtils.getDisplayName(bean.musicName))
                .setNegativeButton(
                    LanguageUtils.getString(this@SongsPage, R.string.long_press_restore_original)
                ) { _, _ -> restoreOriginalName(bean) }
                .setPositiveButton(LanguageUtils.getString(this@SongsPage, R.string.rename_button)) { _, _ ->
                    showRenameDialog(bean)
                }
        )
    }

    private fun restoreOriginalName(bean: MusicBean) {
        val original = bean.musicName
        val removed = DataFileUtils.removeRenameEntry(original)
        if (removed) {
            musicAdapter.notifyDataSetChanged()
            if (isPlaying && currentPlayPosition in musicList.indices &&
                musicList[currentPlayPosition].musicName == original
            ) {
                tvCurrentMusic.text = DataFileUtils.getDisplayName(original)
            }
            ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.restore_original_success))
        } else {
            ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.restore_original_not_needed))
        }
    }

    private fun showRenameDialog(bean: MusicBean) {
        showMaterialDialog(
            AlertDialog.Builder(this, R.style.TransparentDialog)
                .setTitle(LanguageUtils.getString(this@SongsPage, R.string.rename_song_title))
                .setMessage(
                    LanguageUtils.getString(
                        this@SongsPage, R.string.rename_current_name,
                        DataFileUtils.getDisplayName(bean.musicName)
                    )
                )
                .setPositiveButton(LanguageUtils.getString(this@SongsPage, R.string.rename_button)) { _, _ ->
                    showRenameInputDialog(bean.musicName)
                }
                .setNegativeButton(LanguageUtils.getString(this@SongsPage, R.string.cancel), null)
        )
    }

    private fun showRenameInputDialog(originalName: String) {
        val fontColor = try {
            Color.parseColor(SpUtils.getFontColor(this))
        } catch (_: Exception) {
            Color.WHITE
        }
        val input = EditText(this).apply {
            setText(DataFileUtils.getDisplayName(originalName))
            setSelection(text.length)
            background = resources.getDrawable(R.drawable.edittext_bg, null)
            setTextColor(fontColor)
            setHintTextColor(Color.GRAY)
            setPadding(12, 12, 12, 12)
        }
        showMaterialDialog(
            AlertDialog.Builder(this, R.style.TransparentDialog)
                .setTitle(LanguageUtils.getString(this@SongsPage, R.string.rename_input_title))
                .setView(input)
                .setPositiveButton(LanguageUtils.getString(this@SongsPage, R.string.ok)) { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isEmpty()) {
                        ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.rename_empty_error))
                        return@setPositiveButton
                    }
                    if (ProtectedWords.KEYWORDS.any { originalName.contains(it) }) {
                        showSimpleDialog(
                            LanguageUtils.getString(this@SongsPage, R.string.delete_restricted_title),
                            LanguageUtils.getString(this@SongsPage, R.string.rename_restricted)
                        )
                        return@setPositiveButton
                    }
                    DataFileUtils.saveRenameEntry(originalName, newName)
                    musicAdapter.notifyDataSetChanged()
                    if (isPlaying && currentPlayPosition in musicList.indices &&
                        musicList[currentPlayPosition].musicName == originalName
                    ) {
                        tvCurrentMusic.text = newName
                    }
                    ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.rename_success))
                }
                .setNegativeButton(LanguageUtils.getString(this@SongsPage, R.string.cancel), null)
        )
    }

    private fun showDeleteSongDialog(bean: MusicBean) {
        val musicName = bean.musicName
        val displayName = DataFileUtils.getDisplayName(musicName)
        if (ProtectedWords.KEYWORDS.any { musicName.contains(it) }) {
            showSimpleDialog(
                LanguageUtils.getString(this@SongsPage, R.string.delete_restricted_title),
                LanguageUtils.getString(this@SongsPage, R.string.delete_restricted_message, displayName)
            )
            return
        }
        showMaterialDialog(
            AlertDialog.Builder(this, R.style.TransparentDialog)
                .setTitle(LanguageUtils.getString(this@SongsPage, R.string.delete_song_title))
                .setMessage(
                    LanguageUtils.getString(this@SongsPage, R.string.delete_song_message, displayName)
                )
                .setPositiveButton(LanguageUtils.getString(this@SongsPage, R.string.ok)) { _, _ ->
                    executeDeleteMusic(bean)
                }
                .setNegativeButton(LanguageUtils.getString(this@SongsPage, R.string.cancel), null)
        )
    }

    private fun executeDeleteMusic(bean: MusicBean) {
        val displayName = DataFileUtils.getDisplayName(bean.musicName)
        if (bean.isBilibili) {
            bean.localPath?.let { File(it).delete() }
            bean.bvid?.let { removeBiliEntryFromHistory(it) }
            musicList.remove(bean)
            PlaylistManager.getPlaylists(this).forEach { it.musicList.removeAll { m -> m.musicName == bean.musicName } }
            if (MusicPlayerService.currentPlayingName == bean.musicName) {
                sendServiceAction(MusicPlayerService.ACTION_STOP)
                refreshPlayerUIForStop()
            }
            musicAdapter.notifyDataSetChanged()
            ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.delete_success, displayName))
            return
        }

        if (!DataFileUtils.saveDeletedMusicNameSafe(bean.musicName, this)) return
        musicList.remove(bean)
        PlaylistManager.getPlaylists(this).forEach { it.musicList.removeAll { m -> m.musicName == bean.musicName } }
        PlaylistManager.savePlaylists(this, PlaylistManager.getPlaylists(this))

        if (MusicPlayerService.currentPlayingName == bean.musicName) {
            sendServiceAction(MusicPlayerService.ACTION_STOP)
            refreshPlayerUIForStop()
        }
        musicAdapter.notifyDataSetChanged()

        val pureList = musicList.filter { !it.isBilibili }
        DataFileUtils.saveMusicList(pureList)
        SpUtils.saveMusicList(this, pureList)

        ToastUtils.show(this@SongsPage, LanguageUtils.getString(this@SongsPage, R.string.delete_success, displayName))
    }

    private fun removeBiliEntryFromHistory(bvid: String) {
        AppExecutors.io.execute {
            BiliHistoryHelper.removeByBvid(bvid)
        }
    }

    // ==================== 播放状态广播 ====================

    private inner class PlayStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MusicPlayerService.BROADCAST_PLAY_STATE) return

            if (intent.hasExtra(MusicPlayerService.EXTRA_IS_PLAYING)) {
                val playing = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false)
                updatePlayPauseButton(playing)
                if (playing) {
                    PlaybackStatsManager.startUpdater()
                } else {
                    // 暂停时「今日时长」仍要显示（见 PlaybackStatsManager.updateDisplay），
                    // 但 stopUpdater() 只移除每秒回调、不会自己再刷一次，
                    // 于是最后那一秒的累加值可能没被读出来、数字停在上一秒。
                    // 这里先补刷一次再停：暂停正是用户最可能盯着这个数字看的时刻。
                    PlaybackStatsManager.refresh()
                    PlaybackStatsManager.stopUpdater()
                }
            }

            var listDataChanged = false
            val musicName = intent.getStringExtra(MusicPlayerService.EXTRA_MUSIC_NAME)
            if (!musicName.isNullOrEmpty()) {
                val index = musicList.indexOfFirst {
                    DataFileUtils.getDisplayName(it.musicName) == musicName
                }
                updateCurrentMusicInfo(musicName, index, notifyList = false)
                listDataChanged = true
            }

            if (intent.hasExtra(MusicPlayerService.EXTRA_PLAY_MODE)) {
                currentPlayMode = SpUtils.PlayMode.values()[
                    intent.getIntExtra(MusicPlayerService.EXTRA_PLAY_MODE, 0)
                ]
                updatePlayModeIcon()
            }

            if (intent.hasExtra(MusicPlayerService.EXTRA_CURRENT_POSITION)) {
                val current = intent.getIntExtra(MusicPlayerService.EXTRA_CURRENT_POSITION, 0)
                val duration = intent.getIntExtra(MusicPlayerService.EXTRA_DURATION, 0)
                updateSeekBarState(current, duration)
            }

            // 转发给「正在播放」页（未创建时内部直接返回）。
            // 放在最后：它只读已算好的值，不参与上面的列表刷新决策。
            syncNowPlaying(
                musicName = musicName,
                listIndex = currentPlayPosition.takeIf { it != -1 },
                // 用 hasExtra 判定而不是 getIntExtra(…, 0)：暂停广播不带位置，
                // 按 0 读会让正在播放页的进度条在按下暂停时跳回开头。
                currentMs = if (intent.hasExtra(MusicPlayerService.EXTRA_CURRENT_POSITION)) {
                    intent.getIntExtra(MusicPlayerService.EXTRA_CURRENT_POSITION, 0)
                } else null,
                durationMs = if (intent.hasExtra(MusicPlayerService.EXTRA_DURATION)) {
                    intent.getIntExtra(MusicPlayerService.EXTRA_DURATION, 0)
                } else null,
                isPlaying = if (intent.hasExtra(MusicPlayerService.EXTRA_IS_PLAYING)) {
                    intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false)
                } else null
            )

            // 上面各处只改数据，这里统一刷一次列表：
            // isPlaying 高亮与其它改动合并成一次 notifyDataSetChanged，
            // 避免同一时间窗口内 ListView 被全量重绑多次。
            if (listDataChanged) musicAdapter.notifyDataSetChanged()

            // 原末尾的 PlaybackStatsManager.refresh() 已移除：
            // 它每次广播都要在主线程读一遍全部播放记录 JSON（loadPlaybackDetails
            // 遍历分片目录逐个解析），只为了刷新两个 TextView。而本方法在收到
            // EXTRA_IS_PLAYING 时已经启停了 startUpdater——updater 每秒会调
            // updateDisplay()，统计本就在持续刷新，这里再刷一次纯属重复。
            // 播放 B 站音乐后切回歌曲页时，onResume 与这条广播回传在相邻时间
            // 窗口内各刷一次，是切页卡顿的加重因素之一。
        }
    }
}
