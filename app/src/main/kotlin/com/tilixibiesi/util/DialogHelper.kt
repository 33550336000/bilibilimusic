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
