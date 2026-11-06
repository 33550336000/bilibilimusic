package com.tilixibiesi.ui.page

import com.tilixibiesi.util.AppLog
import com.tilixibiesi.ui.SongDetailDialogs
import com.tilixibiesi.util.PlayTimer
import com.tilixibiesi.util.PlaylistDialogHelper
import com.tilixibiesi.util.ToastUtils

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
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import com.tilixibiesi.R
import com.tilixibiesi.bili.BiliHistoryHelper
import com.tilixibiesi.bili.BiliMusicZone
import com.tilixibiesi.bili.BiliVideoPlayer
import com.tilixibiesi.bili.BiliCoverLoader
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
import com.tilixibiesi.ui.widget.PullToRefreshLayout
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.DialogHelper
import com.tilixibiesi.util.FontUtils
import com.tilixibiesi.util.ShareHelper
import java.io.File
import java.lang.ref.WeakReference

class SongsPage(base: Context) : BasePage(base) {

    companion object {
        private const val TAG = "SongsPage"

        internal var pendingPlay: Pair<Int, List<MusicBean>>? = null
        internal var pendingAdjustMode: Boolean = false

        var musicList = mutableListOf<MusicBean>()

        private const val MUSIC_DIR_REL = "system/axeron/long/Android/TilixiBiesiMusic"

        private const val IC_PLAY = android.R.drawable.ic_media_play
        private const val IC_PAUSE = android.R.drawable.ic_media_pause

        private const val IC_MODE_SEQUENCE = android.R.drawable.ic_menu_sort_by_size
        private const val IC_MODE_RANDOM = android.R.drawable.ic_menu_rotate
        private const val IC_MODE_SINGLE_LOOP = android.R.drawable.ic_menu_revert

        private const val DEFAULT_SEEK_MAX = 100
    }

    private var originalSearchBg: Drawable? = null
    private val handler = Handler(Looper.getMainLooper())


    private lateinit var btnTimer: ImageButton

    private lateinit var lvMusic: ListView
    private lateinit var pullRefresh: PullToRefreshLayout
    private lateinit var musicAdapter: MusicAdapter
    private lateinit var playerControlLayout: View
    private lateinit var tvCurrentMusic: TextView
    private lateinit var ivControlCover: ImageView
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

    /** 音乐分区翻页状态：下一页下标 / 是否还有更多 / 是否正在加载。 */
    private var zoneNextPage = 1
    private var zoneHasMore = true
    private var zoneLoading = false

    /** 连续空页计数：用于区分「翻到底了」和「这一页刚好请求失败」。 */
    private var zoneEmptyStreak = 0

    /** 下拉刷新进行中（防止连续下拉重复请求）。 */
    private var zoneRefreshing = false

    private var nowPlaying: NowPlayingPage? = null

    private var currentPlayBean: MusicBean? = null


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

    override fun onResume() {
        stopBiliBackgroundPlayback()
        applyTitleStyle()
        updateSearchFloatVisibility()
        btnSearchFloat.post {
            applySearchButtonPosition()
            applySearchButtonStyle()
        }
        loadBiliHistoryToMusicList()
        syncPlayerControlState(notifyList = false)
        PlaybackStatsManager.refresh()
        if (MusicPlayerService.isPlaying && MusicPlayerService.currentPlayingName != null) {
            PlaybackStatsManager.startUpdater()
        } else {
            PlaybackStatsManager.stopUpdater()
        }
        applyMainRowButtonSettings()
        musicAdapter.notifyDataSetChanged()
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
    }

    private fun applyMainRowButtonSettings() {
        if (!::musicAdapter.isInitialized) return
        val biliZone = SpUtils.isMainSourceBiliZone(this)
        val showAdd = SpUtils.isMainAddButtonVisible(this)
        // 分区列表里的条目不可从主页删除，只有内置歌曲列表才提供删除按钮
        val showDelete = !biliZone && SpUtils.isMainDeleteButtonVisible(this)
        // 下拉刷新只在分区模式有意义，内置列表保持原来的行为
        if (::pullRefresh.isInitialized) pullRefresh.refreshEnabled = biliZone
        if (musicAdapter.showAddButton == showAdd &&
            musicAdapter.showDeleteButton == showDelete &&
            musicAdapter.showCoverInsteadOfIndex == biliZone
        ) return
        musicAdapter.showAddButton = showAdd
        musicAdapter.showDeleteButton = showDelete
        musicAdapter.showCoverInsteadOfIndex = biliZone
        musicAdapter.notifyDataSetChanged()
        (activity as? MainPagerActivity)?.invalidateGlassBackdrop()
    }

