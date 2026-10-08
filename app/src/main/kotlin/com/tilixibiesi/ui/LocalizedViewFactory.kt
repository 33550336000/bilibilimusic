package com.tilixibiesi.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.tilixibiesi.data.LanguageUtils

class LocalizedViewFactory(
    private val inflater: LayoutInflater,
    private val delegate: LayoutInflater.Factory2?
) : LayoutInflater.Factory2 {

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        private val PREFIXES = arrayOf(
            "android.widget.",
            "android.webkit.",
            "android.app.",
            "android.view."
        )

        fun install(inflater: LayoutInflater) {
            try {
                if (inflater.factory2 is LocalizedViewFactory) return
                inflater.factory2 = LocalizedViewFactory(inflater, inflater.factory2)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        val fromDelegate = try {
            delegate?.onCreateView(parent, name, context, attrs)
        } catch (_: Throwable) {
            null
        }
        val view = fromDelegate ?: createView(name, attrs) ?: return null
        applyLocalizedAttrs(view, attrs)
        return view
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        return onCreateView(null, name, context, attrs)
    }

    private fun createView(name: String, attrs: AttributeSet): View? {
        if (name.contains('.')) {
            return try {
                inflater.createView(name, null, attrs)
            } catch (_: Throwable) {
                null
            }
        }
        for (prefix in PREFIXES) {
            try {
                inflater.createView(name, prefix, attrs)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun applyLocalizedAttrs(view: View, attrs: AttributeSet) {
        try {
            val res = view.resources
            val textId = attrs.getAttributeResourceValue(ANDROID_NS, "text", 0)
            if (textId != 0 && view is TextView) {
                LanguageUtils.lookupOverlay(res, textId)?.let { view.text = it }
            }
            val hintId = attrs.getAttributeResourceValue(ANDROID_NS, "hint", 0)
            if (hintId != 0 && view is TextView) {
                LanguageUtils.lookupOverlay(res, hintId)?.let { view.hint = it }
            }
            val descId = attrs.getAttributeResourceValue(ANDROID_NS, "contentDescription", 0)
            if (descId != 0) {
                LanguageUtils.lookupOverlay(res, descId)?.let { view.contentDescription = it }
            }
        } catch (_: Throwable) {
        }
    }
}
