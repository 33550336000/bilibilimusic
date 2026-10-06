package com.tilixibiesi.model

import java.io.Serializable

class MusicBean(
    var musicName: String,
    var musicUrl: String
) : Serializable {
    var localPath: String? = null
    var isDownloaded: Boolean = false
    var isPlaying: Boolean = false

    // B站扩展
    var isBilibili: Boolean = false
    var bvid: String? = null
    var author: String? = null
    var duration: Int = 0

    /**
     * 封面地址（B 站稿件的 `pic`）。
     *
     * 普通音乐没有封面来源，保持 null；正在播放页据此决定"显示封面还是占位图"。
     * 老的历史记录里可能没有这个字段，读出来是空串——此时页面会按 bvid
     * 现取一次（见 BiliVideoMetaHelper），仍拿不到才退到占位图。
     */
    var coverUrl: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MusicBean) return false
        return musicName == other.musicName
    }

    override fun hashCode(): Int = musicName.hashCode()
}