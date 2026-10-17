package com.tilixibiesi.util
import com.tilixibiesi.R
import com.tilixibiesi.data.SpUtils

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

object DialogHelper {

    fun createStyledDialog(
        context: Context,
        builder: AlertDialog.Builder,
        makeMessageBoldItalic: Boolean = false,
        boldItalicAllViews: Boolean = false,
        overrideListViewItemColors: Boolean = false
    ): AlertDialog {
        val activity = ContextUtils.unwrapActivity(context)
        return if (activity != null) {
            createStyledDialog(
                activity, builder, makeMessageBoldItalic,
                boldItalicAllViews, overrideListViewItemColors
            )
        } else {
            builder.create().apply {
                setCanceledOnTouchOutside(false)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                show()
            }
        }
    }

    fun createStyledDialog(
        activity: Activity,
        builder: AlertDialog.Builder,
        makeMessageBoldItalic: Boolean = false,
        boldItalicAllViews: Boolean = false,
        overrideListViewItemColors: Boolean = false
    ): AlertDialog {
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (overrideListViewItemColors) {
            dialog.setOnShowListener {
                dialog.window?.decorView?.post {
                    val listView = findListView(dialog.window?.decorView) ?: return@post
                    val original = listView.adapter ?: return@post
                    listView.adapter = object : BaseAdapter() {
                        override fun getCount(): Int = original.count
                        override fun getItem(position: Int): Any = original.getItem(position)
                        override fun getItemId(position: Int): Long = original.getItemId(position)
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val v = original.getView(position, convertView, parent)
                            setTextViewColorRecursive(v, fontColor(activity))
                            return v
                        }
                    }
                }
            }
        }

        dialog.show()

        applyAdaptiveWidth(dialog, activity, 0.85f)

        val displayMetrics = activity.resources.displayMetrics

        val parentPanelId = activity.resources.getIdentifier("parentPanel", "id", "android")
        val parentPanel = if (parentPanelId != 0) {
            dialog.findViewById<View>(parentPanelId)
        } else {
            (dialog.window?.decorView as? ViewGroup)?.getChildAt(0)
        }

        val dialogBgColor = try {
            Color.parseColor(SpUtils.getDialogBgColor(activity))
        } catch (e: Exception) {
            Color.WHITE
        }
        val dialogAlpha = SpUtils.getDialogAlpha(activity) / 100f
        val bgAlphaColor = Color.argb(
            (dialogAlpha * 255).toInt(),
            Color.red(dialogBgColor),
            Color.green(dialogBgColor),
            Color.blue(dialogBgColor)
        )

        parentPanel?.apply {
            background = GradientDrawable().apply {
                setColor(bgAlphaColor)
                cornerRadius = 16f * displayMetrics.density
            }
            clipToOutline = true
            makeChildrenBackgroundTransparent(this)
        }

        val fontColor = fontColor(activity)
        dialog.findViewById<TextView>(
            activity.resources.getIdentifier("alertTitle", "id", "android")
        )?.setTextColor(fontColor)

        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(fontColor)
            if (makeMessageBoldItalic) {
                setTypeface(typeface, Typeface.BOLD_ITALIC)
            }
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(fontColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(fontColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(fontColor)

        if (boldItalicAllViews) {
            dialog.window?.decorView?.let { setTypefaceRecursive(it, Typeface.defaultFromStyle(Typeface.BOLD_ITALIC)) }
        }

        return dialog
    }

    /**
     * 弹窗字体色：设置里选了「弹窗专用字体色」就用它，否则跟随全局字体色。
     * 全局字体色复用 [FontUtils.color]，非法值回退到默认色。
     */
    fun fontColor(context: Context): Int = try {
        val dialogFont = SpUtils.getDialogFontColor(context)
        if (dialogFont.isEmpty()) FontUtils.color(context) else Color.parseColor(dialogFont)
    } catch (_: Exception) {
        Color.parseColor(SpUtils.DEFAULT_FONT_COLOR)
    }

    /**
     * 让弹窗宽度跟随当前可用宽度，而不是在创建时按像素写死。
     *
     * MainPagerActivity 声明了 configChanges="orientation|screenSize"，旋转时不会重建
     * Activity，已显示的弹窗会跨旋转存活。若只在创建时调用一次 setLayout，弹窗会一直
     * 沿用旧屏幕的宽度：横屏创建后在竖屏下溢出，竖屏创建后在横屏下过窄。
     *
     * 这里给 decorView 挂布局监听，每次布局都按当前宽度重算；用 attributes.width 比较
     * 来截断 setLayout 自身触发的回调，避免死循环。
     */
    fun applyAdaptiveWidth(dialog: AlertDialog, context: Context, fraction: Float) {
        val activity = ContextUtils.unwrapActivity(context) ?: return
        val window = dialog.window ?: return
        val targetWidth = { currentWindowWidthPx(activity, fraction) }

        window.setLayout(targetWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val target = targetWidth()
            if (window.attributes?.width != target) {
                window.setLayout(target, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    /** 取当前窗口可用宽度；用 WindowMetrics 以兼容分屏与自由窗口缩放。 */
    private fun currentWindowWidthPx(activity: Activity, fraction: Float): Int {
        val fullWidth = activity.windowManager.currentWindowMetrics.bounds.width()
        return (fullWidth * fraction).toInt()
    }

    fun makeChildrenBackgroundTransparent(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is EditText && child.background != null && child.background !is ColorDrawable) {
                } else {
                    child.setBackgroundColor(Color.TRANSPARENT)
                }
                if (child is ViewGroup) {
                    makeChildrenBackgroundTransparent(child)
                }
            }
        }
    }

    private fun findListView(view: View?): ListView? {
        if (view == null) return null
        if (view is ListView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findListView(view.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }

    private fun setTextViewColorRecursive(view: View, color: Int) {
        if (view is TextView) {
            view.setTextColor(color)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setTextViewColorRecursive(view.getChildAt(i), color)
            }
        }
    }

    fun createLoadingDialog(context: Context, message: String): AlertDialog {
        val progressBar = ProgressBar(context).apply { isIndeterminate = true }
        val textView = TextView(context).apply {
            text = message
            textSize = 16f
            setPadding(32, 32, 32, 16)
            setTextColor(Color.BLACK)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(progressBar)
            addView(textView)
        }
        return AlertDialog.Builder(context, R.style.TransparentDialog)
            .setView(layout)
            .setCancelable(false)
            .create()
            .apply { window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
    }

    fun setTypefaceRecursive(view: View, typeface: Typeface) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setTypefaceRecursive(view.getChildAt(i), typeface)
            }
        } else if (view is TextView) {
            view.typeface = typeface
        }
    }
}
