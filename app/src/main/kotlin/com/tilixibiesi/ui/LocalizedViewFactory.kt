package com.tilixibiesi.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.tilixibiesi.data.LanguageUtils

/**
 * 布局本地化工厂（全局覆盖方案）。
 *
 * 背景：布局 XML 的 @string 引用在 inflate 时通过 ResourcesImpl（TypedArray）解析，
 * 不经过 Resources.getString/getText 虚方法，因此 LocalizedResources 的 JSON 覆盖
 * 对布局文本无效，只能拿到内置的简体中文。
 *
 * 本工厂在 LayoutInflater 创建每个 View 之后，读取该 View 的 android:text /
 * android:hint / android:contentDescription 所引用的字符串资源 id，用当前语言的
 * JSON 覆盖值替换，从而让「所有布局文本」随语言切换，无需逐处 findViewById。
 *
 * 安装在 Activity 的 LayoutInflater 上后可同时覆盖：
 *  - Activity 页面布局（setContentView）
 *  - Adapter 列表项（LayoutInflater.from(activity) 返回同一实例）
 *  - 代码 inflate 的弹窗布局
 *
 * 视图创建使用 LayoutInflater.createView(name, prefix, attrs)——该方法为纯反射创建，
 * 不会再次回调工厂，因此不存在递归；对短类名按系统默认前缀逐个尝试。
 */
class LocalizedViewFactory(
    private val inflater: LayoutInflater,
    private val delegate: LayoutInflater.Factory2?
) : LayoutInflater.Factory2 {

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** 短类名（无包名）时按系统默认顺序尝试的前缀 */
        private val PREFIXES = arrayOf(
            "android.widget.",
            "android.webkit.",
            "android.app.",
            "android.view."
        )

        /** 安装到指定 inflater；无法安装（已有工厂等）时安全忽略。 */
        fun install(inflater: LayoutInflater) {
            try {
                if (inflater.factory2 is LocalizedViewFactory) return
                inflater.factory2 = LocalizedViewFactory(inflater, inflater.factory2)
            } catch (_: Throwable) {
                // 布局文本将回退内置中文，不影响其它功能
            }
        }
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        // 已有工厂（OEM/插件）优先
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

    /** 用系统默认前缀规则反射创建视图。 */
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
                // 尝试下一个前缀
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
            // 单个属性失败不影响视图创建
        }
    }
}
