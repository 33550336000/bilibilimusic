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

/**
 * 统一样式的对话框构建工具。
 * 各 Activity 的 AlertDialog 统一走此方法，避免重复粘贴样式代码。
 *
 * @param builder 待弹出的 AlertDialog.Builder
 * @param makeMessageBoldItalic 是否将消息文字设为粗斜体
 * @param boldItalicAllViews 是否将对话框内全部 TextView 设为粗斜体
 * @param overrideListViewItemColors 是否将对话框内 ListView 的每一项文字颜色替换为全局字体色
 */
object DialogHelper {

    /**
     * 页面版重载：接受任意 Context（含 BasePage 这类 ContextWrapper），
     * 内部自动解包出宿主 Activity 后复用原实现，使页面代码无需改动即可复用样式。
     */
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
            // 极端兜底：拿不到 Activity 时仍保证对话框可用（仅缺自定义样式）
            builder.create().apply {
                // 只允许点击弹窗内的按钮关闭，点弹窗外部空白处不关闭
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
        // 默认主题（Theme.Material.*.Dialog / Window）带有
        // android:windowCloseOnTouchOutside=true，导致点击弹窗外部空白区域
        // 就会触发 Dialog.cancel() 关闭弹窗。这里统一关掉，
        // 使弹窗只能通过弹窗内的按钮（或返回键）关闭。
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // ListView 文字颜色替换必须在 show() 之前注册监听
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
                            setTextViewColorRecursive(v, fontColorOf(activity))
                            return v
                        }
                    }
                }
            }
        }

        dialog.show()

        val displayMetrics = activity.resources.displayMetrics
        val minDialogWidth = (displayMetrics.widthPixels * 0.85).toInt()
        dialog.window?.setLayout(minDialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)

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

        val fontColor = fontColorOf(activity)
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

    private fun fontColorOf(activity: Activity): Int = try {
        val dialogFont = SpUtils.getDialogFontColor(activity)
        if (dialogFont.isEmpty()) {
            Color.parseColor(SpUtils.getFontColor(activity))
        } else {
            Color.parseColor(dialogFont)
        }
    } catch (e: Exception) {
        Color.WHITE
    }

    /** 递归将非 EditText 子视图背景置为透明，保留对话框整体样式 */
    fun makeChildrenBackgroundTransparent(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is EditText && child.background != null && child.background !is ColorDrawable) {
                    // 保留 EditText 背景
                } else {
                    child.setBackgroundColor(Color.TRANSPARENT)
                }
                if (child is ViewGroup) {
                    makeChildrenBackgroundTransparent(child)
                }
            }
        }
    }

    /** 递归遍历视图树查找 ListView */
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

    /** 递归设置所有 TextView 文字颜色 */
    private fun setTextViewColorRecursive(view: View, color: Int) {
        if (view is TextView) {
            view.setTextColor(color)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setTextViewColorRecursive(view.getChildAt(i), color)
            }
        }
    }

    /** 递归设置视图树中所有 TextView 的字体样式 */
    /** 创建统一风格的加载中对话框（水平进度条 + 文字）。 */
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
