package com.tilixibiesi.ui.page
import com.tilixibiesi.util.PlaylistDialogHelper
import com.tilixibiesi.util.ToastUtils

import com.tilixibiesi.bili.BiliHistoryHelper
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.model.BiliVideo
import com.tilixibiesi.bili.BiliVideoPlayer
import com.tilixibiesi.bili.BiliSearchHelper
import com.tilixibiesi.bili.BiliDownloadManager
import com.tilixibiesi.bili.BiliVideoGridAdapter
import com.tilixibiesi.bili.BiliSubtitleHelper
import com.tilixibiesi.bili.BiliSubtitleTrack
import com.tilixibiesi.ui.widget.DanmakuView
import com.tilixibiesi.util.DialogHelper
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.BackgroundHelper
import com.tilixibiesi.util.WindowUtils
import com.tilixibiesi.ui.adapter.MusicAdapter
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.WebViewMetricsCleaner
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.ui.BaseActivity
import com.tilixibiesi.ui.MainPagerActivity
import com.tilixibiesi.R

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import android.graphics.Bitmap

/**
 * 搜索页（原 SearchActivity）。
 *
 * 迁移要点：
 *  - 继承 [BasePage]，作为 [MainPagerActivity] 内的一页（索引 2）存在，`this` 即 Context；
 *  - 原本的 GestureDetector.onFling 侧滑切页已交由 HorizontalPager 处理，页面不再拦截触摸；
 *    向左滑由容器自动到达设置页（2 -> 3），故 onSwipeLeft/onSwipeRight/initSwipeGesture 全部删除；
 *  - overridePendingTransition 在同一 Activity 内无意义，全部删除；
 *  - 选中歌曲原本是 setResult + finish()，改为向 SongsPage 投递播放请求并切到歌曲页（已完成）；
 *  - 全屏视频播放器自带触摸处理（BiliVideoPlayer.handleTouchEvent），
 *    原先 Activity.dispatchTouchEvent 的转发在 Page 形态下不再需要，交由播放器容器自身处理。
 */
@SuppressLint("SetJavaScriptEnabled")
class SearchPage(base: Context) : BasePage(base) {

    @Suppress("unused")
    companion object {
        const val TAG = "SearchActivity"
    }

    private lateinit var etSearch: EditText
    private lateinit var lvSearchResult: ListView
    private lateinit var gvBiliResult: GridView
    private lateinit var musicAdapter: MusicAdapter
    private lateinit var biliVideoAdapter: BiliVideoGridAdapter
    private lateinit var allMusicList: MutableList<MusicBean>
    private lateinit var searchResultList: MutableList<MusicBean>
    private var isBiliMode = false
    private var isFullBiliSource = false
    private lateinit var btnBiliToggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val biliSearchRequestId = AtomicInteger(0)
    private var hasExtractedCookie = false
    private var currentKeyword = ""
    private var currentPage = 1
    private var hasMorePage = false
    private var isLoadingMore = false
    private var isCheckingCookie = false
    private var cookieValidChecked = false
    /** 上次构建搜索源时歌曲页列表的长度，用于判断是否需要重建 */
    private var lastSourceSize = -1
    private lateinit var progressBar: ProgressBar
    private lateinit var tvSearchingHint: TextView

    private lateinit var videoPlayer: BiliVideoPlayer
    private lateinit var danmakuView: DanmakuView
    /** 全屏播放器上的「弹幕开关」按钮 */
    private var btnDanmaku: ImageButton? = null
    private val biliVideoList = mutableListOf<BiliVideo>()
    private var currentVideoIndex = -1

    private lateinit var lvFooterView: View

