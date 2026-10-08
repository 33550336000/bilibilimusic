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
import com.tilixibiesi.util.ViewUtils
import com.tilixibiesi.ui.widget.SquareLayout

import android.annotation.SuppressLint
import android.content.Context
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

/**
 * 「正在播放」覆盖层：点歌曲页底部控制栏后从底部滑入的全屏页。
 *
 * ## 为什么是覆盖层而不是第 5 个分页
 *
 * 主容器 [MainPagerActivity] 的 4 个页面与底部导航栏是一一对应的
 * （`PAGE_*` 常量同时用作页序与导航 tag）。把播放页塞进页序会牵动
 * 导航高亮、搜索页可用性规则、通知栏跳页等一串逻辑。
 *
 * 而本页在语义上根本不是"另一页主界面"——它是歌曲页的一个全屏延伸，
 * 因此挂到 `decorView` 顶层（与 [com.tilixibiesi.ui.widget.ClickFxOverlay] 同一手法）：
 * 天然盖住分页容器与底部导航，返回时整层收起即可，对既有分页零影响。
 *
 * ## 内容来源
 *
 * 标题/艺术家/专辑/封面都取自当前播放的 [MusicBean]：
 *  - 专辑：普通音乐没有专辑概念 → 「未知专辑」；B 站合集稿件 → 合集名称；
 *    单个视频 → 视频标题。
 *  - 封面：普通音乐没有封面来源 → 占位图；B 站条目缺封面时按 bvid 现取，
 *    仍拿不到（含下载失败）→ 占位图。
 *
 * ## 线程
 *
 * 所有方法都在主线程调用（页面生命周期与广播回调都在主线程）。
 * 网络与解码在 [AppExecutors.io]，结果 post 回主线程。
 *
 * @param activity 宿主 Activity（用于取 decorView、音量服务、注册广播）
 * @param commands 页面内控件触发的播放命令，由歌曲页实现（复用其既有播放逻辑）
 */
