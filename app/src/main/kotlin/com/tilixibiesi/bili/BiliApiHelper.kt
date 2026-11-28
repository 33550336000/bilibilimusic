package com.tilixibiesi.bili

object BiliApiHelper {

    fun isBiliUrl(url: String): Boolean {
        if (url.startsWith("/") || url.startsWith("file:")) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return true
    }
}
