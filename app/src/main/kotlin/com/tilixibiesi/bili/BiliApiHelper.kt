package com.tilixibiesi.bili

object BiliApiHelper {
    fun isBiliUrl(url: String): Boolean {
        return url.contains("bilibili") || url.contains("bilivideo") ||
                url.contains("hdslb") || url.contains("upos")
    }
}