class NowPlayingPage(
    private val activity: android.app.Activity,
    private val commands: Commands
) {

    /** 页面内控件触发的播放命令。全部交给歌曲页执行，本类不直接碰服务。 */
    interface Commands {
        fun onPlayPause()
        fun onPrev()
        fun onNext()
        fun onSeek(positionMs: Int)
    }

    companion object {
        /** 滑入/滑出动画时长 */
        private const val ANIM_MS = 220L

        /** 模糊背景的降采样目标宽度（px）。越小越"糊"，也越省内存。 */
        private const val BLUR_SAMPLE_WIDTH = 40

        /** 歌词行高（dp）与字号（sp），用于计算滚动居中的目标位置 */
        private const val LYRIC_LINE_HEIGHT_DP = 44
        private const val LYRIC_TEXT_SIZE_SP = 17f
        /** 当前句与非当前句的颜色 */
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
    private val btnLyrics: android.widget.Button

    private val ivBlur: ImageView
    private val tvLrcTitle: TextView
    private val tvLrcArtist: TextView
    private val tvLrcAlbum: TextView
    private val svLyrics: ScrollView
    private val llLyrics: LinearLayout
    private val tvNoLyrics: TextView

    /** 页面是否已展开（动画结束后仍为 true，直到 hide 完成） */
    var isVisible: Boolean = false
        private set

    /** 当前显示的是歌词页还是正在播放页 */
    var isLyricsVisible: Boolean = false
        private set

    /** 当前曲目；null 表示没有可展示的曲目 */
    private var bean: MusicBean? = null

    /**
     * 最近一次解析出的标题（已含「未知标题」兜底）。
     *
     * 专辑回退要用它判断"标题是否有效"：标题本身就是占位文案时，
     * 拿它当专辑名只会显示两遍"未知标题"，还不如直接给「未知专辑」。
     */
    private var resolvedTitle: String = ""

    /**
     * 用户是否正在拖动进度条。
     *
     * 拖动期间必须忽略来自服务的进度广播，否则每一秒的 tick 都会把
     * 用户正拖到的位置拽回实际播放位置——表现为"拖不动"。
     */
    private var isUserSeeking = false

    /** 拖动期间是否处于播放态，用于松手后维持图标 */
    private var isPlayingState = false

    /** 歌词行视图与对应的时间轴，用于高亮与滚动 */
    private val lyricLines = mutableListOf<TextView>()
    private var lyric: BiliLyric? = null
    private var lyricBvid: String? = null
    /**
     * 已拉取完成的 bvid（**含"确认没有歌词"**）。
     *
     * 必须与 [lyricBvid] 分开：`lyric` 为 null 既可能是"还没拉"也可能是"拉了但没有"，
     * 只看 `lyric != null` 会让没有歌词的歌在每次切回歌词页时重打一轮接口
     * （三个请求，最坏几秒）——而这恰恰是最常见的情况。
     */
    private var lyricLoadedBvid: String? = null
    /** 歌词请求序号：切歌后丢弃过期结果 */
    private var lyricRequestId = 0
    /** 已高亮到的行下标，避免每帧都重刷全部 TextView */
    private var activeLyricIndex = -1

    /**
     * 当前是否按横屏排布。
     *
     * 单独记一份而不是每次去读 Configuration：判断依据是**视图实际尺寸**
     * （见 [detectLandscape]），靠这个字段与实测尺寸比对来触发重排。
     */
    private var isLandscapeLayout = false

    /** 是否已按某个方向排布过。首次 layout 前为 false，用于强制走一次排布。 */
    private var orientationApplied = false

    /**
     * 控制键与歌词按钮的"原始位置"。
     *
     * 横屏要把这两者搬进底部固定栏（见 [moveControlsToBottomBar]），
     * 竖屏再搬回来。视图只能有一个父容器，所以必须记住原来的父与布局参数，
     * 否则切回竖屏时无法还原成 XML 里定义的样子。
     */
    private var controlsHome: ViewGroup? = null
    private var controlsHomeParams: LinearLayout.LayoutParams? = null
    private var lyricsHome: ViewGroup? = null
    private var lyricsHomeParams: LinearLayout.LayoutParams? = null

    /**
     * 最近一次已知的播放位置（毫秒）。
     *
     * 歌词行刚渲染完时布局还没走完（`view.top` 仍是 0），无法立刻算滚动目标；
     * 因此把位置记下来，等 `post` 到布局之后再定位一次。
     */
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
        btnLyrics = root.findViewById(R.id.btn_np_lyrics)

        ivBlur = root.findViewById(R.id.iv_np_blur)
        tvLrcTitle = root.findViewById(R.id.tv_lrc_title)
        tvLrcArtist = root.findViewById(R.id.tv_lrc_artist)
        tvLrcAlbum = root.findViewById(R.id.tv_lrc_album)
        svLyrics = root.findViewById(R.id.sv_np_lyrics)
        llLyrics = root.findViewById(R.id.ll_np_lyrics)
        tvNoLyrics = root.findViewById(R.id.tv_np_no_lyrics)

        // 记下控制键与歌词按钮的原始父容器与布局参数：
        // 横屏会把它们搬进底部固定栏，竖屏必须能原样搬回来。
        // 这里存**副本**，因为 addView 是直接持有传入的 LayoutParams 对象（不拷贝），
        // 横屏期间对 margin 的就地修改会污染"原始值"。
        // 注意：光在存的时候拷贝还不够——[restoreControlsFromBottomBar] 每次
        // 也必须交出新的副本，否则这份原始值会再次变成视图的活动参数而被改写。
        controlsHome = npControls.parent as? ViewGroup
        controlsHomeParams = (npControls.layoutParams as? LinearLayout.LayoutParams)
            ?.let { LinearLayout.LayoutParams(it) }
        lyricsHome = btnLyrics.parent as? ViewGroup
        lyricsHomeParams = (btnLyrics.layoutParams as? LinearLayout.LayoutParams)
            ?.let { LinearLayout.LayoutParams(it) }

        // 圆角裁剪：把 centerCrop 的封面按背景 drawable 的圆角裁掉溢出部分。
        // clipToOutline 没有 XML 属性（API 21 起可用），只能在代码里开。
        coverBox.clipToOutline = true

        // 封面尺寸与整体排布随横竖屏切换（见 applyOrientationLayout）
        syncOrientationLayout()

        // 自我纠正：每次 layout 后比对实测宽高，方向变了就重排。
        // 这一层是必需的——本页懒创建，且旋转回调依赖宿主转发，
        // 挂在 layout 上才能在"回调没送到"时也保证排布正确。
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) syncOrientationLayout()
        }

        // 播放键图标压在白色圆底上，必须压成深色才看得见
        btnPlay.setColorFilter(Color.BLACK)

        setupClickListeners()
        setupSeekBars()
        applyScaleFeedback()

        // 初始收在屏幕下方，且不可交互
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

    // ==================== 初始化 ====================

    /**
     * 判断当前是否横屏。
     *
     * **优先用本视图的实测宽高，而不是 [Configuration]。**
     * 本页是懒创建的（首次点开时才 addView），创建那一刻读到的 Configuration
     * 未必已随旋转更新；而视图实测尺寸永远是当下真实的结果。
     * 只有在本视图尚未布局（宽高为 0）时才退回 Configuration / 屏幕尺寸。
     */
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

    /**
     * 重新判断横竖屏；方向真的变了才重排。
     *
     * 由三处调用：构造后、宿主转发 onConfigurationChanged、以及本视图每次 layout。
     * 挂在 layout 上是关键——它让本页**自我纠正**：即使旋转回调因任何原因没送到
     * （懒创建、页面未 created、宿主漏转发），只要尺寸变了就会重新排布。
     */
    private fun syncOrientationLayout() {
        val landscape = detectLandscape()
        if (orientationApplied && landscape == isLandscapeLayout) return
        isLandscapeLayout = landscape
        orientationApplied = true
        applyOrientationLayout(landscape)
    }

    /**
     * 按横竖屏切换排布与尺寸。
     *
     * **为什么必须放在代码里而不是 layout-land：**
     * 宿主 Activity 声明了 `configChanges="…|orientation|screenSize"`，
     * 旋转时不会重建，资源系统也就不会去挑 layout-land，只能用代码改属性。
     *
     * 竖屏：单列。封面约占屏宽六成，下方依次是文字、进度、控制键。
     * 横屏：双列。封面在左，文字+进度+控制键在右。
     *       横屏可用高度只有屏宽的零头（例如 2800×1260 上中段仅约 900px），
     *       竖屏那套纵向排布连控件都放不下；且此时封面与信息是并排关系，
     *       封面高度不再挤压兄弟控件，故放宽到中段高度的 92%。
     */
    private fun applyOrientationLayout(landscape: Boolean) {
        if (landscape) {
            // 双列：封面在左，信息在右
            npContent.orientation = LinearLayout.HORIZONTAL
            npContent.gravity = Gravity.CENTER_VERTICAL

            // 封面：**由高度决定边长**，左列宽度随正方形自适应。
            //
            // 这里不能再用 weight 分宽度：weight 会把"占 42% 宽"落到 lp.width 上，
            // 而 SquareLayout 又会拿这个已分配宽度再乘一次 sizeRatio，
            // 比例被叠加两次（0.42×0.42≈17.6%），封面缩成一小块。
            // 改为 width=wrap_content + height=match_parent 后，宽度由本视图实测得出，
            // sizeRatio 取 1f 即"不二次缩放"，边长只受 maxHeightFraction 约束；
            // 左列宽度自动贴合正方形，也不会在封面与右列之间留下空档。
            //
            // 0.92：控制键搬到底部栏后，中段高度约 714px，0.92 得约 657px，
            // 与竖屏封面的 677px（屏宽的 62%）体量相当，转屏时不会"忽大忽小"。
            coverBox.sizeRatio = 1f
            coverBox.maxHeightFraction = 0.92f
            (coverBox.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.height = LinearLayout.LayoutParams.MATCH_PARENT
                lp.weight = 0f
                lp.gravity = Gravity.CENTER_VERTICAL
                coverBox.layoutParams = lp
            }

            // 右列：吃掉剩余宽度，纵向居中
            (npInfo.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = 0
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 1f
                lp.marginStart = dp(24f)
                npInfo.layoutParams = lp
            }
            // 右列内容整体居中：横屏下右列本身是窄列，左对齐会显得偏
            npInfo.gravity = Gravity.CENTER_HORIZONTAL

            // 横屏纵向紧凑：标题上边距、进度与控制的间距都要收
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
            // 控制键与歌词按钮搬到底部固定高度栏：
            // 中段（np_content）是 weight=1 的伸缩区，一旦内容超高，
            // LinearLayout 会压缩其中最后一个子视图；控制键留在里面就会被压扁
            // （白色圆底被拉成椭圆）。搬出来之后它不再参与中段的压缩。
            moveControlsToBottomBar()
            // 歌词页：横屏去掉 72dp 顶部留白，否则会吃掉大半屏歌词
            npLyricsBody.setPadding(
                npLyricsBody.paddingLeft, dp(16f), npLyricsBody.paddingRight, dp(12f)
            )
            (svLyrics.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = dp(12f)
                svLyrics.layoutParams = it
            }
        } else {
            // 单列：封面在上，文字与控件在下
            npContent.orientation = LinearLayout.VERTICAL
            npContent.gravity = Gravity.CENTER

            coverBox.sizeRatio = 0.62f
            // 竖屏下方还有标题/进度/控制，封面最多占中段高度的六成
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
            // 控制键与歌词按钮回到原本的位置（中段底部 / 整页底部）
            restoreControlsFromBottomBar()
            npLyricsBody.setPadding(
                npLyricsBody.paddingLeft, dp(72f), npLyricsBody.paddingRight, dp(24f)
            )
            (svLyrics.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = dp(36f)
                svLyrics.layoutParams = it
            }
        }

        // 尺寸变了，封面需要重新测量；歌词行的滚动定位也要按新的视口高度重算
        coverBox.requestLayout()
        if (lyricLines.isNotEmpty()) {
            svLyrics.post { scrollLyricToCenter(activeLyricIndex) }
        }
    }

    /** 屏幕旋转（Activity 不重建，由宿主显式转发）。 */
    fun onConfigurationChanged() {
        syncOrientationLayout()
    }

    /**
     * 把「播放控制键」与「查看歌词」搬进底部固定高度栏（横屏用）。
     *
     * 为什么必须搬：中段 [npContent] 是 `weight=1` 的伸缩区，当其中内容总高
     * 超过可用高度时，`LinearLayout` 会按测量顺序压缩子视图——最后一个
     * （也就是控制键那一行）被压得最狠。播放键的白色圆底是 oval drawable，
     * 高度被压小后就会拉成椭圆，这正是"标题过长导致按钮被压扁"的成因。
     *
     * 放进高度固定的底部栏后，中段成为唯一的伸缩者：超高只会让封面/文字
     * 变小或截断，控制键始终保持 68dp 的原始尺寸。
     */
    private fun moveControlsToBottomBar() {
        if (npControls.parent === npBottomBar) return
        (npControls.parent as? ViewGroup)?.removeView(npControls)
        (btnLyrics.parent as? ViewGroup)?.removeView(btnLyrics)
        npBottomBar.removeAllViews()
        // 控制键的 marginTop 在横屏要清零：底部栏自己已有 paddingTop
        (npControls.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = 0
            npControls.layoutParams = it
        }
        npBottomBar.addView(npControls)
        // 歌词按钮与播放键并排，左边留出间距
        (btnLyrics.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.marginStart = dp(20f)
            it.bottomMargin = 0
            btnLyrics.layoutParams = it
        }
        npBottomBar.addView(btnLyrics)
        npBottomBar.visibility = View.VISIBLE
    }

    /** 把控制键与歌词按钮搬回 XML 里定义的原始位置（竖屏用）。 */
    private fun restoreControlsFromBottomBar() {
        if (npBottomBar.visibility == View.GONE && npControls.parent !== npBottomBar) return
        npBottomBar.removeView(npControls)
        npBottomBar.removeView(btnLyrics)
        // 每次都必须传**新副本**，不能把 controlsHomeParams / lyricsHomeParams 本体交给 addView。
        //
        // addView(view, params) 最终执行 View.setLayoutParams(params)，是**直接持有该对象**、
        // 不做拷贝的。若把"原始参数"本体交出去，它此后就是视图的活动参数；下一轮横屏
        // moveControlsToBottomBar() 对 margin 的就地修改会永久污染这份"原始值"，
        // 于是第一次转屏正常、第二次转屏按钮位置就偏了（丢失 bottomMargin、多了 marginStart）。
        // 交给 addView 的永远是临时副本，存储的那份原始值就再也不会被改写。
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
        ViewUtils.applyScaleToButton(btnPlay)
        ViewUtils.applyScaleToButton(btnPrev)
        ViewUtils.applyScaleToButton(btnNext)
        ViewUtils.applyScaleToButton(ivCollapse)
    }

    // ==================== 显示 / 隐藏 ====================

    /** 展开整页（从底部滑入）。已展开时是空操作。 */
    fun show() {
        if (isVisible) return
        isVisible = true
        root.animate().cancel()
        root.visibility = View.VISIBLE
        // 起点用实际高度：decorView 高度此刻已确定
        root.translationY = root.height.toFloat().takeIf { it > 0f } ?: dp(800f).toFloat()
        root.animate()
            .translationY(0f)
            .setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        // 每次展开都重新同步一次：期间可能已切歌/切播放态
        refresh()
    }

    /** 收起整页（滑回底部）。 */
    fun hide() {
        if (!isVisible) return
        isVisible = false
        // 关闭时回到正在播放页，下次打开不会停在歌词页
        showPlayerPane()
        root.animate().cancel()
        root.animate()
            .translationY(root.height.toFloat())
            .setDuration(ANIM_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // 动画期间用户可能又点开了：只有仍未展开时才真正隐藏
                if (!isVisible) root.visibility = View.GONE
            }
            .start()
    }

    /**
     * 返回键处理。
     *
     * @return true 表示已消费（歌词页 → 回正在播放页，或收起整页）
     */
    fun handleBack(): Boolean {
        if (!isVisible) return false
        if (isLyricsVisible) {
            showPlayerPane()
            return true
        }
        hide()
        return true
    }

    // ==================== 曲目信息 ====================

    /**
     * 绑定当前曲目并刷新全部展示字段。
     *
     * @param bean 当前播放的条目；null 时按"无曲目"处理（全部显示未知/占位）
     */
    fun updateTrack(bean: MusicBean?) {
        this.bean = bean

        val title = bean?.let { DataFileUtils.getDisplayName(it.musicName) }
            ?.takeIf { it.isNotEmpty() }
            ?: LanguageUtils.getString(context, R.string.now_playing_unknown_title)
        resolvedTitle = title
        tvTitle.text = title
        tvLrcTitle.text = title

        // 艺术家：B 站条目带 UP 主名；普通音乐没有这个字段
        val artist = bean?.author?.takeIf { it.isNotEmpty() }
            ?: LanguageUtils.getString(context, R.string.now_playing_unknown_artist)
        tvArtist.text = artist
        tvLrcArtist.text = artist

        // 专辑：普通音乐 → 未知专辑；B 站 → 合集名，非合集 → 视频标题。
        // 合集名需要联网现取，因此先落一个保守值（未知专辑 / 视频标题），
        // 取到合集名后再覆盖。
        val unknownAlbum = LanguageUtils.getString(context, R.string.now_playing_unknown_album)
        val initialAlbum = if (bean?.isBilibili == true) title else unknownAlbum
        tvAlbum.text = initialAlbum
        tvLrcAlbum.text = initialAlbum

        loadCover(bean)
        resolveAlbumIfNeeded(bean, unknownAlbum)

        // 切歌后歌词作废：重新拉取（若正停在歌词页，用户应立即看到新歌的歌词）
        resetLyrics()
        if (isLyricsVisible) loadLyrics(bean)
    }

    /**
     * 封面：有 URL 就加载，拿不到（无 URL / 下载失败 / 解码失败）一律占位图。
     *
     * 占位图先铺上，加载成功后再替换——这样无论成功与否都不会出现空白。
     */
    private fun loadCover(bean: MusicBean?) {
        ivCover.setImageResource(R.drawable.ic_cover_placeholder)
        ivBlur.setImageDrawable(null)

        val direct = bean?.coverUrl?.takeIf { it.isNotEmpty() }
        if (direct != null) {
            fetchCoverInto(direct)
            return
        }
        // 没有现成封面：B 站条目按 bvid 现取（合集名解析会命中同一份缓存，
        // 因此这里不额外增加请求次数）
        val bvid = bean?.bvid?.takeIf { it.isNotEmpty() } ?: return
        BiliVideoMetaHelper.fetch(bvid) { meta ->
            // 回调期间可能已切歌：只有仍是同一个 bvid 才落地
            if (this.bean?.bvid != bvid) return@fetch
            val url = meta?.coverUrl
            if (url.isNullOrEmpty()) {
                ivCover.setImageResource(R.drawable.ic_cover_placeholder)
                return@fetch
            }
            // 顺手写回条目，后续再打开本页就不必重新解析
            this.bean?.coverUrl = url
            fetchCoverInto(url)
        }
    }

    /** 加载封面并同时铺到主图与歌词页模糊背景上（同一位图，不重复下载） */
    private fun fetchCoverInto(url: String) {
        BiliCoverLoader.fetch(url) { bitmap ->
            if (bitmap == null) {
                // 下载失败：明确回到占位图，而不是留一个空框
                ivCover.setImageResource(R.drawable.ic_cover_placeholder)
                ivBlur.setImageDrawable(null)
                return@fetch
            }
            ivCover.setImageBitmap(bitmap)
            ivBlur.setImageBitmap(downscaleForBlur(bitmap))
        }
    }

    /**
     * 生成歌词页的背景图：把封面大幅降采样后交给 ImageView 放大。
     *
     * API 30 没有 `RenderEffect`（API 31 才加入），做不了真实高斯模糊。
     * 这里靠"降采样 + 双线性放大"得到柔和的色块，观感接近模糊且零依赖。
     */
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

    /**
     * B 站条目：解析合集名并覆盖专辑字段。
     *
     * 普通音乐直接跳过（没有合集概念，专辑保持「未知专辑」）。
     *
     * @param unknownAlbum 「未知专辑」文案，由调用方取好传入（本方法在回调里用到，
     *                     提前取可避免在主线程回调里反复查资源表）
     */
    private fun resolveAlbumIfNeeded(bean: MusicBean?, unknownAlbum: String) {
        if (bean?.isBilibili != true) return
        val bvid = bean.bvid?.takeIf { it.isNotEmpty() } ?: return
        val unknownTitle = LanguageUtils.getString(context, R.string.now_playing_unknown_title)
        BiliVideoMetaHelper.fetch(bvid) { meta ->
            // 回调期间已切歌：丢弃，别把上一首的专辑名贴到新歌上
            if (this.bean?.bvid != bvid) return@fetch
            val album = meta?.collectionTitle?.takeIf { it.isNotEmpty() }
                // 单个视频（无合集）用视频标题兜底；标题本身也没取到时退回「未知专辑」
                ?: resolvedTitle.takeIf { it.isNotEmpty() && it != unknownTitle }
                ?: unknownAlbum
            tvAlbum.text = album
            tvLrcAlbum.text = album
            // 顺带补封面（老历史记录可能没存 coverUrl）。
            // loadCover 已发过同一 bvid 的请求，这里命中 BiliVideoMetaHelper 的缓存，
            // 不会产生第二次网络往返。
            val cover = meta?.coverUrl
            if (this.bean?.coverUrl.isNullOrEmpty() && !cover.isNullOrEmpty()) {
                this.bean?.coverUrl = cover
                fetchCoverInto(cover)
            }
        }
    }

    // ==================== 进度与播放态 ====================

    /**
     * 同步进度、时长与播放状态。由歌曲页在收到播放广播时转发。
     *
     * [positionMs] / [durationMs] 传 null 表示**这次广播没有携带该信息**，
     * 应当沿用页面上已有的值而不是归零：暂停广播（[MusicPlayerService.sendPlayStateBroadcast]）
     * 只带播放状态与歌名，不带位置——若在这里按 0 处理，一按暂停进度条就会跳回开头。
     *
     * @param positionMs 当前位置；null 表示本次不更新进度
     * @param durationMs 总时长；null 表示本次不更新时长
     */
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
        // 位置未知时保持原样：暂停不该把进度条拉回 0
        if (positionMs != null && !isUserSeeking) {
            sbProgress.progress = positionMs.coerceIn(0, sbProgress.max.coerceAtLeast(0))
            tvCur.text = formatTime(positionMs)
            // 歌词跟随：只在位置真的推进到另一句时才刷 UI
            updateLyricHighlight(positionMs)
        }
    }

    /** 从服务重新拉一次进度（展开页面、回到前台时用） */
    fun refresh() {
        // 进度由歌曲页通过 ACTION_REQUEST_PROGRESS 主动拉取后经 updateProgress 回填，
        // 本类不自己拼 Intent，避免与服务耦合。
    }

    // ==================== 歌词 ====================

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

    /** 切歌时作废歌词状态：清空行、隐藏滚动区、回到"加载中"的空白态 */
    private fun resetLyrics() {
        lyricRequestId++
        lyric = null
        lyricBvid = null
        lyricLoadedBvid = null
        activeLyricIndex = -1
        lyricLines.clear()
        llLyrics.removeAllViews()
        svLyrics.visibility = View.GONE
        // 清空后先不显示「暂无歌词」：结果未知，等加载完再决定，
        // 否则每次切歌都会闪一下"暂无歌词"。
        tvNoLyrics.visibility = View.GONE
    }

    /**
     * 拉取并渲染歌词。
     *
     * 只有 B 站条目可能有歌词（本应用没有本地歌词文件的概念），
     * 普通音乐直接显示「暂无歌词」，不发任何网络请求。
     */
    private fun loadLyrics(bean: MusicBean?) {
        val bvid = bean?.bvid?.takeIf { it.isNotEmpty() && bean.isBilibili }
        if (bvid == null) {
            showNoLyrics()
            return
        }
        // 这首歌已经拉过了（不论有没有歌词）：直接按结果渲染，不重打接口。
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
                // 字幕兜底需要登录态；曲库链路不需要，传上去无副作用
                cookie = SpUtils.getBiliCookie(context)
            )
            handler.post {
                // 已切歌 / 页面已收起：丢弃过期结果
                if (requestId != lyricRequestId) return@post
                lyricBvid = bvid
                // 否定结果同样记入"已加载"，避免反复重试
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

    /** 把歌词逐行塞进滚动区；行数多时靠 ScrollView 滚动 */
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
                // 单行歌词不换行，超出部分省略，避免把行高撑破导致滚动定位失准
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                // 点某句 → 立即跳到它的起始时间。
                // 用 index 而不是文本去定位时间：重复句（副歌）按文本查会一律命中第一处。
                isClickable = true
                setBackgroundResource(R.drawable.lyric_line_bg)
                setOnClickListener { seekToLyric(index) }
            }
            llLyrics.addView(tv)
            lyricLines.add(tv)
        }
        svLyrics.visibility = View.VISIBLE
        tvNoLyrics.visibility = View.GONE
        // 立即按当前位置定位一次，避免刚打开时停在第一行。
        // 必须 post：此刻行视图刚 addView，尚未测量布局，
        // `view.top` 全是 0，直接算出来的滚动目标必然是错的。
        activeLyricIndex = -1
        svLyrics.post { updateLyricHighlight(lastKnownPositionMs) }
    }

    /**
     * 跳到第 [index] 句歌词的起始时间。
     *
     * 三件事必须一起做，否则界面会"跳完立刻回弹"：
     *  1. 更新本地已知位置与高亮——进度广播下一次 tick 才到（最多 1 秒），
     *     若不等它，用户点完会先看到旧位置，像没生效；
     *  2. 把高亮强行置为这一句，不经过 [updateLyricHighlight] 的"同句即忽略"判断；
     *  3. 命令歌曲页 seek，由它去驱动服务（本类不直接碰服务）。
     *
     * 歌词页没有进度条，因此不必处理"用户正在拖动进度条"的冲突。
     */
    private fun seekToLyric(index: Int) {
        val l = lyric ?: return
        val line = l.lines.getOrNull(index) ?: return
        val targetMs = (line.fromSec * 1000f).toInt().coerceAtLeast(0)

        lastKnownPositionMs = targetMs
        // 立即高亮并滚动：反馈必须跟手，不能等服务回传
        applyActiveLyric(index, scroll = true)
        commands.onSeek(targetMs)
    }

    /**
     * 高亮当前句并把视口滚到它附近。
     *
     * 只在句号变化时动 UI：每秒的进度 tick 都会调到这里，
     * 若每次都遍历所有 TextView，几百行的歌词会明显掉帧。
     */
    private fun updateLyricHighlight(positionMs: Int) {
        lastKnownPositionMs = positionMs
        val l = lyric ?: return
        if (lyricLines.isEmpty()) return
        val index = l.indexAt(positionMs.toLong())
        if (index < 0 || index == activeLyricIndex) return
        applyActiveLyric(index, scroll = true)
    }

    /**
     * 把第 [index] 行设为当前句。
     *
     * @param scroll 是否同时把该行滚到视口中间。点歌词跳转时同样要滚，
     *               否则用户点了屏幕外的句子、画面却停在原处。
     */
    private fun applyActiveLyric(index: Int, scroll: Boolean) {
        if (index !in lyricLines.indices) return
        if (activeLyricIndex in lyricLines.indices) {
            lyricLines[activeLyricIndex].setTextColor(LYRIC_NORMAL_COLOR)
        }
        lyricLines[index].setTextColor(LYRIC_ACTIVE_COLOR)
        activeLyricIndex = index
        if (scroll) scrollLyricToCenter(index)
    }

    /** 把第 [index] 行滚到视口中间，让当前句始终处于视觉焦点 */
    private fun scrollLyricToCenter(index: Int) {
        val view = lyricLines.getOrNull(index) ?: return
        val target = view.top - (svLyrics.height - view.height) / 2
        // 平滑滚动：直接 scrollTo 会一跳一跳，观感很生硬
        svLyrics.smoothScrollTo(0, target.coerceAtLeast(0))
    }

    // ==================== 工具 ====================

    private fun formatTime(ms: Int): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60)
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()

    /**
     * 释放：从 decorView 摘下整层。
     *
     * 必须显式摘除：本页挂在 decorView 上而非页面视图树里，
     * 页面 onDestroy 不会连带移除它，留着就是一处 Activity 泄漏。
     */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        runCatching { (root.parent as? ViewGroup)?.removeView(root) }
    }
}