    /** “我的”页切换数据源后调用：重新加载列表并立即换掉封面/序号表现。 */
    internal fun onMainSourceChanged() {
        if (!::musicAdapter.isInitialized) return
        applyMainRowButtonSettings()
        loadMusicList()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        nowPlaying?.onConfigurationChanged()
    }

    override fun onDestroy() {
        playTimer.cancel()
        songDetailDialogs.dismiss()
        nowPlaying?.release()
        nowPlaying = null
        PlaybackStatsManager.stopUpdater()
        PlaybackStatsManager.releaseViews()
        unregisterPageReceiver(playStateReceiver)
        super.onDestroy()
    }

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

    fun enterAdjustModeFromHost() {
        enterAdjustMode()
    }

    override fun onNewIntent(intent: Intent) {
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


    private fun startMusicService() {
        startForegroundService(Intent(this, MusicPlayerService::class.java))
    }

    private fun initView() {
        lvMusic = findViewById(R.id.lv_music)!!
        pullRefresh = findViewById(R.id.pull_refresh)!!
        playerControlLayout = activity.findViewById(R.id.layout_player_control)!!
        tvCurrentMusic = activity.findViewById(R.id.tv_current_music)!!
        ivControlCover = activity.findViewById(R.id.iv_control_cover)!!
        ivControlCover.clipToOutline = true
        btnPlayPause = activity.findViewById(R.id.btn_play_pause)!!
        btnPrev = activity.findViewById(R.id.btn_prev)!!
        btnNext = activity.findViewById(R.id.btn_next)!!
        btnPlayMode = activity.findViewById(R.id.btn_play_mode)!!
        seekBar = activity.findViewById(R.id.seekbar_progress)!!
        btnSearchFloat = findViewById(R.id.btn_search_float)!!
        originalSearchBg = btnSearchFloat.background
        btnTimer = activity.findViewById(R.id.btn_timer)!!
        btnDetail = activity.findViewById(R.id.btn_detail)!!
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
        seekBar.progressDrawable = resources.getDrawable(R.drawable.floating_seekbar_track, null)
        seekBar.thumb = resources.getDrawable(R.drawable.floating_seekbar_thumb, null)
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
                sendServiceAction(MusicPlayerService.ACTION_SEEK, MusicPlayerService.EXTRA_SEEK_POSITION to targetMs)
                sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
            }
        })

        musicAdapter = MusicAdapter(this, musicList).apply {
            val biliZone = SpUtils.isMainSourceBiliZone(this@SongsPage)
            showAddButton = SpUtils.isMainAddButtonVisible(this@SongsPage)
            showDeleteButton = !biliZone && SpUtils.isMainDeleteButtonVisible(this@SongsPage)
            showCoverInsteadOfIndex = biliZone
        }
        lvMusic.adapter = musicAdapter

        lvMusic.onItemClickListener =
            android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                if (position !in musicList.indices) return@OnItemClickListener
                playAtPosition(position, musicList, toggleIfCurrent = true)
            }

        lvMusic.onItemLongClickListener =
            android.widget.AdapterView.OnItemLongClickListener { _, _, position, _ ->
                if (position in musicList.indices) {
                    showLongPressActionDialog(musicList[position])
                }
                true
            }

        lvMusic.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {}
            override fun onScroll(
                view: android.widget.AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                if (!SpUtils.isMainSourceBiliZone(this@SongsPage)) return
                if (zoneLoading || !zoneHasMore || totalItemCount == 0) return
                if (firstVisibleItem + visibleItemCount >= totalItemCount - 1) {
                    loadNextZonePage()
                }
            }
        })

        // 下拉刷新：仅 B站音乐分区模式启用，内置列表保持原样
        pullRefresh.refreshEnabled = SpUtils.isMainSourceBiliZone(this)
        pullRefresh.onRefresh = { refreshZoneFromTop() }

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

        playerControlLayout.setOnClickListener { openNowPlaying() }
        tvCurrentMusic.setOnClickListener { openNowPlaying() }
    }

    private fun initReceiver() {
        playStateReceiver = PlayStateReceiver()
        val filter = IntentFilter().apply {
            addAction(MusicPlayerService.BROADCAST_PLAY_STATE)
        }
        registerPageReceiver(playStateReceiver, filter)
    }


    private fun loadMusicList() {
        if (SpUtils.isMainSourceBiliZone(this)) {
            loadBiliZoneMusicList()
            return
        }
        val musicFile = StoragePaths.resolveRead("music.txt")
        val now = System.currentTimeMillis()
        val isCacheValid = musicFile.exists() && (now - musicFile.lastModified()) < 24 * 60 * 60 * 1000L

        if (isCacheValid) {
            val cachedList = DataFileUtils.loadMusicList().toMutableList()
            DataFileUtils.removeInvisible(cachedList)
            applyMusicListUpdate(cachedList)
            checkLocalFiles()
            musicAdapter.notifyDataSetChanged()
            setPlayerControlVisible(true)
            seekBar.visibility = View.VISIBLE
            loadBiliHistoryToMusicList()
            return
        }

        val localList = DataFileUtils.loadMusicList().toMutableList()
        DataFileUtils.removeInvisible(localList)
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
                    val filtered = musicList.toMutableList()
                    DataFileUtils.removeInvisible(filtered)
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

    /**
     * B站音乐分区模式：先用本地缓存铺满，再在后台从第 1 页开始拉。
     *
     * 该模式下列表完全由分区数据构成，不再混入内置歌曲/播放历史；
     * 下拉到底由 [loadNextZonePage] 继续翻页追加。
     */
    private fun loadBiliZoneMusicList() {
        val cached = BiliMusicZone.loadCached()
        if (cached.isNotEmpty()) {
            zoneNextPage = BiliMusicZone.loadCachedNextPage()
            applyZoneListUpdate(cached)
            showZoneList()
        } else {
            zoneNextPage = 1
        }
        val cacheFresh = cached.isNotEmpty() && BiliMusicZone.isCacheFresh()
        if (cacheFresh) return
        val pageRef = WeakReference<SongsPage>(this)
        AppExecutors.io.execute {
            val cookie = SpUtils.getBiliCookie(this)
            val fetched = BiliMusicZone.fetchPage(1, cookie)
            if (fetched.isEmpty()) {
                if (cached.isEmpty()) {
                    handler.post {
                        val page = pageRef.get() ?: return@post
                        if (!SpUtils.isMainSourceBiliZone(page)) return@post
                        ToastUtils.show(page, LanguageUtils.getString(page, R.string.zone_fetch_fail))
                    }
                }
                return@execute
            }
            // 刷新只更新第 1 页，已翻出来的后续页要保留（否则每次启动都会掉回 10 条）
            val merged = LinkedHashMap<String, MusicBean>()
            for (bean in fetched) bean.biliKey?.let { merged[it] = bean }
            for (bean in cached) bean.biliKey?.let { merged.putIfAbsent(it, bean) }
            val mergedList = merged.values.toList()
            val nextPage = maxOf(BiliMusicZone.loadCachedNextPage(), 2)
            BiliMusicZone.saveCache(mergedList, nextPage)
            handler.post {
                val page = pageRef.get() ?: return@post
                if (!SpUtils.isMainSourceBiliZone(page)) return@post
                page.zoneEmptyStreak = 0
                page.zoneNextPage = nextPage
                page.zoneHasMore = BiliMusicZone.canFetch(nextPage)
                page.applyZoneListUpdate(mergedList)
                page.showZoneList()
            }
        }
    }

    /**
     * 下拉刷新：整体重置列表。
     *
     * 搜索同一关键词的同一页结果是稳定的，直接重置到第 1 页会刷出一模一样的内容，
     * 所以这里跳到「下一个关键词」的第 1 页，用不同关键词拿到新的一批音乐。
     */
    private fun refreshZoneFromTop() {
        if (!SpUtils.isMainSourceBiliZone(this)) {
            pullRefresh.finishRefresh(false)
            return
        }
        if (zoneRefreshing) {
            pullRefresh.finishRefresh(false)
            return
        }
        zoneRefreshing = true
        val pageRef = WeakReference<SongsPage>(this)
        val startPage = BiliMusicZone.nextRefreshStart(zoneNextPage)
        AppExecutors.io.execute {
            val cookie = SpUtils.getBiliCookie(this)
            val fetched = BiliMusicZone.fetchPage(startPage, cookie)
            handler.post {
                val page = pageRef.get() ?: return@post
                page.zoneRefreshing = false
                if (fetched.isEmpty()) {
                    page.pullRefresh.finishRefresh(false)
                    return@post
                }
                page.zoneEmptyStreak = 0
                page.zoneNextPage = startPage + 1
                page.zoneHasMore = BiliMusicZone.canFetch(startPage + 1)
                // 整体替换：旧的分页内容全部作废
                page.applyZoneListUpdate(fetched)
                page.showZoneList()
                page.lvMusic.setSelection(0)
                BiliMusicZone.saveCache(musicList, startPage + 1)
                page.pullRefresh.finishRefresh(true)
            }
        }
    }

    /** 下拉到底时追加下一页；[zoneLoading] 防重入。 */
    private fun loadNextZonePage() {
        if (!SpUtils.isMainSourceBiliZone(this)) return
        if (zoneLoading || !zoneHasMore) return
        zoneLoading = true
        val pageRef = WeakReference<SongsPage>(this)
        val pageIndex = zoneNextPage
        AppExecutors.io.execute {
            val cookie = SpUtils.getBiliCookie(this)
            val fetched = BiliMusicZone.fetchPage(pageIndex, cookie)
            handler.post {
                val page = pageRef.get() ?: return@post
                page.zoneLoading = false
                if (!SpUtils.isMainSourceBiliZone(page)) return@post
                if (fetched.isEmpty()) {
                    // 空结果可能只是这一页请求失败（风控/网络），不能直接判定「没有更多」，
                    // 否则一次失败就会让整条列表停止加载。最多容忍连续 3 次，之后才收尾。
                    page.zoneEmptyStreak++
                    if (page.zoneEmptyStreak >= 3) {
                        page.zoneHasMore = false
                    } else {
                        page.zoneNextPage = pageIndex + 1
                        page.zoneHasMore = BiliMusicZone.canFetch(pageIndex + 1)
                    }
                    return@post
                }
                page.zoneEmptyStreak = 0
                page.zoneNextPage = pageIndex + 1
                page.zoneHasMore = BiliMusicZone.canFetch(pageIndex + 1)
                page.appendZoneBeans(fetched)
                // 追加后把「已翻到第几页」落盘，重启才不会重复拉同一页
                BiliMusicZone.saveCache(musicList, pageIndex + 1)
            }
        }
    }

    private fun showZoneList() {
        musicAdapter.notifyDataSetChanged()
        setPlayerControlVisible(true)
        seekBar.visibility = View.VISIBLE
        restorePlayingHighlight()
    }

    private fun applyZoneListUpdate(newList: List<MusicBean>) {
        val anchorName = currentPlayPosition.takeIf { it in musicList.indices }
            ?.let { musicList[it].musicName }
            ?: MusicPlayerService.currentPlayingName
        val visible = DataFileUtils.filterBlocked(newList, DataFileUtils.loadBlockedWords())
        musicList.clear()
        musicList.addAll(visible)
        MusicPlayerService.musicList = musicList
        if (anchorName != null) {
            val newPos = musicList.indexOfFirst { it.musicName == anchorName }
            if (newPos != -1) {
                currentPlayPosition = newPos
                musicList.forEachIndexed { i, bean -> bean.isPlaying = (i == newPos) }
            } else {
                // 正在播放的若是内置歌曲，切到分区后列表里没有它，旧下标会指向错的条目
                currentPlayPosition = -1
                musicList.forEach { it.isPlaying = false }
            }
        }
    }

    /** 追加下一页到列表尾部（已存在的条目按 aid/bvid 去重）。 */
    private fun appendZoneBeans(beans: List<MusicBean>) {
        val existing = musicList.mapNotNullTo(HashSet()) { it.biliKey }
        val added = DataFileUtils.filterBlocked(beans, DataFileUtils.loadBlockedWords())
            .filter { key -> key.biliKey?.let { it !in existing } ?: false }
        if (added.isEmpty()) return
        musicList.addAll(added)
        MusicPlayerService.musicList = musicList
        musicAdapter.notifyDataSetChanged()
    }

    private fun applyMusicListUpdate(newList: List<MusicBean>) {
        musicList.clear()
        musicList.addAll(newList)
        MusicPlayerService.musicList = musicList
        // 分区模式下列表归分区管，搜索页的临时播放请求不能覆盖内置歌曲文件，
        // 否则切回内置列表时会读到一份被清空的缓存
        if (SpUtils.isMainSourceBiliZone(this)) return
        val pureList = musicList.filter { !it.isBilibili }
        SpUtils.saveMusicList(this, pureList)
        DataFileUtils.saveMusicList(pureList)
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
        // 分区模式下列表就是分区内容本身，混入播放历史会破坏“封面+作者”的展示预期
        if (SpUtils.isMainSourceBiliZone(this)) return
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

    private fun applyBiliBeans(biliBeans: List<MusicBean>) {
        val anchorName = currentPlayPosition.takeIf { it in musicList.indices }
            ?.let { musicList[it].musicName }
            ?: MusicPlayerService.currentPlayingName
        val visibleBili = DataFileUtils.filterBlocked(biliBeans, DataFileUtils.loadBlockedWords())
        musicList.removeAll { it.isBilibili }
        musicList.addAll(0, visibleBili)
        MusicPlayerService.musicList = musicList
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

    private fun playAtPosition(position: Int, list: List<MusicBean>, toggleIfCurrent: Boolean = false) {
        if (position !in list.indices) return
        val bean = list[position]
        val isCurrentTrack = toggleIfCurrent &&
            MusicPlayerService.currentPlayingName != null &&
            DataFileUtils.getDisplayName(bean.musicName) == MusicPlayerService.currentPlayingName
        for (b in musicList) b.isPlaying = false
        bean.isPlaying = true
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
        syncControlCover()
        setPlayerControlVisible(true)
        seekBar.visibility = View.VISIBLE
        if (isCurrentTrack) {
            sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
        } else {
            updatePlayPauseButton(true)
            resetSeekBar()
        }
    }


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
        (activity as? MainPagerActivity)?.setFloatingPlayerBarVisible(visible)
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

    private fun updateCurrentMusicInfo(
        musicName: String?,
        position: Int?,
        notifyList: Boolean = true
    ) {
        if (musicName != null) {
            val nameChanged = DataFileUtils.getDisplayName(musicName) !=
                MusicPlayerService.currentPlayingName.let { DataFileUtils.getDisplayName(it ?: "") }
            tvCurrentMusic.text = DataFileUtils.getDisplayName(musicName)
            syncControlCover()
            setPlayerControlVisible(true)
            seekBar.visibility = View.VISIBLE
            isUserSeeking = false
            if (nameChanged) {
                resetSeekBar()
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


    private fun openNowPlaying() {
        val bean = resolveCurrentBean() ?: return
        currentPlayBean = bean
        val page = ensureNowPlaying()
        page.updateTrack(bean)
        page.show()
        sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
    }

    private fun resolveCurrentBean(): MusicBean? {
        musicList.getOrNull(currentPlayPosition)?.let { return it }
        val playingName = MusicPlayerService.currentPlayingName ?: return null
        val index = musicList.indexOfFirst {
            DataFileUtils.getDisplayName(it.musicName) == playingName
        }
        return musicList.getOrNull(index)
    }

    private fun syncControlCover() {
        val bean = resolveCurrentBean()
        if (ivControlCover.tag == bean) return
        BiliCoverLoader.loadForBean(ivControlCover, bean)
    }

    private fun ensureNowPlaying(): NowPlayingPage {
        nowPlaying?.let { return it }
        val page = NowPlayingPage(activity, object : NowPlayingPage.Commands {
            override fun onPlayPause() {
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

    fun handleNowPlayingBack(): Boolean = nowPlaying?.handleBack() == true

    private fun syncNowPlaying(
        musicName: String?,
        listIndex: Int?,
        currentMs: Int?,
        durationMs: Int?,
        isPlaying: Boolean?
    ) {
        val page = nowPlaying ?: return
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
        BiliCoverLoader.loadForBean(ivControlCover, null)
        setPlayerControlVisible(true)
        seekBar.visibility = View.GONE
        resetSeekBar()
    }

    private fun syncPlayerControlState(notifyList: Boolean = true) {
        val playingName = MusicPlayerService.currentPlayingName
        val isServicePlaying = MusicPlayerService.isPlaying
        if (playingName != null) {
            currentPlayPosition = musicList.indexOfFirst {
                DataFileUtils.getDisplayName(it.musicName) == playingName
            }
            tvCurrentMusic.text = playingName
            syncControlCover()
            updatePlayPauseButton(isServicePlaying)
            musicList.forEachIndexed { i, bean -> bean.isPlaying = (i == currentPlayPosition) }
            if (notifyList) musicAdapter.notifyDataSetChanged()
            sendServiceAction(MusicPlayerService.ACTION_REQUEST_PROGRESS)
        } else {
            refreshPlayerUIForStop()
            if (notifyList) musicAdapter.notifyDataSetChanged()
        }
    }


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

    fun repositionSearchButton() {
        if (!::btnSearchFloat.isInitialized) return
        btnSearchFloat.post { applySearchButtonPosition() }
    }

    private fun applySearchButtonPosition() {
        val parent = btnSearchFloat.parent as? View ?: return
        if (parent.height <= 0 || btnSearchFloat.height <= 0) return

        val marginBottom = (16 * resources.displayMetrics.density).toInt()
        val restTop = parent.height - btnSearchFloat.height - marginBottom
        val barInset = (activity as? MainPagerActivity)?.floatingPlayerBarInset ?: 0
        val gap = (12 * resources.displayMetrics.density).toInt()
        val lift = barInset + gap

        val x = SpUtils.getSearchButtonX(this)
        val y = SpUtils.getSearchButtonY(this)
        if (x == -1f || y == -1f) {
            btnSearchFloat.translationX = 0f
            btnSearchFloat.translationY = -lift.toFloat()
            return
        }
        val maxTx = parent.width - btnSearchFloat.width - marginBottom
        val minTy = -(restTop.toFloat())
        val maxTy = -lift.toFloat()
        btnSearchFloat.translationX = x.coerceIn(-marginBottom.toFloat(), maxTx.toFloat())
        btnSearchFloat.translationY = y.coerceIn(minTy, maxTy)
    }

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
        btnSearchFloat.setTextColor(FontUtils.color(this))
        if (SpUtils.isSearchBtnTransparentStyle(this)) {
            btnSearchFloat.background = resources.getDrawable(R.drawable.search_button_adjust_bg, null)
        } else {
            originalSearchBg?.let { btnSearchFloat.background = it }
                ?: run { btnSearchFloat.background = resources.getDrawable(R.drawable.button_float_bg, null) }
        }
    }

    private fun applyTitleStyle() {
        val titleTv = findViewById<TextView>(R.id.tv_title) ?: return
        val fontColor = FontUtils.color(this)
        titleTv.text = LanguageUtils.getString(this@SongsPage, R.string.all_songs)
        btnDetail.text = LanguageUtils.getString(this@SongsPage, R.string.detail)
        btnSearchFloat.text = LanguageUtils.getString(this@SongsPage, R.string.search)
        titleTv.setTextColor(fontColor)
        titleTv.textSize = SpUtils.getFontSize(this).toFloat()
    }

    private fun stopBiliBackgroundPlayback() {
        try {
            BiliVideoPlayer.stopCurrentVideo()
        } catch (e: Exception) { AppLog.d(TAG, "停止背景视频失败: ${e.message}") }
    }


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


    private fun showMaterialDialog(builder: AlertDialog.Builder): AlertDialog =
        DialogHelper.createStyledDialog(this, builder, makeMessageBoldItalic = true, overrideListViewItemColors = true)

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
        val fontColor = FontUtils.color(this)
        val hintColor = FontUtils.dimmed(this)
        listOf(etHour, etMinute, etSecond).forEach { input ->
            input.setTextColor(fontColor)
            input.setHintTextColor(hintColor)
        }
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
                .setNeutralButton(LanguageUtils.getString(this@SongsPage, R.string.now_playing_share)) { _, _ ->
                    ShareHelper.shareMusic(this@SongsPage, bean)
                }
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
        val fontColor = FontUtils.color(this)
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
            if (MusicPlayerService.currentPlayingName == displayName) {
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

        if (MusicPlayerService.currentPlayingName == displayName) {
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


    private inner class PlayStateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MusicPlayerService.BROADCAST_PLAY_STATE) return

            if (intent.hasExtra(MusicPlayerService.EXTRA_IS_PLAYING)) {
                val playing = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false)
                updatePlayPauseButton(playing)
                if (playing) {
                    // 每秒都会收到一次带 IS_PLAYING 的广播，startUpdater 内部已做幂等保护
                    PlaybackStatsManager.startUpdater()
                } else {
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

            syncNowPlaying(
                musicName = musicName,
                listIndex = currentPlayPosition.takeIf { it != -1 },
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

            if (listDataChanged) musicAdapter.notifyDataSetChanged()

        }
    }
}
