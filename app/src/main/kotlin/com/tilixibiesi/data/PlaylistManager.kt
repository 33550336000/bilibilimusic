package com.tilixibiesi.data
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.model.PlaylistBean

import android.content.Context

object PlaylistManager {
    fun getPlaylists(context: Context): MutableList<PlaylistBean> =
        DataFileUtils.loadPlaylists().toMutableList()

    fun savePlaylists(context: Context, list: List<PlaylistBean>) =
        DataFileUtils.savePlaylists(list)

    // 创建顶级歌单
    fun createPlaylist(context: Context, name: String): PlaylistBean {
        val list = getPlaylists(context)
        val id = System.currentTimeMillis().toString()
        val newPl = PlaylistBean(id, name)
        list.add(newPl)
        savePlaylists(context, list)
        FootprintUtils.recordPlaylistEvent("创建歌单:$name")
        return newPl
    }

    /**
     * 递归查找指定 ID 的歌单（包括顶级和所有嵌套子歌单）
     */
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

    /**
     * 向指定歌单添加歌曲（支持子歌单）
     */
    fun addMusicToPlaylist(context: Context, playlistId: String, music: MusicBean) {
        val list = getPlaylists(context)
        val pl = findPlaylistRecursive(list, playlistId) ?: return
        pl.addMusic(music)
        FootprintUtils.recordPlaylistEvent("添加了:${pl.name}:${music.musicName}")
        savePlaylists(context, list)
    }

    /**
     * 从指定歌单移除歌曲（支持子歌单）
     */
    fun removeMusicFromPlaylist(context: Context, playlistId: String, music: MusicBean) {
        val list = getPlaylists(context)
        val pl = findPlaylistRecursive(list, playlistId) ?: return
        pl.musicList.remove(music)
        FootprintUtils.recordPlaylistEvent("删除了:${pl.name}:${music.musicName}")
        savePlaylists(context, list)
    }

    /**
     * 在父歌单中创建子歌单（父歌单可以是顶级或子歌单）
     */
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

    /**
     * 获取歌单的直接子歌单列表（不递归）
     */
    fun getSubPlaylists(context: Context, playlistId: String): List<PlaylistBean> {
        val playlist = getPlaylistById(context, playlistId)
        return playlist?.subPlaylists ?: emptyList()
    }

    fun deletePlaylist(context: Context, playlistId: String) {
        val list = getPlaylists(context)
        // 从顶级列表删除（如果它是顶级歌单）
        list.removeAll { it.id == playlistId }
        // 同时从所有子歌单列表中递归删除
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