    private lateinit var webViewContainer: FrameLayout
    /**
     * 登录 WebView。**按需创建、用完即毁**：它已从布局里移除，改在这里 new 出来。
     *
     * 常驻一个 WebView 会白白占着几十 MB（渲染进程 + GPU 纹理 + 缓存），
     * 而它一年也用不上几次——只有 Cookie 失效需要重新登录时才出现。
     */
    private var loginWebView: WebView? = null
    private lateinit var btnCloseWebView: ImageButton
    private lateinit var progressWebView: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_search)

        // 搜索源必须是「歌曲页当前实际展示的列表」：
        // 1. 歌曲页已从远端列表 + 本地文件 + B 站历史合并出真实曲目集合（SongsPage.musicList）；
        // 2. DataFileUtils.loadMusicList() 只是远端的 HTTP 快照：
        //    - 本地导入/下载的歌不在里面 → 搜不到；
        //    - 已删除/被屏蔽的歌仍在里面 → 搜出来却放不了（列表不正确）。
        // 因此一律以 SongsPage.musicList 为准，它为空时说明歌曲页尚未加载完，此时再回退本地快照。
        allMusicList = buildSourceList().toMutableList()

        searchResultList = mutableListOf()
        isFullBiliSource = SpUtils.isFullBiliSource(this)

        initView()
        initBiliToggle()
        initPageButtons()
        initFullscreenVideo()
        initWebViewLogin()
        applySettings()

        if (allMusicList.isEmpty()) {
            Toast.makeText(this, R.string.music_list_empty, Toast.LENGTH_SHORT).show()
            return
        }

        if (isFullBiliSource) {
            checkCookieValidity()
        }
    }

    /**
     * 本地搜索的数据源：一律取「歌曲页当前真实展示的列表」。
     *
     * 不能再用 DataFileUtils.loadMusicList()：那只是远端目录的 HTTP 快照，
     *   - 本地导入 / 已下载的歌不在快照里 → 明明有却搜不到；
     *   - 已删除、被屏蔽的歌仍在快照里 → 搜得到却放不了；
     *   - B 站历史里的歌也不是快照的一部分。
     * 这正是「搜索结果不正确」的根因。
     */
    private fun buildSourceList(): List<MusicBean> {
        val source = SongsPage.musicList.takeIf { it.isNotEmpty() }
            ?: DataFileUtils.loadMusicList()
        val deleted = DataFileUtils.loadDeletedMusicNames()
        val blockedWords = DataFileUtils.loadBlockedWords()
        return source.filter { bean ->
            !deleted.contains(bean.musicName) &&
                blockedWords.none { word -> bean.musicName.contains(word, ignoreCase = true) }
        }
    }

    private fun checkCookieValidity() {
        if (isCheckingCookie || cookieValidChecked) return
        val cookie = SpUtils.getBiliCookie(this)
        if (cookie.isEmpty()) return
        isCheckingCookie = true
        AppExecutors.io.execute {
            try {
                val url = URL("https://api.bilibili.com/x/web-interface/nav")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(text)
                    val retCode = json.optInt("code", -1)
                    if (retCode == -101) {
                        handler.post {
                            Toast.makeText(this@SearchPage, R.string.login_expired, Toast.LENGTH_LONG).show()
                            openBiliLoginWebView()
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                isCheckingCookie = false
                cookieValidChecked = true
            }
        }
    }

    // ---------- 生命周期 ----------
    /**
     * 暂停：保存播放进度，并释放封面位图内存。
     *
     * 释放封面的理由：宿主进入后台后用户看不到封面，但缓存仍攥着几十上百 MB 不放
     * （单 Activity 常驻 4 页 → 它不会被系统回收）。注意本方法只在「当前页」被调用
     * （见 MainPagerActivity 的派发），所以它等价于"正停在搜索页时退到后台"。
     * 释放必须放在早退判断**之前**，否则播放器未初始化时会漏掉。
     */
    override fun onPause() {
        if (::biliVideoAdapter.isInitialized) biliVideoAdapter.releaseCovers()
        // 离开本页：暂停背景视频并静音（避免多页背景音叠加）
        findViewById<View>(R.id.search_bg_host)?.let { BackgroundHelper.setActive(it, false) }
        // 应用不可见 / 离开本页时销毁登录 WebView。
        //
        // 两个目的：
        //  1. 性能：WebView 常驻会一直占着渲染进程、GPU 纹理与缓存（几十 MB），
        //     而后台清理又把 app_webview 删了，留着它只会不断把文件写回来；
        //  2. 正确：它是「用完即毁」的临时登录页，用户已经看不到了。
        // 回到前台时不需要恢复：登录态在 WebView 出现前就已提取进 SpUtils，
        // 真要再登录会走 openBiliLoginWebView() 重新创建。
        closeLoginWebViewIfVisible()
        if (!::videoPlayer.isInitialized) return
        // 弹幕帧回调必须随宿主一起停：视频通知是前台服务、进程不会被回收，
        // 不停的话页面在后台仍以 60fps 全屏重绘（纯耗电）。
        videoPlayer.onHostPause()
        val layoutFullscreen = findViewById<FrameLayout>(R.id.layout_fullscreen_video) ?: return
        if (layoutFullscreen.visibility == View.VISIBLE) {
            videoPlayer.saveCurrentProgress()
        }
    }

    /**
     * 每次进入本页都要刷新搜索源。
     *
     * 本页首次 layout 时（onCreate）歌曲页往往还没把远端列表拉回来，
     * 那时 SongsPage.musicList 是空的；若只在 onCreate 取一次，
     * 用户看到的就永远是那一瞬间的旧快照——搜到不该有的、搜不到刚加载的。
     */
    override fun onPageShow() {
        refreshSearchSource()
    }

    /**
     * 离开搜索页：释放全部封面位图。
     *
     * 此时用户已经看不到封面了，但缓存还按堆上限的 1/4 攥着几十上百 MB 不放
     * （单 Activity 常驻 4 页 → 它不会被系统回收）。主动交还，
     * 回到本页时列表重新拉取、图片重新解码即可。
     */
    override fun onPageHide() {
        if (::biliVideoAdapter.isInitialized) biliVideoAdapter.releaseCovers()
    }

    /** 歌曲页列表长度变化时重建搜索源，并让当前关键词重新出结果 */
    private fun refreshSearchSource() {
        val sourceSize = SongsPage.musicList.size
        if (sourceSize == lastSourceSize && ::allMusicList.isInitialized && sourceSize != 0) return
        lastSourceSize = sourceSize
        allMusicList = buildSourceList().toMutableList()

        val kw = etSearch.text.toString()
        if (::etSearch.isInitialized && kw.isNotEmpty()) {
            // 本地模式才需要按新数据源重算；B 站模式走网络分页，不受影响
            if (!isBiliMode) filterMusic(kw)
        }
    }

    override fun onResume() {
        refreshSearchSource()
        isFullBiliSource = SpUtils.isFullBiliSource(this)
        updateBiliButtonState()
        updateListVisibility()
        applySettings()
        // 本页成为当前页：背景视频恢复播放并出声
        findViewById<View>(R.id.search_bg_host)?.let { BackgroundHelper.setActive(it, true) }
        if (!::videoPlayer.isInitialized) return
        videoPlayer.onHostResume()
        val layoutFullscreen = findViewById<FrameLayout>(R.id.layout_fullscreen_video) ?: return
        if (layoutFullscreen.visibility == View.VISIBLE && !videoPlayer.isPlaying()) {
            if (biliVideoList.isNotEmpty() && currentVideoIndex in biliVideoList.indices) {
                videoPlayer.play(biliVideoList[currentVideoIndex])
            }
        }
    }

    override fun onDestroy() {
        BiliVideoPlayer.stopCurrentVideo()
        if (::biliVideoAdapter.isInitialized) biliVideoAdapter.shutdown()
        destroyLoginWebView()
        // 释放背景视频解码器（VideoView 脱离视图树不会自动 release）
        findViewById<View>(R.id.search_bg_host)?.let { BackgroundHelper.release(it) }
    }

    override fun onBackPressed(): Boolean {
        if (findViewById<FrameLayout>(R.id.layout_webview_login)?.visibility == View.VISIBLE) {
            closeLoginWebView()
            return true
        }
        if (findViewById<FrameLayout>(R.id.layout_fullscreen_video)?.visibility == View.VISIBLE &&
            ::videoPlayer.isInitialized
        ) {
            videoPlayer.close()
            currentVideoIndex = -1
            return true
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (!::videoPlayer.isInitialized) return
        videoPlayer.onConfigurationChanged(newConfig)
    }

    /** 全屏播放器是否正在显示：供宿主把它加入拖拽黑名单，避免手势冲突 */
    val isVideoFullscreenActive: Boolean
        get() = ::videoPlayer.isInitialized && videoPlayer.isFullscreenActive

    private fun setFullScreen() {
        WindowUtils.setFullScreen(activity)
    }

    private fun restoreSystemUI() {
        WindowUtils.restoreSystemUI(activity)
    }

    /** 选中歌曲：把播放请求投递给歌曲页并切过去 */
    private fun deliverPlayRequest(position: Int, list: List<MusicBean>) {
        SongsPage.pendingPlay = position to ArrayList(list)
        gotoPage(MainPagerActivity.PAGE_SONGS, true)
    }

    private fun initView() {
        etSearch = findViewById(R.id.et_search)!!
        lvSearchResult = findViewById(R.id.lv_search_result)!!
        gvBiliResult = findViewById(R.id.gv_bili_result)!!
        progressBar = findViewById(R.id.progress_bar)!!
        tvSearchingHint = findViewById(R.id.tv_searching_hint)!!
        val btnSort = findViewById<Button>(R.id.btn_sort)
        btnSort?.visibility = View.GONE

        val btnClearSearch = findViewById<ImageButton>(R.id.btn_clear_search)
        btnClearSearch?.setOnClickListener { etSearch.text.clear() }

        lvFooterView = layoutInflater.inflate(R.layout.footer_loading, lvSearchResult, false).apply {
            visibility = View.GONE
        }
        lvSearchResult.addFooterView(lvFooterView, null, false)

        musicAdapter = MusicAdapter(this, searchResultList).apply {
            // MusicBean.equals 仅按歌名判等，B 站条目与本地同名歌曲会互相命中，
            // 导致序号错乱。此处按引用定位（列表内元素唯一）。
            originalIndexProvider = { bean -> allMusicList.indexOfFirst { it === bean } }
        }
        lvSearchResult.adapter = musicAdapter

        biliVideoAdapter = BiliVideoGridAdapter(this)
        gvBiliResult.adapter = biliVideoAdapter

        gvBiliResult.setOnItemClickListener { _, _, position, _ ->
            if (position < biliVideoList.size && ::videoPlayer.isInitialized) {
                currentVideoIndex = position
                videoPlayer.play(biliVideoList[position])
            }
        }
        gvBiliResult.setOnItemLongClickListener { _, _, position, _ ->
            if (position < biliVideoList.size) {
                showDownloadQualityDialog(biliVideoList[position])
                true
            } else false
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterMusic(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        lvSearchResult.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position < 0 || position >= searchResultList.size) return@OnItemClickListener
            val bean = searchResultList[position]
            if (bean.isBilibili) playBiliAudio(bean)
            else {
                // 按引用定位：B 站历史条目可能与本地歌曲同名，
                // 按歌名 indexOf 会错点到空的 B 站条目上（播放失败）。
                val pos = allMusicList.indexOfFirst { it === bean }
                if (pos == -1) return@OnItemClickListener
                deliverPlayRequest(pos, allMusicList)
            }
        }
        musicAdapter.onAddToPlaylistClickListener = object : MusicAdapter.OnAddToPlaylistClickListener {
            override fun onAddToPlaylistClick(position: Int, musicBean: MusicBean) {
                PlaylistDialogHelper.showAddToPlaylistDialog(this@SearchPage, musicBean)
            }
        }
        musicAdapter.onAddToHistoryClickListener = object : MusicAdapter.OnAddToBiliHistoryClickListener {
            override fun onAddToHistoryClick(position: Int, musicBean: MusicBean) {
                showAddToHistoryDialog(musicBean)
            }
        }
    }

    private fun initWebViewLogin() {
        webViewContainer = findViewById(R.id.layout_webview_login)!!
        btnCloseWebView = findViewById(R.id.btn_close_webview)!!
        progressWebView = findViewById(R.id.progress_webview)!!
        btnCloseWebView.setOnClickListener {
            tryExtractCookie()
            closeLoginWebView()
        }
    }

    /** 按需创建登录 WebView 并塞进容器（布局里已不再常驻它） */
    private fun createLoginWebView(): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 Edg/120.0.0.0"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressWebView.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progressWebView.visibility = View.GONE
                tryExtractCookie()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                progressWebView.visibility = View.GONE
                Toast.makeText(this@SearchPage, R.string.webpage_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressWebView.progress = newProgress
                if (newProgress == 100) progressWebView.visibility = View.GONE
            }
        }
        // 放在最底层（index 0），关闭按钮与进度条才在它之上
        webViewContainer.addView(wv, 0)
        loginWebView = wv
        return wv
    }

    /**
     * 彻底销毁登录 WebView 并从容器移除。
     *
     * 只 removeView 是不够的：WebView 内部持有渲染进程与 GPU 资源，
     * 必须 destroy() 才会释放；而 destroy() 前必须先从视图树摘掉。
     */
    private fun destroyLoginWebView() {
        val wv = loginWebView ?: return
        loginWebView = null
        runCatching {
            wv.stopLoading()
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = null
            (wv.parent as? FrameLayout)?.removeView(wv)
            wv.destroy()
        }
    }

    private fun tryExtractCookie() {
        if (hasExtractedCookie) return
        val cookies = CookieManager.getInstance().getCookie("https://bilibili.com") ?: ""
        if (cookies.isEmpty()) return
        val sessdata = extractCookieValue(cookies, "SESSDATA")
        val biliJct = extractCookieValue(cookies, "bili_jct")
        if (!sessdata.isNullOrEmpty() && !biliJct.isNullOrEmpty()) {
            hasExtractedCookie = true
            val dedeUserID = extractCookieValue(cookies, "DedeUserID")
            val fullCookie = "SESSDATA=$sessdata; bili_jct=$biliJct; DedeUserID=${dedeUserID ?: ""}; " +
                    "DedeUserID__ckMd5=${extractCookieValue(cookies, "DedeUserID__ckMd5") ?: ""}; " +
                    "sid=${extractCookieValue(cookies, "sid") ?: ""}"
            SpUtils.saveBiliCookie(this, fullCookie.trimEnd(';', ' '))
            SpUtils.setFullBiliSource(this, true)
            isFullBiliSource = true
            updateBiliButtonState()
            updateListVisibility()
            Toast.makeText(this, R.string.login_success_full, Toast.LENGTH_SHORT).show()
            handler.postDelayed({ closeLoginWebView() }, 500)
        }
    }

    private fun initFullscreenVideo() {
        val layout = findViewById<FrameLayout>(R.id.layout_fullscreen_video)!!
        val vv = findViewById<TextureView>(R.id.vv_fullscreen)!!

        val closeBtn = findViewById<ImageButton>(R.id.btn_close_video)!!
        val titleTv = findViewById<TextView>(R.id.tv_video_title)!!
        val playPauseBtn = findViewById<ImageButton>(R.id.btn_video_play_pause)!!
        val seekBar = findViewById<SeekBar>(R.id.sb_video_progress)!!
        val timeTv = findViewById<TextView>(R.id.tv_video_time)!!

        val btnAddToHistory = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.VISIBLE
            setColorFilter(Color.parseColor("#2196F3"))
            setOnClickListener {
                if (currentVideoIndex in biliVideoList.indices) {
                    val video = biliVideoList[currentVideoIndex]
                    DialogHelper.createStyledDialog(activity,
                        AlertDialog.Builder(this@SearchPage)
                            .setTitle(R.string.add_to_history_title)
                            .setMessage(LanguageUtils.getString(this@SearchPage, R.string.add_to_history_message, video.title))
                            .setPositiveButton(R.string.ok) { _, _ ->
                                recordBiliEntry(video)
                                Toast.makeText(this@SearchPage, R.string.history_added, Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(R.string.cancel, null)
                    )
                }
            }
        }
        val addBtnParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            val rotationBtnWidth = (48 * resources.displayMetrics.density).toInt()
            setMargins(16, 16, rotationBtnWidth + 16, 16)
        }
        layout.addView(btnAddToHistory, addBtnParams)

        val orientationBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_crop)
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.VISIBLE
        }
        val oriParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(16, 16, 16, 16) }
        layout.addView(orientationBtn, oriParams)

        // 弹幕层：插在视频之上、控制栏之下（XML 里紧跟 TextureView）
        danmakuView = findViewById<DanmakuView>(R.id.danmaku_view)!!

        // 弹幕开关按钮：与「添加到历史」同排（右上角：加号 → 弹幕 → 旋转）。
        // 交给 BiliVideoPlayer 托管点击与状态，这样才能跟随控制栏一起显隐
        // （showAllControls/hideAllControls 由播放器内部驱动）。
        val danmakuBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.stat_notify_chat)
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.VISIBLE
        }
        btnDanmaku = danmakuBtn
        val danmakuParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            val btnWidth = (48 * resources.displayMetrics.density).toInt()
            setMargins(16, 16, btnWidth * 2 + 16, 16)
        }
        layout.addView(danmakuBtn, danmakuParams)
        updateDanmakuButton()

        // BiliVideoPlayer 的构造签名要求 BaseActivity（内部用 requestedOrientation / window）。
        // 宿主若不是 BaseActivity（例如纯 Activity），这里退化为不初始化全屏播放器，
        // 所有使用点都以 ::videoPlayer.isInitialized 守卫，避免崩溃。
        val hostActivity = activity as? BaseActivity ?: return
        videoPlayer = BiliVideoPlayer(hostActivity).apply {
            callback = object : BiliVideoPlayer.Callback {
                override fun onFullscreenOpened() {}
                override fun onFullscreenClosed() {}
                override fun setSystemUIForFullscreen() = setFullScreen()
                override fun restoreSystemUI() = this@SearchPage.restoreSystemUI()
                override fun setFullScreen() = this@SearchPage.setFullScreen()
                override fun onRequestSwitchNext() {
                    if (currentVideoIndex < biliVideoList.size - 1) {
                        currentVideoIndex++
                        videoPlayer.play(biliVideoList[currentVideoIndex])
                    }
                }
                override fun onRequestSwitchPrevious() {
                    if (currentVideoIndex > 0) {
                        currentVideoIndex--
                        videoPlayer.play(biliVideoList[currentVideoIndex])
                    }
                }
                override fun onRecordHistory(video: BiliVideo) { /* 不自动记录 */ }
                override fun onDanmakuToggled(enabled: Boolean) = this@SearchPage.onDanmakuToggled(enabled)
                override fun onRequestNeighbor(direction: Int): BiliVideo? {
                    // -1 = 上一条（手指下滑），+1 = 下一条（手指上滑）
                    val target = currentVideoIndex + direction
                    return biliVideoList.getOrNull(target)
                }
            }
            initialize(
                layout, vv, closeBtn, titleTv, playPauseBtn, seekBar, timeTv, orientationBtn,
                btnAddToHistory, danmakuView, danmakuBtn,
                findViewById(R.id.layout_switch_preview),
                findViewById(R.id.iv_switch_cover),
                findViewById(R.id.tv_switch_title),
                findViewById(R.id.tv_switch_author),
                findViewById(R.id.layout_switch_info)
            )
        }
    }

    /**
     * 弹幕开关：与「添加到历史」按钮同排，点击切换是否显示弹幕。
     *
     * 状态存在 SpUtils（跨视频、跨会话保留），播放器内部也据此决定是否拉取弹幕，
     * 因此关掉弹幕后再打开视频不会白白发一次网络请求。
     */
    /** 播放器内部点击后回调不到页面，这里只负责同步图标配色与提示 */
    fun onDanmakuToggled(enabled: Boolean) {
        updateDanmakuButton()
        Toast.makeText(
            this@SearchPage,
            LanguageUtils.getString(
                this@SearchPage,
                if (enabled) R.string.danmaku_toggle_on else R.string.danmaku_toggle_off
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 按当前开关状态刷新按钮配色（开=蓝色，关=白色）。
     *
     * 读的是 SpUtils 而非 player.isDanmakuEnabled()：本方法在播放器实例
     * 创建之前就会被调用一次（按钮先 addView），而 player 内部的
     * danmakuEnabled 初值同样取自 SpUtils，两者始终一致。
     */
    private fun updateDanmakuButton() {
        val on = SpUtils.isDanmakuEnabled(this)
        btnDanmaku?.setColorFilter(
            if (on) Color.parseColor("#2196F3") else Color.WHITE
        )
    }

    private fun initBiliToggle() {
        btnBiliToggle = findViewById(R.id.btn_bili_toggle)!!
        updateBiliButtonState()
        btnBiliToggle.setOnClickListener {
            isBiliMode = !isBiliMode
            updateBiliButtonState()
            updateListVisibility()
            val kw = etSearch.text.toString()
            if (kw.isNotEmpty()) filterMusic(kw)
            else {
                searchResultList.clear()
                biliVideoList.clear()
                refreshAdapters()
                hideSearchingHint()
                resetPagination()
            }
        }
        btnBiliToggle.setOnLongClickListener {
            showFullBiliSourceDialog()
            true
        }
    }

    private fun updateBiliButtonState() {
        val suffix = if (isFullBiliSource) LanguageUtils.getString(this@SearchPage, R.string.bili_full_suffix) else ""
        btnBiliToggle.text = if (isBiliMode) {
            LanguageUtils.getString(this@SearchPage, R.string.bili_toggle_on_template, suffix)
        } else {
            LanguageUtils.getString(this@SearchPage, R.string.bili_toggle_off_template, suffix)
        }
    }

    private fun updateListVisibility() {
        if (isBiliMode && isFullBiliSource) {
            lvSearchResult.visibility = View.GONE
            gvBiliResult.visibility = View.VISIBLE
        } else {
            lvSearchResult.visibility = View.VISIBLE
            gvBiliResult.visibility = View.GONE
        }
    }

    private fun showFullBiliSourceDialog() {
        val current = isFullBiliSource
        if (current) {
            DialogHelper.createStyledDialog(activity,
                AlertDialog.Builder(this)
                    .setTitle(R.string.full_bili_source_title)
                    .setMessage(R.string.full_bili_source_enabled_msg)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        SpUtils.setFullBiliSource(this, false)
                        isFullBiliSource = false
                        updateBiliButtonState()
                        updateListVisibility()
                        Toast.makeText(this, R.string.full_bili_source_disabled_toast, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
            )
        } else {
            val savedCookie = SpUtils.getBiliCookie(this)
            if (savedCookie.isNotEmpty()) {
                SpUtils.setFullBiliSource(this, true)
                isFullBiliSource = true
                updateBiliButtonState()
                updateListVisibility()
                Toast.makeText(this, R.string.full_bili_source_enabled_toast, Toast.LENGTH_SHORT).show()
            } else {
                openBiliLoginWebView()
            }
        }
    }

    private fun openBiliLoginWebView() {
        cookieValidChecked = false
        hasExtractedCookie = false
        CookieManager.getInstance().removeAllCookies(null)
        // 只有真正要登录时才创建：这是它存在的唯一理由
        val wv = loginWebView ?: createLoginWebView()
        wv.loadUrl("https://passport.bilibili.com/login")
        webViewContainer.visibility = View.VISIBLE
        WindowUtils.setFullScreen(activity)
    }

    private fun closeLoginWebView() {
        webViewContainer.visibility = View.GONE
        // 用完立即销毁：WebView 常驻会一直占着几十 MB
        destroyLoginWebView()
        clearWebViewCacheAsync()
        setFullScreen()
    }

    /**
     * 仅当登录页正在显示时才销毁它（离开本页 / 退到后台时调用）。
     *
     * 与 [closeLoginWebView] 的区别：这里不动 fullscreen 与页面可见性——
     * 退到后台时改这些会与窗口状态打架，而 Activity 本来就已经不可见了。
     */
    private fun closeLoginWebViewIfVisible() {
        if (loginWebView == null) return
        destroyLoginWebView()
        clearWebViewCacheAsync()
    }

    /** 清理 WebView 落盘的缓存文件（销毁之后异步跑，不影响界面） */
    private fun clearWebViewCacheAsync() {
        AppExecutors.io.execute {
            // 前台只在页面销毁时清：这里用定点版而非激进版 purge()，
            // 因为应用可能仍在前台运行（关闭登录页），激进版会连
            // code_cache / app_textures / databases 一起清，打断正在运行的自身。
            // 应用整体退到后台后，MyApplication 会另行触发激进清理。
            WebViewMetricsCleaner.purgeWebViewArtifacts(this)
            // 登录页用完即毁，顺带清掉 WebView 的 Cookie（这里清的是 WebView 自己的
            // Cookie 库；应用登录态保存在 MusicPlayerPrefs，不受影响）
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    private fun initPageButtons() {
        lvSearchResult.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (!isBiliMode || isLoadingMore || currentKeyword.isEmpty() || !hasMorePage) return
                if (firstVisibleItem + visibleItemCount >= totalItemCount - 1) {
                    loadPage(currentPage + 1)
                }
            }
        })

        gvBiliResult.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (!isBiliMode || isLoadingMore || currentKeyword.isEmpty() || !hasMorePage) return
                if (firstVisibleItem + visibleItemCount >= totalItemCount - 1) {
                    loadPage(currentPage + 1)
                }
            }
        })
    }

    private fun filterMusic(keyword: String) {
        if (keyword.isEmpty()) {
            searchResultList.clear()
            biliVideoList.clear()
            refreshAdapters()
            hideSearchingHint()
            resetPagination()
            return
        }
        if (!isBiliMode) {
            hideSearchingHint()
            resetPagination()
            searchResultList.clear()

            val normalizedKeyword = normalizeForSearch(keyword)
            if (normalizedKeyword.isEmpty()) {
                musicAdapter.notifyDataSetChanged()
                return
            }
            val keywordChars = normalizedKeyword.toSet()

            // 临时存储 (MusicBean, 命中字符数)
            val scoredList = mutableListOf<Pair<MusicBean, Int>>()

            for (b in allMusicList) {
                val normalizedName = normalizeForSearch(DataFileUtils.getDisplayName(b.musicName))
                val nameChars = normalizedName.toSet()
                // 计算交集字符数（关键词中有多少字符出现在歌名中）
                val score = keywordChars.count { it in nameChars }
                if (score > 0) {
                    scoredList.add(b to score)
                }
            }

            // 按得分降序，得分相同的保持原有相对顺序（稳定排序）
            scoredList.sortByDescending { it.second }
            searchResultList.addAll(scoredList.map { it.first })

            musicAdapter.notifyDataSetChanged()
            musicAdapter.showAddToHistoryButton = false
        } else {
            resetPagination()
            currentKeyword = keyword
            loadPage(1)
        }
    }

    private fun normalizeForSearch(input: String): String {
        return input.lowercase()
            .replace(Regex("\\.(mp3|flac|wav|aac|ogg|wma|m4a|opus)$"), "")
    }

    private fun loadPage(page: Int) {
        if (isLoadingMore) return
        isLoadingMore = true
        progressBar.visibility = View.VISIBLE
        tvSearchingHint.visibility = View.VISIBLE

        if (!isFullBiliSource || !isBiliMode) {
            lvFooterView.visibility = View.VISIBLE
        }

        val id = biliSearchRequestId.incrementAndGet()
        AppExecutors.io.execute {
            val cookie = SpUtils.getBiliCookie(this)
            val result = BiliSearchHelper.searchVideosPage(currentKeyword, page, cookie)
            if (id != biliSearchRequestId.get()) {
                handler.post {
                    isLoadingMore = false
                    progressBar.visibility = View.GONE
                    tvSearchingHint.visibility = View.GONE
                    lvFooterView.visibility = View.GONE
                }
                return@execute
            }
            handler.post {
                if (id != biliSearchRequestId.get()) return@post
                hideSearchingHint()

                // 精准判断 Cookie 失效（-101），其他错误静默处理
                if (result.errorCode == -101) {
                    Toast.makeText(this@SearchPage, R.string.cookie_invalid_load, Toast.LENGTH_LONG).show()
                    openBiliLoginWebView()
                    isLoadingMore = false
                    return@post
                }

                val videos = result.videos
                val hasMore = result.hasMore

                // 若首页返回空且无更多页（非 -101 导致的空），可能是搜索无结果，正常显示空列表即可
                if (page == 1) {
                    searchResultList.clear()
                    biliVideoList.clear()
                }

                biliVideoList.addAll(videos)

                if (isFullBiliSource && isBiliMode) {
                    if (page == 1) {
                        biliVideoAdapter.dataList = biliVideoList
                    } else {
                        biliVideoAdapter.addData(videos)
                    }
                    biliVideoAdapter.hasMore = hasMore
                    biliVideoAdapter.notifyDataSetChanged()
                } else {
                    for (v in videos) {
                        searchResultList.add(
                            MusicBean(v.title, "").apply {
                                isBilibili = true
                                bvid = v.bvid
                                author = v.author
                                duration = v.duration.toIntOrNull() ?: 0
                            }
                        )
                    }
                    musicAdapter.notifyDataSetChanged()
                    if (page == 1) lvSearchResult.setSelection(0)
                }

                lvFooterView.visibility = if (!isBiliMode || !isFullBiliSource) {
                    if (hasMore && isBiliMode) View.VISIBLE else View.GONE
                } else {
                    View.GONE
                }

                currentPage = page
                hasMorePage = hasMore
                isLoadingMore = false
            }
        }
    }

    private fun hideSearchingHint() {
        progressBar.visibility = View.GONE
        tvSearchingHint.visibility = View.GONE
    }

    private fun refreshAdapters() {
        musicAdapter.notifyDataSetChanged()
        biliVideoAdapter.clearData()
        biliVideoAdapter.hasMore = false
        biliVideoAdapter.notifyDataSetChanged()
    }

    private fun resetPagination() {
        currentPage = 1
        hasMorePage = false
        isLoadingMore = false
        lvFooterView.visibility = View.GONE
        biliVideoAdapter.hasMore = false
        biliVideoAdapter.notifyDataSetChanged()
    }

    private fun playBiliAudio(bean: MusicBean) {
        // 不再预先获取链接，直接传回主活动，由服务并发获取
        val existingIndex = allMusicList.indexOfFirst { it === bean }
        if (existingIndex != -1) allMusicList[existingIndex] = bean
        else allMusicList.add(bean)
        val playPos = allMusicList.indexOfFirst { it === bean }
        deliverPlayRequest(playPos, allMusicList)
    }

    private fun showDownloadQualityDialog(video: BiliVideo) {
        val dialog = DialogHelper.createLoadingDialog(this, LanguageUtils.getString(this@SearchPage, R.string.loading_fetch_quality))
        dialog.show()
        AppExecutors.io.execute {
            val cookie = SpUtils.getBiliCookie(this)
            val detail = BiliSearchHelper.getVideoDetail(video.bvid, cookie) ?: run {
                handler.post { dialog.dismiss(); ToastUtils.show(this@SearchPage, LanguageUtils.getString(this@SearchPage, R.string.video_info_fail)) }
                return@execute
            }
            // 画质列表与字幕轨道互不依赖，并行拉取：
            // 字幕需要多次采样合并（详见 BiliSubtitleHelper），耗时明显，
            // 串行会让弹窗多等好几秒。失败视为"无字幕"，不阻断下载。
            //
            // 注意：这里是「池线程提交子任务后阻塞等待」的 fan-out。
            // AppExecutors 用 CallerRunsPolicy，池满时子任务由本线程直接执行，
            // 因此 latch 一定能倒数到 0，不会因线程耗尽而永久等待。
            val latch = CountDownLatch(2)
            var urls: BiliSearchHelper.PlayUrlResult? = null
            var subtitleTracks: List<BiliSubtitleTrack> = emptyList()
            AppExecutors.io.execute {
                try {
                    urls = BiliSearchHelper.getPlayUrls(detail.bvid, detail.cid, cookie)
                } finally { latch.countDown() }
            }
            AppExecutors.io.execute {
                try {
                    // 内部先取稳定的语种清单确定"有几种字幕"，再并发采样补每种的下载 URL。
                    // 语种数量恒定不变，不会出现"时多时少 / 有时显示无字幕"。
                    subtitleTracks = BiliSubtitleHelper.fetchTracks(detail.bvid, detail.cid, cookie)
                } finally { latch.countDown() }
            }
            try { latch.await() } catch (_: InterruptedException) { }
            // 用局部 val 接住：闭包里写过的 var 无法 smart cast 成非空
            val playUrls = urls ?: run {
                handler.post { dialog.dismiss(); ToastUtils.show(this@SearchPage, LanguageUtils.getString(this@SearchPage, R.string.quality_fetch_fail)) }
                return@execute
            }
            handler.post {
                dialog.dismiss()
                data class QualityTag(val isVideo: Boolean, val index: Int, val description: String, val isSubtitle: Boolean = false)
                val videoQualities = playUrls.videoQualities
                val audioQualities = playUrls.audioQualities
                val items = mutableListOf<Pair<String, QualityTag>>()
                videoQualities.forEachIndexed { i, q -> items.add(LanguageUtils.getString(this@SearchPage, R.string.video_quality_format, q.description) to QualityTag(true, i, q.description)) }
                if (audioQualities.isNotEmpty()) {
                    items.add(LanguageUtils.getString(this@SearchPage, R.string.separator_audio) to QualityTag(false, -1, ""))
                    audioQualities.forEachIndexed { i, q -> items.add(LanguageUtils.getString(this@SearchPage, R.string.audio_quality_format, q.description) to QualityTag(false, i, q.description)) }
                }
                // 字幕：仅在视频确实存在字幕轨道时可选，否则给一条禁用占位项说明情况
                items.add(LanguageUtils.getString(this@SearchPage, R.string.subtitle_separator) to QualityTag(false, -1, ""))
                if (subtitleTracks.isEmpty()) {
                    items.add(LanguageUtils.getString(this@SearchPage, R.string.subtitle_none) to QualityTag(false, -1, "", isSubtitle = true))
                } else {
                    subtitleTracks.forEachIndexed { i, t ->
                        items.add(
                            LanguageUtils.getString(this@SearchPage, R.string.subtitle_option, t.displayName)
                                to QualityTag(false, i, "", isSubtitle = true)
                        )
                    }
                }
                var selectedVideoIndex = -1
                var selectedAudioIndex = -1
                // 字幕支持多选：一个视频往往同时有中文/英文/AI 等多种轨道，
                // 单选意味着想下全部语言得反复开关弹窗。
                val selectedSubtitleIndices = mutableSetOf<Int>()
                val checkBoxes = mutableListOf<CheckBox>()
                fun createListener(cb: CheckBox, tag: QualityTag): CompoundButton.OnCheckedChangeListener {
                    return CompoundButton.OnCheckedChangeListener { _, isChecked ->
                        if (!isChecked) {
                            when {
                                tag.isVideo -> selectedVideoIndex = -1
                                tag.isSubtitle -> selectedSubtitleIndices.remove(tag.index)
                                tag.index >= 0 -> selectedAudioIndex = -1
                            }
                            return@OnCheckedChangeListener
                        }
                        // 字幕与音频/视频互不冲突，且字幕之间也互不冲突：
                        // 可以「视频 + 音频 + 若干种字幕」一次性全部下载。
                        if (tag.isSubtitle) {
                            selectedSubtitleIndices.add(tag.index)
                            return@OnCheckedChangeListener
                        }
                        if (tag.isVideo) {
                            if (selectedVideoIndex != -1) {
                                for (prevCb in checkBoxes) {
                                    val prevTag = prevCb.tag as? QualityTag ?: continue
                                    if (prevTag.isVideo && prevTag.index == selectedVideoIndex) {
                                        prevCb.setOnCheckedChangeListener(null); prevCb.isChecked = false
                                        prevCb.setOnCheckedChangeListener(createListener(prevCb, prevTag)); break
                                    }
                                }
                            }
                            selectedVideoIndex = tag.index
                        } else if (tag.index >= 0) {
                            if (selectedAudioIndex != -1) {
                                for (prevCb in checkBoxes) {
                                    val prevTag = prevCb.tag as? QualityTag ?: continue
                                    if (!prevTag.isVideo && prevTag.index == selectedAudioIndex) {
                                        prevCb.setOnCheckedChangeListener(null); prevCb.isChecked = false
                                        prevCb.setOnCheckedChangeListener(createListener(prevCb, prevTag)); break
                                    }
                                }
                            }
                            selectedAudioIndex = tag.index
                        }
                    }
                }
                val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }
                // 应用设置中的字体颜色（参考其他对话框的做法）
                val fontColor = try {
                    val dialogFont = SpUtils.getDialogFontColor(this)
                    if (dialogFont.isEmpty()) Color.parseColor(SpUtils.getFontColor(this))
                    else Color.parseColor(dialogFont)
                } catch (_: Exception) {
                    Color.WHITE
                }
                for ((label, tag) in items) {
                    val cb = CheckBox(this).apply {
                        text = label; setTextColor(fontColor); isEnabled = tag.index != -1; this.tag = tag
                        setOnCheckedChangeListener(createListener(this, tag))
                    }
                    checkBoxes.add(cb); dialogView.addView(cb)
                }
                val scrollView = ScrollView(this).apply { addView(dialogView) }
                DialogHelper.createStyledDialog(activity,
                    AlertDialog.Builder(this)
                        .setTitle(R.string.quality_select_title)
                        .setView(scrollView)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            // 字幕独立于画质选择：可与视频/音频任意组合，也可单独下载。
                            // 因此先处理字幕，再走原来的画质分支，两者互不影响。
                            // 字幕可多选：勾选的每种语言各存一个 SRT（文件名带语言后缀）。
                            // 走单个后台任务串行下载，而不是每种语言各起一条线程：
                            //  - 避免同时写同一目录造成的竞争；
                            //  - 避免连弹 N 个 Toast，最后只汇总一条结果。
                            if (selectedSubtitleIndices.isNotEmpty()) {
                                downloadSubtitles(detail.title, subtitleTracks, selectedSubtitleIndices.sorted(), cookie)
                            }
                            when {
                                selectedVideoIndex != -1 && selectedAudioIndex != -1 -> {
                                    val vq = videoQualities[selectedVideoIndex]; val aq = audioQualities[selectedAudioIndex]
                                    if (vq.videoUrl.isNullOrEmpty() || aq.audioUrl.isNullOrEmpty()) { ToastUtils.show(this@SearchPage, LanguageUtils.getString(this@SearchPage, R.string.url_not_available)); return@setPositiveButton }
                                    BiliDownloadManager.downloadVideoWithQuality(this, detail.title, vq.videoUrl, aq.audioUrl, "${vq.description}+${aq.description}")
                                }
                                selectedVideoIndex != -1 -> {
                                    val vq = videoQualities[selectedVideoIndex]
                                    if (vq.videoUrl.isNullOrEmpty()) { ToastUtils.show(this@SearchPage, LanguageUtils.getString(this@SearchPage, R.string.video_url_not_available)); return@setPositiveButton }
                                    BiliDownloadManager.downloadVideoWithQuality(this, detail.title, vq.videoUrl, "", vq.description)
                                }
                                selectedAudioIndex != -1 -> {
                                    val aq = audioQualities[selectedAudioIndex]
                                    if (aq.audioUrl.isNullOrEmpty()) { ToastUtils.show(this@SearchPage, LanguageUtils.getString(this@SearchPage, R.string.audio_url_not_available)); return@setPositiveButton }
                                    BiliDownloadManager.downloadAudioOnly(this, detail.title, aq.audioUrl, aq.description)
                                }
                                // 只勾了字幕（没选画质）也是合法操作
                                selectedSubtitleIndices.isEmpty() ->
                                    ToastUtils.show(this@SearchPage, LanguageUtils.getString(this@SearchPage, R.string.select_at_least_one_quality))
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                )
            }
        }
    }

    /**
     * 下载字幕（SRT）到 `subtitle/` 目录。
     *
     * 走后台线程：内部要拉字幕 JSON 再转 SRT，都不该阻塞 UI。
     */
    /**
     * 串行下载勾选的每种字幕，完成后只弹一条汇总提示。
     *
     * 多线程并发下载在这里没有收益（字幕只有几十 KB，瓶颈是往返延迟），
     * 反而会带来目录写竞争和 N 个连续 Toast 刷屏。
     */
    private fun downloadSubtitles(
        title: String,
        tracks: List<BiliSubtitleTrack>,
        indices: List<Int>,
        cookie: String
    ) {
        AppExecutors.io.execute {
            val saved = mutableListOf<String>()
            var failed = 0
            for (idx in indices) {
                val track = tracks.getOrNull(idx) ?: continue
                val file = BiliDownloadManager.downloadSubtitle(this, title, track, cookie, idx)
                if (file != null) saved.add(file.name) else failed++
            }
            handler.post {
                when {
                    saved.isEmpty() ->
                        ToastUtils.show(this@SearchPage, LanguageUtils.getString(this@SearchPage, R.string.subtitle_failed))
                    saved.size == 1 ->
                        ToastUtils.show(this@SearchPage, 
                            LanguageUtils.getString(this@SearchPage, R.string.subtitle_complete, saved[0])
                        )
                    else -> ToastUtils.show(this@SearchPage, 
                        LanguageUtils.getString(
                            this@SearchPage,
                            R.string.subtitle_complete_multi,
                            saved.size,
                            failed
                        )
                    )
                }
            }
        }
    }

    private fun showAddToHistoryDialog(bean: MusicBean) {
        DialogHelper.createStyledDialog(activity,
            AlertDialog.Builder(this)
                .setTitle(R.string.add_to_history_title)
                .setMessage(LanguageUtils.getString(this@SearchPage, R.string.add_to_history_message, DataFileUtils.getDisplayName(bean.musicName)))
                .setPositiveButton(R.string.ok) { _, _ ->
                    recordBiliEntryFromBean(bean)
                    Toast.makeText(this, R.string.history_added, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    private fun recordBiliEntry(video: BiliVideo) {
        AppExecutors.io.execute {
            BiliHistoryHelper.addEntry(video)
        }
    }

    private fun recordBiliEntryFromBean(bean: MusicBean) {
        bean.bvid?.let { bvid ->
            val video = BiliVideo(
                title = bean.musicName, author = bean.author ?: "", bvid = bvid,
                coverUrl = "", duration = bean.duration.toString()
            )
            recordBiliEntry(video)
        }
    }

    private fun applySettings() {
        // 背景宿主是外层 FrameLayout：内容根 layout_search_root 保持透明，
        // 背景层（index 0）才透得出来，且与内容层叠而非挤占空间。
        val bgHost = findViewById<View>(R.id.search_bg_host) ?: return
        BackgroundHelper.applyBackground(this, bgHost, SpUtils.getBackgroundAlpha(this))
        try {
            etSearch.setTextColor(Color.parseColor(SpUtils.getFontColor(this)))
        } catch (_: Exception) { etSearch.setTextColor(0xFFFFFFFF.toInt()) }
        etSearch.textSize = SpUtils.getFontSize(this).toFloat()
    }

    private fun extractCookieValue(cookieString: String, key: String): String? {
        return Regex("$key=([^;]*)").find(cookieString)?.groupValues?.get(1)?.trim()
    }
}
