package com.tilixibiesi.model

import java.io.Serializable

class PlaylistBean(
    var id: String,
    var name: String
) : Serializable {
    val musicList: MutableList<MusicBean> = mutableListOf()
    val subPlaylists: MutableList<PlaylistBean> = mutableListOf()   // 子歌单列表

    fun addMusic(bean: MusicBean) {
        if (musicList.none { it.musicName == bean.musicName }) {
            musicList.add(bean)
        }
    }

    fun addSubPlaylist(sub: PlaylistBean) {
        subPlaylists.add(sub)
    }
}