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
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
    private var submittedKeyword = ""
    private var currentPage = 1
    private var hasMorePage = false
    private var isLoadingMore = false
    private var isCheckingCookie = false
    private var cookieValidChecked = false
    private var lastSourceSize = -1
    private lateinit var progressBar: ProgressBar
    private lateinit var tvSearchingHint: TextView

    private lateinit var videoPlayer: BiliVideoPlayer
    private lateinit var danmakuView: DanmakuView
    private var btnDanmaku: ImageButton? = null
    private val biliVideoList = mutableListOf<BiliVideo>()
    private var currentVideoIndex = -1

    private lateinit var webViewContainer: FrameLayout
    private var loginWebView: WebView? = null
    private lateinit var btnCloseWebView: ImageButton
    private lateinit var progressWebView: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_search)

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

    override fun onPause() {
        if (::biliVideoAdapter.isInitialized) biliVideoAdapter.releaseCovers()
        findViewById<View>(R.id.search_bg_host)?.let { BackgroundHelper.setActive(it, false) }
        closeLoginWebViewIfVisible()
        if (!::videoPlayer.isInitialized) return
        videoPlayer.onHostPause()
        val layoutFullscreen = findViewById<FrameLayout>(R.id.layout_fullscreen_video) ?: return
        if (layoutFullscreen.visibility == View.VISIBLE) {
            videoPlayer.saveCurrentProgress()
        }
    }

    override fun onPageShow() {
        refreshSearchSource()
    }

    override fun onPageHide() {
        if (::biliVideoAdapter.isInitialized) biliVideoAdapter.releaseCovers()
    }

    private fun refreshSearchSource() {
        val sourceSize = SongsPage.musicList.size
        if (sourceSize == lastSourceSize && ::allMusicList.isInitialized && sourceSize != 0) return
        lastSourceSize = sourceSize
        allMusicList = buildSourceList().toMutableList()

        val kw = submittedKeyword
        if (::etSearch.isInitialized && kw.isNotEmpty()) {
            if (!isBiliMode) filterMusic(kw)
        }
    }

    override fun onResume() {
        refreshSearchSource()
        isFullBiliSource = SpUtils.isFullBiliSource(this)
        updateBiliButtonState()
        updateListVisibility()
        applySettings()
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

    val isVideoFullscreenActive: Boolean
        get() = ::videoPlayer.isInitialized && videoPlayer.isFullscreenActive

    private fun setFullScreen() {
        WindowUtils.setFullScreen(activity)
    }

    private fun restoreSystemUI() {
        WindowUtils.restoreSystemUI(activity)
    }

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
        btnClearSearch?.apply {
            visibility = View.GONE
            setOnClickListener { etSearch.text.clear() }
        }

        musicAdapter = MusicAdapter(this, searchResultList).apply {
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
                btnClearSearch?.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (s.isNullOrEmpty()) clearSearchResults()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etSearch.setOnEditorActionListener { _, actionId, event ->
            val isImeConfirm = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            val isEnterKey = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (isImeConfirm || isEnterKey) {
                performSearch()
                true
            } else false
        }

        lvSearchResult.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position < 0 || position >= searchResultList.size) return@OnItemClickListener
            val bean = searchResultList[position]
            if (bean.isBilibili) playBiliAudio(bean)
            else {
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
        webViewContainer.addView(wv, 0)
        loginWebView = wv
        return wv
    }

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
                            .setTitle(R.string.add_to_target_title)
                            .setMessage(LanguageUtils.getString(this@SearchPage, R.string.add_to_target_message, video.title))
                            .setPositiveButton(R.string.add_to_playlist_title) { _, _ ->
                                PlaylistDialogHelper.showPlaylistSelector(this@SearchPage, video.toMusicBean())
                            }
                            .setNeutralButton(R.string.add_to_main_page) { _, _ ->
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

        danmakuView = findViewById<DanmakuView>(R.id.danmaku_view)!!

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
                override fun onRecordHistory(video: BiliVideo) {  }
                override fun onDanmakuToggled(enabled: Boolean) = this@SearchPage.onDanmakuToggled(enabled)
                override fun onRequestNeighbor(direction: Int): BiliVideo? {
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
            val kw = etSearch.text.toString().trim()
            submittedKeyword = kw
            if (kw.isNotEmpty()) filterMusic(kw)
            else clearSearchResults()
        }
        btnBiliToggle.setOnLongClickListener {
            showFullBiliSourceDialog()
            true
        }
    }

    private fun updateBiliButtonState() {
        btnBiliToggle.text = LanguageUtils.getString(
            this@SearchPage,
            if (isBiliMode) R.string.bili_toggle_on else R.string.bili_toggle_off
        )
    }

    private fun updateListVisibility() {
        lvSearchResult.visibility = if (isBiliMode) View.GONE else View.VISIBLE
        gvBiliResult.visibility = if (isBiliMode) View.VISIBLE else View.GONE
    }

    private fun showFullBiliSourceDialog() {
        if (isFullBiliSource) {
            DialogHelper.createStyledDialog(activity,
                AlertDialog.Builder(this)
                    .setTitle(R.string.full_bili_source_title)
                    .setMessage(R.string.full_bili_source_enabled_msg)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        SpUtils.setFullBiliSource(this, false)
                        isFullBiliSource = false
                        Toast.makeText(this, R.string.full_bili_source_disabled_toast, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
            )
        } else {
            val savedCookie = SpUtils.getBiliCookie(this)
            if (savedCookie.isNotEmpty()) {
                SpUtils.setFullBiliSource(this, true)
                isFullBiliSource = true
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
        val wv = loginWebView ?: createLoginWebView()
        wv.loadUrl("https://passport.bilibili.com/login")
        webViewContainer.visibility = View.VISIBLE
        WindowUtils.setFullScreen(activity)
    }

    private fun closeLoginWebView() {
        webViewContainer.visibility = View.GONE
        destroyLoginWebView()
        clearWebViewCacheAsync()
        setFullScreen()
    }

    private fun closeLoginWebViewIfVisible() {
        if (loginWebView == null) return
        destroyLoginWebView()
        clearWebViewCacheAsync()
    }

    private fun clearWebViewCacheAsync() {
        AppExecutors.io.execute {
            WebViewMetricsCleaner.purgeWebViewArtifacts(this)
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    private fun initPageButtons() {
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

    private fun performSearch() {
        val keyword = etSearch.text.toString().trim()
        submittedKeyword = keyword
        hideSoftKeyboard()
        etSearch.clearFocus()
        filterMusic(keyword)
    }

    private fun clearSearchResults() {
        submittedKeyword = ""
        searchResultList.clear()
        biliVideoList.clear()
        refreshAdapters()
        hideSearchingHint()
        resetPagination()
    }

    private fun hideSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
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

            val scoredList = mutableListOf<Pair<MusicBean, Int>>()

            for (b in allMusicList) {
                val normalizedName = normalizeForSearch(DataFileUtils.getDisplayName(b.musicName))
                val nameChars = normalizedName.toSet()
                val score = keywordChars.count { it in nameChars }
                if (score > 0) {
                    scoredList.add(b to score)
                }
            }

            scoredList.sortByDescending { it.second }
            searchResultList.addAll(scoredList.map { it.first })

            musicAdapter.notifyDataSetChanged()
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

        val id = biliSearchRequestId.incrementAndGet()
        AppExecutors.io.execute {
            val cookie = if (isFullBiliSource) SpUtils.getBiliCookie(this) else ""
            val result = BiliSearchHelper.searchVideosPage(currentKeyword, page, cookie)
            if (id != biliSearchRequestId.get()) {
                handler.post {
                    isLoadingMore = false
                    progressBar.visibility = View.GONE
                    tvSearchingHint.visibility = View.GONE
                }
                return@execute
            }
            handler.post {
                if (id != biliSearchRequestId.get()) return@post
                hideSearchingHint()

                if (result.errorCode == -101) {
                    Toast.makeText(this@SearchPage, R.string.cookie_invalid_load, Toast.LENGTH_LONG).show()
                    openBiliLoginWebView()
                    isLoadingMore = false
                    return@post
                }

                val videos = result.videos
                val hasMore = result.hasMore

                if (page == 1) {
                    biliVideoList.clear()
                    biliVideoAdapter.clearData()
                }
                biliVideoList.addAll(videos)
                biliVideoAdapter.addData(videos)
                biliVideoAdapter.hasMore = hasMore
                biliVideoAdapter.notifyDataSetChanged()

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
        biliVideoAdapter.hasMore = false
        biliVideoAdapter.notifyDataSetChanged()
    }

    private fun playBiliAudio(bean: MusicBean) {
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
                    subtitleTracks = BiliSubtitleHelper.fetchTracks(detail.bvid, detail.cid, cookie)
                } finally { latch.countDown() }
            }
            try { latch.await() } catch (_: InterruptedException) { }
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
                                selectedSubtitleIndices.isEmpty() ->
                                    ToastUtils.show(this@SearchPage, LanguageUtils.getString(this@SearchPage, R.string.select_at_least_one_quality))
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                )
            }
        }
    }

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

    private fun recordBiliEntry(video: BiliVideo) {
        AppExecutors.io.execute {
            BiliHistoryHelper.addEntry(video)
        }
    }

    private fun BiliVideo.toMusicBean() = MusicBean(title, "").apply {
        isBilibili = true
        bvid = this@toMusicBean.bvid
        author = this@toMusicBean.author
        duration = this@toMusicBean.duration.toIntOrNull() ?: 0
        coverUrl = this@toMusicBean.coverUrl.takeIf { it.isNotEmpty() }
    }

    private fun applySettings() {
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
