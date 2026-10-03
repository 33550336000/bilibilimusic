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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MusicBean) return false
        return musicName == other.musicName
    }

    override fun hashCode(): Int = musicName.hashCode()
}