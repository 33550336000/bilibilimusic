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

    private val RECEIVER_EXPORTED = 2

    private val languageUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LanguageUtils.ACTION_LANGUAGE_UPDATED) {
                lastLanguageCode = LanguageUtils.getEffectiveLanguage(applicationContext)
                recreate()
            }
        }
    }

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
        LocalizedViewFactory.install(layoutInflater)
        LocalizedViewFactory.install(LayoutInflater.from(this))
        lastLanguageCode = LanguageUtils.getEffectiveLanguage(applicationContext)
        registerLanguageReceiver()
    }

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
        val currentLang = LanguageUtils.getEffectiveLanguage(applicationContext)
        if (currentLang != lastLanguageCode) {
            recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        val content = findViewById<ViewGroup>(android.R.id.content)
        ViewUtils.applyScaleOnTouch(content)
        setupBottomNav()
    }

    protected fun setupBottomNav() {
        if (this !is MainPagerActivity) return

        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val existingNav = content.findViewWithTag<View>(TAG_BOTTOM_NAV)
        val navHeightPx = (NAV_HEIGHT_DP * resources.displayMetrics.density).toInt()

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

        if (existingNav != null) return

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

        addPagerNavButton(navBar, LanguageUtils.getString(this, R.string.nav_playlist), MainPagerActivity.PAGE_PLAYLIST, R.drawable.ic_nav_playlist)
        addPagerNavButton(navBar, LanguageUtils.getString(this, R.string.nav_songs), MainPagerActivity.PAGE_SONGS, R.drawable.ic_nav_songs)
        if (SpUtils.getSearchMode(this)) {
            addPagerNavButton(navBar, LanguageUtils.getString(this, R.string.nav_search), MainPagerActivity.PAGE_SEARCH, R.drawable.ic_nav_search)
        }
        addPagerNavButton(navBar, LanguageUtils.getString(this, R.string.nav_settings), MainPagerActivity.PAGE_SETTINGS, R.drawable.ic_nav_settings)
    }

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
                self.setPage(page, smooth = false)
                self.updateNavHighlight()
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
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
        ViewUtils.applyScaleToButton(item)
        item.setTag(R.id.nav_icon_view, icon)
        item.setTag(R.id.nav_text_view, text)
        navBar.addView(item)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).roundToInt()

    companion object {
        const val TAG_BOTTOM_NAV = "bottom_nav_bar"
        const val NAV_HEIGHT_DP = 56
    }
}
