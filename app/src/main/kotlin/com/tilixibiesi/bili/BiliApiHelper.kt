package com.tilixibiesi.bili

object BiliApiHelper {
    /**
     * 判断某个 URL 是否属于 B 站音视频 CDN（这些地址必须带 Referer 才能访问）。
     *
     * 曾经只认 bilibili/bilivideo/hdslb/upos 四个关键字，但 B 站音频会下发第三方
     * 边缘节点（实测 `*.edge.mountaintoys.cn:4483`），关键字全不命中，于是播放器
     * 不加 Referer，请求被 CDN 直接 403，表现为「个别歌曲点开没声音/无法播放」。
     *
     * 现在改为：只要不是本机文件路径、且是 http(s) 网络地址，就按 B 站资源处理。
     * 加 Referer 对非 B 站地址无害（仅一个请求头），而漏加会导致整首歌放不出来。
     */
    fun isBiliUrl(url: String): Boolean {
        if (url.startsWith("/") || url.startsWith("file:")) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return true
    }
}
