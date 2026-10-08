package com.tilixibiesi.data
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.model.PlaylistBean

import android.content.Context

object PlaylistManager {
    fun getPlaylists(context: Context): MutableList<PlaylistBean> =
        DataFileUtils.loadPlaylists().toMutableList()

    fun savePlaylists(context: Context, list: List<PlaylistBean>) =
        DataFileUtils.savePlaylists(list)

    fun createPlaylist(context: Context, name: String): PlaylistBean {
        val list = getPlaylists(context)
        val id = System.currentTimeMillis().toString()
        val newPl = PlaylistBean(id, name)
        list.add(newPl)
        savePlaylists(context, list)
        FootprintUtils.recordPlaylistEvent("创建歌单:$name")
        return newPl
    }

    fun getPlaylistById(context: Context, id: String): PlaylistBean? {
        return findPlaylistRecursive(getPlaylists(context), id)
    }

    private fun findPlaylistRecursive(list: List<PlaylistBean>, id: String): PlaylistBean? {
        for (pl in list) {
            if (pl.id == id) return pl
            val found = findPlaylistRecursive(pl.subPlaylists, id)
            if (found != null) return found
        }
        return null
    }

    fun addMusicToPlaylist(context: Context, playlistId: String, music: MusicBean) {
        val list = getPlaylists(context)
        val pl = findPlaylistRecursive(list, playlistId) ?: return
        pl.addMusic(music)
        FootprintUtils.recordPlaylistEvent("添加了:${pl.name}:${music.musicName}")
        savePlaylists(context, list)
    }

    fun removeMusicFromPlaylist(context: Context, playlistId: String, music: MusicBean) {
        val list = getPlaylists(context)
        val pl = findPlaylistRecursive(list, playlistId) ?: return
        pl.musicList.remove(music)
        FootprintUtils.recordPlaylistEvent("删除了:${pl.name}:${music.musicName}")
        savePlaylists(context, list)
    }

    fun addSubPlaylist(context: Context, parentPlaylistId: String, name: String): PlaylistBean? {
        val list = getPlaylists(context)
        val parent = findPlaylistRecursive(list, parentPlaylistId) ?: return null
        val id = System.currentTimeMillis().toString()
        val newPl = PlaylistBean(id, name)
        parent.addSubPlaylist(newPl)
        savePlaylists(context, list)
        FootprintUtils.recordPlaylistEvent("在歌单「${parent.name}」中创建了子歌单:$name")
        return newPl
    }

    fun getSubPlaylists(context: Context, playlistId: String): List<PlaylistBean> {
        val playlist = getPlaylistById(context, playlistId)
        return playlist?.subPlaylists ?: emptyList()
    }

    fun deletePlaylist(context: Context, playlistId: String) {
        val list = getPlaylists(context)
        list.removeAll { it.id == playlistId }
        removePlaylistRecursive(list, playlistId)
        savePlaylists(context, list)
    }

    private fun removePlaylistRecursive(list: List<PlaylistBean>, id: String) {
        for (pl in list) {
            pl.subPlaylists.removeAll { it.id == id }
            removePlaylistRecursive(pl.subPlaylists, id)
        }
    }

    fun renamePlaylist(context: Context, playlistId: String, newName: String) {
        val list = getPlaylists(context)
        val pl = findPlaylistRecursive(list, playlistId) ?: return
        pl.name = newName
        savePlaylists(context, list)
    }
}
