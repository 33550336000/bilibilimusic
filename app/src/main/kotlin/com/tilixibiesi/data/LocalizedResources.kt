package com.tilixibiesi.data

import android.content.res.Resources

/**
 * 本地化资源包装器。
 *
 * 覆写 getText/getString（两者在 [Resources] 中非 final），
 * 先查当前语言已下载的 JSON 覆盖映射（按资源名 getResourceEntryName 定位），
 * 命中则返回翻译文本，否则回退到内置的简体中文资源（super）。
 *
 * 注意：布局 XML 的 @string 在 inflate 时走 ResourcesImpl（TypedArray），
 * 不经过这两个虚方法，因此布局文本由 LocalizedViewFactory 另行覆盖。
 */
@Suppress("DEPRECATION")
class LocalizedResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {

    override fun getText(id: Int): CharSequence {
        return LanguageUtils.lookupOverlay(this, id) ?: super.getText(id)
    }

    override fun getString(id: Int): String {
        return LanguageUtils.lookupOverlay(this, id) ?: super.getString(id)
    }
}
