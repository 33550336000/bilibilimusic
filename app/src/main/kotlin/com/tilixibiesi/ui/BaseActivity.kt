package com.tilixibiesi.ui
import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.LocalizedResources
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.util.ViewUtils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

open class BaseActivity : Activity() {

    private var lastLanguageCode: String? = null

    /**
     * 等价于 Context.RECEIVER_EXPORTED（API 33 新增，该常量值固定为 2）。
     * 因为 compileSdk 仍为 30，编译期无法引用该符号，这里用等值字面量。
     */
    private val RECEIVER_EXPORTED = 2

    /** 启动时后台下载完当前语言资源后，收到广播即重建界面以应用新语言 */
    private val languageUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LanguageUtils.ACTION_LANGUAGE_UPDATED) {
                lastLanguageCode = LanguageUtils.getEffectiveLanguage(applicationContext)
                recreate()
            }
        }
    }

    // 缓存语言资源包装器，用于让所有 LanguageUtils.getString(this, R.string.xxx)（无格式化参数）走 JSON 覆盖
    private var localizedResources: Resources? = null

    override fun getResources(): Resources {
        var res = localizedResources
        if (res == null) {
            res = LocalizedResources(super.getResources())
            localizedResources = res
        }
        return res
    }

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = LanguageUtils.wrapContext(newBase ?: this)
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 安装布局本地化工厂：在 inflate 时按当前语言覆盖布局 XML 里的 @string 文本，
        // 覆盖本 Activity 页面、Adapter 列表项以及代码 inflate 的弹窗，无需逐处 findViewById。
        // 二者通常为同一 LayoutInflater 实例；分别安装以兼容不同 ROM 的实现差异。
        LocalizedViewFactory.install(layoutInflater)
        LocalizedViewFactory.install(LayoutInflater.from(this))
        lastLanguageCode = LanguageUtils.getEffectiveLanguage(applicationContext)
        registerLanguageReceiver()
    }

    /**
     * 注册语言更新广播。
     *
     * 该广播按预期需要能被外部触发（如 am broadcast），故沿用「导出」语义，
     * Android 13(API 33) 起注册非系统广播必须显式携带 RECEIVER_EXPORTED 标志。
     *
     * 注：compileSdk 仍为 30，编译期拿不到 API 33 的常量/重载语义，
     * 因此用等值字面量与 SDK_INT 守卫，并对 lint 的两条相应告警做局部抑制。
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag", "WrongConstant")
    private fun registerLanguageReceiver() {
        val langFilter = IntentFilter(LanguageUtils.ACTION_LANGUAGE_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(languageUpdatedReceiver, langFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(languageUpdatedReceiver, langFilter)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(languageUpdatedReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 用「实际生效语言」比较：启动时的静默下载即使广播被错过，
        // 只要生效语言（如「跟随系统」解析出的英文）发生变化也会重建界面。
        val currentLang = LanguageUtils.getEffectiveLanguage(applicationContext)
        if (currentLang != lastLanguageCode) {
            recreate()
        }
    }

    // ⭐ 在视图完全构建后，为所有按钮添加缩放监听，并处理底部导航栏
    override fun onStart() {
        super.onStart()
        // 获取 content 根容器（不包含状态栏/标题栏等系统视图）
        val content = findViewById<ViewGroup>(android.R.id.content)
        ViewUtils.applyScaleOnTouch(content)
        setupBottomNav()
    }

    // ⭐ 底部导航栏：在设置页开启后，主活动底部显示导航按钮，点击切换指定活动
    protected fun setupBottomNav() {
        // 4 个主页面已合并进 MainPagerActivity，导航栏只在主容器内渲染
        if (this !is MainPagerActivity) return

        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val existingNav = content.findViewWithTag<View>(TAG_BOTTOM_NAV)
        val navHeightPx = (NAV_HEIGHT_DP * resources.displayMetrics.density).toInt()

        // 开关已关闭：若已存在导航栏则移除，并恢复内容底部内边距
        if (!SpUtils.getBottomNavEnabled(this)) {
            if (existingNav != null) {
                content.removeView(existingNav)
                for (i in 0 until content.childCount) {
                    val child = content.getChildAt(i)
                    if (child.tag == TAG_BOTTOM_NAV) continue
                    child.setPadding(
                        child.paddingLeft,
                        child.paddingTop,
                        child.paddingRight,
                        (child.paddingBottom - navHeightPx).coerceAtLeast(0)
                    )
                }
            }
            return
        }

        // 已开启且已添加过则不再重复
        if (existingNav != null) return

        // 底部导航栏
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xEE000000.toInt())
            elevation = 12f
            tag = TAG_BOTTOM_NAV
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                navHeightPx
            )
        }

        // 为内容子视图增加底部内边距，避免遮挡列表末尾
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child.tag == TAG_BOTTOM_NAV) continue
            child.setPadding(
                child.paddingLeft,
                child.paddingTop,
                child.paddingRight,
                child.paddingBottom + navHeightPx
            )
        }

        content.addView(navBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            navHeightPx,
            Gravity.BOTTOM
        ))

        // 合并后的主容器：导航按钮直接驱动分页，不再启动 Activity
        addPagerNavButton(navBar, LanguageUtils.getString(this, R.string.nav_playlist), MainPagerActivity.PAGE_PLAYLIST, R.drawable.ic_nav_playlist)
        addPagerNavButton(navBar, LanguageUtils.getString(this, R.string.nav_songs), MainPagerActivity.PAGE_SONGS, R.drawable.ic_nav_songs)
        // 搜索按钮模式（search_mode=false）下搜索页不可达，同步隐藏该导航项，
        // 避免点了之后翻到一个被禁用的页
        if (SpUtils.getSearchMode(this)) {
            addPagerNavButton(navBar, LanguageUtils.getString(this, R.string.nav_search), MainPagerActivity.PAGE_SEARCH, R.drawable.ic_nav_search)
        }
        addPagerNavButton(navBar, LanguageUtils.getString(this, R.string.nav_settings), MainPagerActivity.PAGE_SETTINGS, R.drawable.ic_nav_settings)
    }

    /** 主容器内：底部导航点击平滑翻页（复用同一套样式） */
    /**
     * 导航项：图标 + 文字。
     *
     * 用 LinearLayout 而非 Button：需要在文字上方叠一个图标，
     * 而 Button 的 compoundDrawable 位置控制有限（难以精确居中并跟随高亮换色）。
     * 图标用 tint 着色，便于随选中状态切换；tag 存页索引供高亮逻辑识别。
     */
    private fun addPagerNavButton(navBar: LinearLayout, label: String, page: Int, iconRes: Int) {
        val self = this as MainPagerActivity
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            tag = page
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
            setOnClickListener {
                // 直接落到目标页：导航栏是显式目的地，逐页滚过去会让人等一段
                // 无意义的中间动画（尤其跨两页时），这里一律用无动画跳转。
                self.setPage(page, smooth = false)
                self.updateNavHighlight()
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            // 图标着色由 updateNavHighlight 统一处理
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22))
        }
        val text = TextView(this).apply {
            text = label
            textSize = 11f
            setAllCaps(false)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        item.addView(icon)
        item.addView(text)
        // 导航项不再是 Button，不会走 ViewUtils.applyScaleOnTouch 的按压缩放，
        // 这里显式补上，保持与其他按钮一致的按压反馈
        ViewUtils.applyScaleToButton(item)
        // 保存引用，供高亮时换色
        item.setTag(R.id.nav_icon_view, icon)
        item.setTag(R.id.nav_text_view, text)
        navBar.addView(item)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).roundToInt()

    companion object {
        const val TAG_BOTTOM_NAV = "bottom_nav_bar"
        /** 底部导航栏高度（dp） */
        const val NAV_HEIGHT_DP = 56
    }
}
