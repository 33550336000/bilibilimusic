package com.tilixibiesi.ui

import com.tilixibiesi.R
import com.tilixibiesi.bili.BiliCoverLoader
import com.tilixibiesi.bili.BiliLyric
import com.tilixibiesi.bili.BiliLyricHelper
import com.tilixibiesi.bili.BiliSearchHelper
import com.tilixibiesi.bili.BiliVideoMetaHelper
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.ToastUtils
import com.tilixibiesi.util.ViewUtils
import com.tilixibiesi.ui.widget.SquareLayout

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

class NowPlayingPage(
    private val activity: android.app.Activity,
    private val commands: Commands
) {

    interface Commands {
        fun onPlayPause()
        fun onPrev()
        fun onNext()
        fun onSeek(positionMs: Int)
    }

    companion object {
        private const val ANIM_MS = 220L

        private const val BLUR_SAMPLE_WIDTH = 40

        private const val LYRIC_LINE_HEIGHT_DP = 44
        private const val LYRIC_TEXT_SIZE_SP = 17f
        private const val LYRIC_ACTIVE_COLOR = 0xFFFFFFFF.toInt()
        private const val LYRIC_NORMAL_COLOR = 0x99FFFFFF.toInt()
    }

    private val context: Context = activity
    private val handler = Handler(Looper.getMainLooper())

    private val root: FrameLayout
    private val playerPane: View
    private val lyricsPane: View

    private val npContent: LinearLayout
    private val npInfo: LinearLayout
    private val npLyricsBody: LinearLayout
    private val npControls: LinearLayout
    private val npBottomBar: LinearLayout

    private val ivCollapse: ImageButton
    private val ivCover: ImageView
    private val coverBox: SquareLayout
    private val tvTitle: TextView
    private val tvArtist: TextView
    private val tvAlbum: TextView
    private val sbProgress: SeekBar
    private val tvCur: TextView
    private val tvTotal: TextView
    private val btnPrev: ImageButton
    private val btnPlay: ImageButton
    private val btnNext: ImageButton
    private val btnShare: ImageButton
    private val btnLyrics: android.widget.Button

    private val ivBlur: ImageView
    private val tvLrcTitle: TextView
    private val tvLrcArtist: TextView
    private val tvLrcAlbum: TextView
    private val svLyrics: ScrollView
    private val llLyrics: LinearLayout
    private val tvNoLyrics: TextView

    var isVisible: Boolean = false
        private set

    var isLyricsVisible: Boolean = false
        private set

    private var bean: MusicBean? = null

    private var resolvedTitle: String = ""

    private var isUserSeeking = false

    private var isPlayingState = false

    private val lyricLines = mutableListOf<TextView>()
    private var lyric: BiliLyric? = null
    private var lyricBvid: String? = null
    private var lyricLoadedBvid: String? = null
    private var lyricRequestId = 0
    private var activeLyricIndex = -1

    private var isLandscapeLayout = false

    private var orientationApplied = false

    private var controlsHome: ViewGroup? = null
    private var controlsHomeParams: LinearLayout.LayoutParams? = null
    private var lyricsHome: ViewGroup? = null
    private var lyricsHomeParams: LinearLayout.LayoutParams? = null

    private var lastKnownPositionMs = 0

    init {
        root = LayoutInflater.from(activity)
            .inflate(R.layout.page_now_playing, null) as FrameLayout
        playerPane = root.findViewById(R.id.np_player)
        lyricsPane = root.findViewById(R.id.np_lyrics)

        npContent = root.findViewById(R.id.np_content)
        npInfo = root.findViewById(R.id.np_info)
        npLyricsBody = root.findViewById(R.id.np_lyrics_body)
        npControls = root.findViewById(R.id.np_controls)
        npBottomBar = root.findViewById(R.id.np_bottom_bar)

        ivCollapse = root.findViewById(R.id.iv_np_collapse)
        ivCover = root.findViewById(R.id.iv_np_cover)
        coverBox = root.findViewById(R.id.np_cover_box)
        tvTitle = root.findViewById(R.id.tv_np_title)
        tvArtist = root.findViewById(R.id.tv_np_artist)
        tvAlbum = root.findViewById(R.id.tv_np_album)
        sbProgress = root.findViewById(R.id.sb_np_progress)
        tvCur = root.findViewById(R.id.tv_np_cur)
        tvTotal = root.findViewById(R.id.tv_np_total)
        btnPrev = root.findViewById(R.id.btn_np_prev)
        btnPlay = root.findViewById(R.id.btn_np_play)
        btnNext = root.findViewById(R.id.btn_np_next)
        btnShare = root.findViewById(R.id.btn_np_share)
        btnLyrics = root.findViewById(R.id.btn_np_lyrics)

        ivBlur = root.findViewById(R.id.iv_np_blur)
        tvLrcTitle = root.findViewById(R.id.tv_lrc_title)
        tvLrcArtist = root.findViewById(R.id.tv_lrc_artist)
        tvLrcAlbum = root.findViewById(R.id.tv_lrc_album)
        svLyrics = root.findViewById(R.id.sv_np_lyrics)
        llLyrics = root.findViewById(R.id.ll_np_lyrics)
        tvNoLyrics = root.findViewById(R.id.tv_np_no_lyrics)

        controlsHome = npControls.parent as? ViewGroup
        controlsHomeParams = (npControls.layoutParams as? LinearLayout.LayoutParams)
            ?.let { LinearLayout.LayoutParams(it) }
        lyricsHome = btnLyrics.parent as? ViewGroup
        lyricsHomeParams = (btnLyrics.layoutParams as? LinearLayout.LayoutParams)
            ?.let { LinearLayout.LayoutParams(it) }

        coverBox.clipToOutline = true

        syncOrientationLayout()

        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) syncOrientationLayout()
        }

        btnPlay.setColorFilter(Color.BLACK)

        setupClickListeners()
        setupSeekBars()
        applyScaleFeedback()

        root.visibility = View.GONE
        root.translationY = 1f
        (activity.window.decorView as? ViewGroup)?.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }


    private fun detectLandscape(): Boolean {
        val w = root.width
        val h = root.height
        if (w > 0 && h > 0) return w > h
        val cfg = context.resources.configuration
        if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) return true
        if (cfg.orientation == Configuration.ORIENTATION_PORTRAIT) return false
        val dm = context.resources.displayMetrics
        return dm.widthPixels > dm.heightPixels
    }

    private fun syncOrientationLayout() {
        val landscape = detectLandscape()
        if (orientationApplied && landscape == isLandscapeLayout) return
        isLandscapeLayout = landscape
        orientationApplied = true
        applyOrientationLayout(landscape)
    }

    private fun applyOrientationLayout(landscape: Boolean) {
        if (landscape) {
            npContent.orientation = LinearLayout.HORIZONTAL
            npContent.gravity = Gravity.CENTER_VERTICAL

            coverBox.sizeRatio = 1f
            coverBox.maxHeightFraction = 0.92f
            (coverBox.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.height = LinearLayout.LayoutParams.MATCH_PARENT
                lp.weight = 0f
                lp.gravity = Gravity.CENTER_VERTICAL
                coverBox.layoutParams = lp
            }

            (npInfo.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = 0
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 1f
                lp.marginStart = dp(24f)
                npInfo.layoutParams = lp
            }
            npInfo.gravity = Gravity.CENTER_HORIZONTAL

            (tvTitle.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = 0
                tvTitle.layoutParams = it
            }
            (sbProgress.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = dp(16f)
                sbProgress.layoutParams = it
            }
            (npControls.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = 0
                npControls.layoutParams = it
            }
            moveControlsToBottomBar()
            npLyricsBody.setPadding(
                npLyricsBody.paddingLeft, dp(16f), npLyricsBody.paddingRight, dp(12f)
            )
            (svLyrics.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = dp(12f)
                svLyrics.layoutParams = it
            }
        } else {
            npContent.orientation = LinearLayout.VERTICAL
            npContent.gravity = Gravity.CENTER

            coverBox.sizeRatio = 0.62f
            coverBox.maxHeightFraction = 0.6f
            (coverBox.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
                lp.gravity = Gravity.CENTER_HORIZONTAL
                coverBox.layoutParams = lp
            }

            (npInfo.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
                lp.marginStart = 0
                npInfo.layoutParams = lp
            }
            npInfo.gravity = Gravity.START

            (tvTitle.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = dp(28f)
                tvTitle.layoutParams = it
            }
            (sbProgress.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = dp(24f)
                sbProgress.layoutParams = it
            }
            (npControls.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = dp(20f)
                npControls.layoutParams = it
            }
            restoreControlsFromBottomBar()
            npLyricsBody.setPadding(
                npLyricsBody.paddingLeft, dp(72f), npLyricsBody.paddingRight, dp(24f)
            )
            (svLyrics.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = dp(36f)
                svLyrics.layoutParams = it
            }
        }

        coverBox.requestLayout()
        if (lyricLines.isNotEmpty()) {
            svLyrics.post { scrollLyricToCenter(activeLyricIndex) }
        }
    }

    fun onConfigurationChanged() {
        syncOrientationLayout()
    }

    private fun moveControlsToBottomBar() {
        if (npControls.parent === npBottomBar) return
        (npControls.parent as? ViewGroup)?.removeView(npControls)
        (btnLyrics.parent as? ViewGroup)?.removeView(btnLyrics)
        npBottomBar.removeAllViews()
        (npControls.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = 0
            npControls.layoutParams = it
        }
        npBottomBar.addView(npControls)
        (btnLyrics.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.marginStart = dp(20f)
            it.bottomMargin = 0
            btnLyrics.layoutParams = it
        }
        npBottomBar.addView(btnLyrics)
        npBottomBar.visibility = View.VISIBLE
    }

    private fun restoreControlsFromBottomBar() {
        if (npBottomBar.visibility == View.GONE && npControls.parent !== npBottomBar) return
        npBottomBar.removeView(npControls)
        npBottomBar.removeView(btnLyrics)
        controlsHome?.let { home ->
            (npControls.parent as? ViewGroup)?.removeView(npControls)
            val lp = controlsHomeParams
            if (lp != null) home.addView(npControls, LinearLayout.LayoutParams(lp))
            else home.addView(npControls)
        }
        lyricsHome?.let { home ->
            (btnLyrics.parent as? ViewGroup)?.removeView(btnLyrics)
            val lp = lyricsHomeParams
            if (lp != null) home.addView(btnLyrics, LinearLayout.LayoutParams(lp))
            else home.addView(btnLyrics)
        }
        npBottomBar.visibility = View.GONE
    }

    private fun setupClickListeners() {
        ivCollapse.setOnClickListener { hide() }
        btnLyrics.setOnClickListener { showLyrics() }
        btnShare.setOnClickListener { shareCurrentTrack() }
        btnPlay.setOnClickListener { commands.onPlayPause() }
        btnPrev.setOnClickListener { commands.onPrev() }
        btnNext.setOnClickListener { commands.onNext() }
    }

    private fun setupSeekBars() {
        sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvCur.text = formatTime(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                val target = sb?.progress ?: 0
                if (sbProgress.max > 0) commands.onSeek(target)
            }
        })
    }

    private fun applyScaleFeedback() {
        ViewUtils.applyScaleToButton(btnLyrics)
        ViewUtils.applyScaleToButton(btnShare)
        ViewUtils.applyScaleToButton(btnPlay)
        ViewUtils.applyScaleToButton(btnPrev)
        ViewUtils.applyScaleToButton(btnNext)
        ViewUtils.applyScaleToButton(ivCollapse)
    }


    fun show() {
        if (isVisible) return
        isVisible = true
        root.animate().cancel()
        root.visibility = View.VISIBLE
        root.translationY = root.height.toFloat().takeIf { it > 0f } ?: dp(800f).toFloat()
        root.animate()
            .translationY(0f)
            .setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        refresh()
    }

    fun hide() {
        if (!isVisible) return
        isVisible = false
        showPlayerPane()
        root.animate().cancel()
        root.animate()
            .translationY(root.height.toFloat())
            .setDuration(ANIM_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (!isVisible) root.visibility = View.GONE
            }
            .start()
    }

    fun handleBack(): Boolean {
        if (!isVisible) return false
        if (isLyricsVisible) {
            showPlayerPane()
            return true
        }
        hide()
        return true
    }


    fun updateTrack(bean: MusicBean?) {
        this.bean = bean

        val title = bean?.let { DataFileUtils.getDisplayName(it.musicName) }
            ?.takeIf { it.isNotEmpty() }
            ?: LanguageUtils.getString(context, R.string.now_playing_unknown_title)
        resolvedTitle = title
        tvTitle.text = title
        tvLrcTitle.text = title

        val artist = bean?.author?.takeIf { it.isNotEmpty() }
            ?: LanguageUtils.getString(context, R.string.now_playing_unknown_artist)
        tvArtist.text = artist
        tvLrcArtist.text = artist

        val unknownAlbum = LanguageUtils.getString(context, R.string.now_playing_unknown_album)
        val initialAlbum = if (bean?.isBilibili == true) title else unknownAlbum
        tvAlbum.text = initialAlbum
        tvLrcAlbum.text = initialAlbum

        loadCover(bean)
        resolveAlbumIfNeeded(bean, unknownAlbum)

        resetLyrics()
        if (isLyricsVisible) loadLyrics(bean)
    }

    private fun shareCurrentTrack() {
        val current = bean ?: return
        val title = resolvedTitle.takeIf { it.isNotEmpty() }
            ?: LanguageUtils.getString(context, R.string.now_playing_unknown_title)

        if (!current.isBilibili) {
            val url = current.musicUrl.takeIf { it.isNotEmpty() }
            if (url == null) {
                ToastUtils.show(context, LanguageUtils.getString(context, R.string.now_playing_share_no_link))
                return
            }
            sendShare(title, url)
            return
        }

        val cachedUrl = current.musicUrl.takeIf { it.isNotEmpty() }
        if (cachedUrl != null) {
            sendShare(title, cachedUrl)
            return
        }
        val bvid = current.bvid?.takeIf { it.isNotEmpty() }
        if (bvid == null) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.now_playing_share_no_link))
            return
        }
        val shareBvid = bvid
        AppExecutors.io.execute {
            val url = runCatching { BiliSearchHelper.getPreferredAudioUrl(shareBvid) }.getOrNull()
            handler.post {
                if (bean?.bvid != shareBvid) return@post
                if (url.isNullOrEmpty()) {
                    ToastUtils.show(context, LanguageUtils.getString(context, R.string.now_playing_share_no_link))
                } else {
                    bean?.musicUrl = url
                    sendShare(title, url)
                }
            }
        }
    }

    private fun sendShare(title: String, url: String) {
        val text = LanguageUtils.getString(context, R.string.now_playing_share_format, title, url)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, LanguageUtils.getString(context, R.string.now_playing_share_chooser))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            ToastUtils.show(context, LanguageUtils.getString(context, R.string.now_playing_share_failed))
        }
    }

    private fun loadCover(bean: MusicBean?) {
        ivCover.setImageResource(R.drawable.ic_cover_placeholder)
        ivBlur.setImageDrawable(null)

        val direct = bean?.coverUrl?.takeIf { it.isNotEmpty() }
        if (direct != null) {
            fetchCoverInto(direct)
            return
        }
        val bvid = bean?.bvid?.takeIf { it.isNotEmpty() } ?: return
        BiliVideoMetaHelper.fetch(bvid) { meta ->
            if (this.bean?.bvid != bvid) return@fetch
            val url = meta?.coverUrl
            if (url.isNullOrEmpty()) {
                ivCover.setImageResource(R.drawable.ic_cover_placeholder)
                return@fetch
            }
            this.bean?.coverUrl = url
            fetchCoverInto(url)
        }
    }

    private fun fetchCoverInto(url: String) {
        BiliCoverLoader.fetch(url) { bitmap ->
            if (bitmap == null) {
                ivCover.setImageResource(R.drawable.ic_cover_placeholder)
                ivBlur.setImageDrawable(null)
                return@fetch
            }
            ivCover.setImageBitmap(bitmap)
            ivBlur.setImageBitmap(downscaleForBlur(bitmap))
        }
    }

    private fun downscaleForBlur(src: Bitmap): Bitmap? {
        return try {
            val ratio = BLUR_SAMPLE_WIDTH.toFloat() / src.width.coerceAtLeast(1)
            val w = BLUR_SAMPLE_WIDTH.coerceAtLeast(1)
            val h = (src.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveAlbumIfNeeded(bean: MusicBean?, unknownAlbum: String) {
        if (bean?.isBilibili != true) return
        val bvid = bean.bvid?.takeIf { it.isNotEmpty() } ?: return
        val unknownTitle = LanguageUtils.getString(context, R.string.now_playing_unknown_title)
        BiliVideoMetaHelper.fetch(bvid) { meta ->
            if (this.bean?.bvid != bvid) return@fetch
            val album = meta?.collectionTitle?.takeIf { it.isNotEmpty() }
                ?: resolvedTitle.takeIf { it.isNotEmpty() && it != unknownTitle }
                ?: unknownAlbum
            tvAlbum.text = album
            tvLrcAlbum.text = album
            val cover = meta?.coverUrl
            if (this.bean?.coverUrl.isNullOrEmpty() && !cover.isNullOrEmpty()) {
                this.bean?.coverUrl = cover
                fetchCoverInto(cover)
            }
        }
    }


    fun updateProgress(positionMs: Int?, durationMs: Int?, isPlaying: Boolean) {
        isPlayingState = isPlaying
        btnPlay.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        btnPlay.setColorFilter(Color.BLACK)

        if (durationMs != null && durationMs > 0) {
            if (sbProgress.max != durationMs) sbProgress.max = durationMs
            tvTotal.text = formatTime(durationMs)
        }
        if (positionMs != null && !isUserSeeking) {
            sbProgress.progress = positionMs.coerceIn(0, sbProgress.max.coerceAtLeast(0))
            tvCur.text = formatTime(positionMs)
            updateLyricHighlight(positionMs)
        }
    }

    fun refresh() {
    }


    private fun showLyrics() {
        isLyricsVisible = true
        playerPane.visibility = View.GONE
        lyricsPane.visibility = View.VISIBLE
        loadLyrics(bean)
    }

    private fun showPlayerPane() {
        isLyricsVisible = false
        lyricsPane.visibility = View.GONE
        playerPane.visibility = View.VISIBLE
    }

    private fun resetLyrics() {
        lyricRequestId++
        lyric = null
        lyricBvid = null
        lyricLoadedBvid = null
        activeLyricIndex = -1
        lyricLines.clear()
        llLyrics.removeAllViews()
        svLyrics.visibility = View.GONE
        tvNoLyrics.visibility = View.GONE
    }

    private fun loadLyrics(bean: MusicBean?) {
        val bvid = bean?.bvid?.takeIf { it.isNotEmpty() && bean.isBilibili }
        if (bvid == null) {
            showNoLyrics()
            return
        }
        if (lyricLoadedBvid == bvid) {
            val cached = lyric
            if (cached == null || cached.lines.isEmpty()) showNoLyrics() else renderLyric(cached)
            return
        }
        val requestId = ++lyricRequestId
        val durationSec = (sbProgress.max / 1000).takeIf { it > 0 } ?: (bean.duration)
        AppExecutors.io.execute {
            val cid = BiliSearchHelper.resolveCid(bvid) ?: 0L
            val result = if (cid <= 0L) null else BiliLyricHelper.fetch(
                bvid = bvid,
                cid = cid,
                durationSec = durationSec,
                cookie = SpUtils.getBiliCookie(context)
            )
            handler.post {
                if (requestId != lyricRequestId) return@post
                lyricBvid = bvid
                lyricLoadedBvid = bvid
                lyric = result
                if (result == null || result.lines.isEmpty()) showNoLyrics() else renderLyric(result)
            }
        }
    }

    private fun showNoLyrics() {
        lyricLines.clear()
        llLyrics.removeAllViews()
        svLyrics.visibility = View.GONE
        tvNoLyrics.visibility = View.VISIBLE
    }

    @SuppressLint("SetTextI18n")
    private fun renderLyric(lyric: BiliLyric) {
        lyricLines.clear()
        llLyrics.removeAllViews()
        val lineHeight = dp(LYRIC_LINE_HEIGHT_DP.toFloat())
        lyric.lines.forEachIndexed { index, line ->
            val tv = TextView(context).apply {
                text = line.text
                textSize = LYRIC_TEXT_SIZE_SP
                setTextColor(LYRIC_NORMAL_COLOR)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    lineHeight
                )
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = true
                setBackgroundResource(R.drawable.lyric_line_bg)
                setOnClickListener { seekToLyric(index) }
            }
            llLyrics.addView(tv)
            lyricLines.add(tv)
        }
        svLyrics.visibility = View.VISIBLE
        tvNoLyrics.visibility = View.GONE
        activeLyricIndex = -1
        svLyrics.post { updateLyricHighlight(lastKnownPositionMs) }
    }

    private fun seekToLyric(index: Int) {
        val l = lyric ?: return
        val line = l.lines.getOrNull(index) ?: return
        val targetMs = (line.fromSec * 1000f).toInt().coerceAtLeast(0)

        lastKnownPositionMs = targetMs
        applyActiveLyric(index, scroll = true)
        commands.onSeek(targetMs)
    }

    private fun updateLyricHighlight(positionMs: Int) {
        lastKnownPositionMs = positionMs
        val l = lyric ?: return
        if (lyricLines.isEmpty()) return
        val index = l.indexAt(positionMs.toLong())
        if (index < 0 || index == activeLyricIndex) return
        applyActiveLyric(index, scroll = true)
    }

    private fun applyActiveLyric(index: Int, scroll: Boolean) {
        if (index !in lyricLines.indices) return
        if (activeLyricIndex in lyricLines.indices) {
            lyricLines[activeLyricIndex].setTextColor(LYRIC_NORMAL_COLOR)
        }
        lyricLines[index].setTextColor(LYRIC_ACTIVE_COLOR)
        activeLyricIndex = index
        if (scroll) scrollLyricToCenter(index)
    }

    private fun scrollLyricToCenter(index: Int) {
        val view = lyricLines.getOrNull(index) ?: return
        val target = view.top - (svLyrics.height - view.height) / 2
        svLyrics.smoothScrollTo(0, target.coerceAtLeast(0))
    }


    private fun formatTime(ms: Int): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60)
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()

    fun release() {
        handler.removeCallbacksAndMessages(null)
        runCatching { (root.parent as? ViewGroup)?.removeView(root) }
    }
}
