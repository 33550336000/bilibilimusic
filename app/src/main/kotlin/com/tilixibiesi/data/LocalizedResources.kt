package com.tilixibiesi.data

import android.content.res.Resources

@Suppress("DEPRECATION")
class LocalizedResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {

    override fun getText(id: Int): CharSequence {
        return LanguageUtils.lookupOverlay(this, id) ?: super.getText(id)
    }

    override fun getString(id: Int): String {
        return LanguageUtils.lookupOverlay(this, id) ?: super.getString(id)
    }
}
