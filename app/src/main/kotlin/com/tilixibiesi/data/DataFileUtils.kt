package com.tilixibiesi.data
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.model.PlaylistBean
import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.*

object DataFileUtils {
    /** 常见音频扩展名（含点、全小写）。用 HashSet 保证 O(1) 查找。 */
    private val AUDIO_SUFFIXES: Set<String> = hashSetOf(
        ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a", ".opus", ".wma"
    )

    // 相对路径（基于当前存储根目录 StoragePaths.root()）
    private const val APPDATA_REL = "system/axeron/long/Android/Appdata"
    private const val MUSIC_FILE_REL = "music.txt"
    private const val PLAYLIST_FILE_REL = "$APPDATA_REL/Playlist.txt"
    private const val DELETE_FILE_REL = "$APPDATA_REL/Delete.txt"
    private const val RENAME_FILE_REL = "$APPDATA_REL/change.txt"
    private const val BLOCKED_WORDS_FILE_REL = "$APPDATA_REL/blocked_words.txt"

    /** 读取用：优先当前根，读不到回退另一根（保留用户自定义/迁移前文件） */
    private fun relRead(rel: String): File = StoragePaths.resolveRead(rel)

    /** 写入用：总是当前根，并确保父目录存在 */
    private fun relWrite(rel: String): File =
        StoragePaths.resolveWrite(rel).apply { parentFile?.mkdirs() }

    private val renameMap = mutableMapOf<String, String>()

    fun initRenameMap() {
        renameMap.clear()
        val file = relRead(RENAME_FILE_REL)
        if (!file.exists()) return
        try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { line ->
                        val idx = line.indexOf('=')
                        if (idx > 0 && idx < line.length - 1) {
                            val original = line.substring(0, idx).trim()
                            val newName = line.substring(idx + 1).trim()
                            if (original.isNotEmpty() && newName.isNotEmpty()) {
                                renameMap[original] = newName
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 获取显示名称：优先使用用户自定义的重命名，否则去除常见音频后缀
     */
    fun getDisplayName(rawName: String?): String {
        if (rawName == null) return ""
        // 1. 查找重命名
        renameMap[rawName]?.let { return it }
        // 2. 去除音频扩展名
        return rawName.stripAudioExtension()
    }

    /**
     * 去掉常见的音频文件后缀（不区分大小写）
     */
    private fun String.stripAudioExtension(): String {
        // 扩展名只可能出现在最后一段：先取出再查常量集合。
        // 原先对 8 个后缀逐个 endsWith（且每次调用都新建 List），
        // 而本函数是列表渲染热路径——每次 getView、每次按播放名定位
        // 列表项都会调用，200 首歌规模下一次切页要跑几百次。
        val dot = lastIndexOf('.')
        if (dot < 0) return this
        val ext = substring(dot).lowercase()
        return if (ext in AUDIO_SUFFIXES) substring(0, dot) else this
    }

    fun saveRenameEntry(originalName: String?, newDisplayName: String?) {
        if (originalName == null || newDisplayName == null) return
        renameMap[originalName] = newDisplayName
        try {
            val dir = relWrite(APPDATA_REL)
            if (!dir.exists()) dir.mkdirs()
            OutputStreamWriter(FileOutputStream(relWrite(RENAME_FILE_REL)), Charsets.UTF_8).use { writer ->
                renameMap.forEach { (k, v) ->
                    writer.write("$k=$v\n")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removeRenameEntry(originalName: String): Boolean {
        val removed = renameMap.remove(originalName) != null
        if (removed) {
            try {
                val dir = relWrite(APPDATA_REL)
                if (!dir.exists()) dir.mkdirs()
                OutputStreamWriter(FileOutputStream(relWrite(RENAME_FILE_REL)), Charsets.UTF_8).use { writer ->
                    renameMap.forEach { (k, v) ->
                        writer.write("$k=$v\n")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return removed
    }
    // ========== 以下方法保持不变 ==========

    fun loadDeletedMusicNames(): HashSet<String> {
        val deleted = HashSet<String>()
        try {
            val file = relRead(DELETE_FILE_REL)
            if (!file.exists()) return deleted
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { deleted.add(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return deleted
    }

    fun saveDeletedMusicName(musicName: String) {
        try {
            val dir = relWrite(APPDATA_REL)
            if (!dir.exists()) dir.mkdirs()
            OutputStreamWriter(FileOutputStream(relWrite(DELETE_FILE_REL), true), Charsets.UTF_8).use { writer ->
                writer.write("$musicName\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveDeletedMusicNameSafe(musicName: String, context: Context): Boolean {
        for (keyword in ProtectedWords.KEYWORDS) {
            if (musicName.contains(keyword)) {
                Handler(Looper.getMainLooper()).post {
                    AlertDialog.Builder(context)
                        .setTitle(LanguageUtils.getString(context, R.string.op_restricted))
                        .setMessage(LanguageUtils.getString(context, R.string.delete_restricted_song, musicName))
                        .setPositiveButton(LanguageUtils.getString(context, R.string.confirm), null)
                        .setNegativeButton(LanguageUtils.getString(context, R.string.confirm), null)
                        .create()
                        .apply {
                            // 只允许点弹窗内按钮关闭，点弹窗外部空白处不关闭
                            setCanceledOnTouchOutside(false)
                            show()
                        }
                }
                return false
            }
        }
        saveDeletedMusicName(musicName)
        return true
    }

    // ========== 音乐列表序列化（包含B站字段） ==========
    fun saveMusicList(list: List<MusicBean>) {
        try {
            val dir = relWrite(APPDATA_REL)
            if (!dir.exists()) dir.mkdirs()
            val arr = JSONArray()
            for (bean in list) {
                arr.put(musicBeanToJson(bean))
            }
            OutputStreamWriter(FileOutputStream(relWrite(MUSIC_FILE_REL)), Charsets.UTF_8).use { writer ->
                writer.write(arr.toString())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadMusicList(): List<MusicBean> {
        val list = mutableListOf<MusicBean>()
        try {
            val file = relRead(MUSIC_FILE_REL)
            if (!file.exists()) return list
            val jsonStr = BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).readText()
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(jsonToMusicBean(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    // ========== 歌单序列化（递归，包含B站字段） ==========
    fun savePlaylists(playlists: List<PlaylistBean>) {
        try {
            val dir = relWrite(APPDATA_REL)
            if (!dir.exists()) dir.mkdirs()
            val arr = JSONArray()
            for (pl in playlists) {
                arr.put(playlistToJson(pl))
            }
            OutputStreamWriter(FileOutputStream(relWrite(PLAYLIST_FILE_REL)), Charsets.UTF_8).use { writer ->
                writer.write(arr.toString())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playlistToJson(pl: PlaylistBean): JSONObject {
        val obj = JSONObject()
        obj.put("id", pl.id)
        obj.put("name", pl.name)
        val musicArr = JSONArray()
        for (m in pl.musicList) {
            musicArr.put(musicBeanToJson(m))
        }
        obj.put("musicList", musicArr)
        val subArr = JSONArray()
        for (sub in pl.subPlaylists) {
            subArr.put(playlistToJson(sub))
        }
        obj.put("subPlaylists", subArr)
        return obj
    }

    fun loadPlaylists(): List<PlaylistBean> {
        val list = mutableListOf<PlaylistBean>()
        try {
            val file = relRead(PLAYLIST_FILE_REL)
            if (!file.exists()) return list
            val jsonStr = BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).readText()
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val plObj = arr.getJSONObject(i)
                list.add(jsonToPlaylist(plObj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun jsonToPlaylist(obj: JSONObject): PlaylistBean {
        val pl = PlaylistBean(obj.getString("id"), obj.getString("name"))
        val musicArr = obj.getJSONArray("musicList")
        for (j in 0 until musicArr.length()) {
            pl.musicList.add(jsonToMusicBean(musicArr.getJSONObject(j)))
        }
        if (obj.has("subPlaylists")) {
            val subArr = obj.getJSONArray("subPlaylists")
            for (k in 0 until subArr.length()) {
                val subObj = subArr.getJSONObject(k)
                pl.subPlaylists.add(jsonToPlaylist(subObj))
            }
        }
        return pl
    }

    // ========== MusicBean JSON 编解码（供音乐列表与歌单复用） ==========
    private fun musicBeanToJson(bean: MusicBean): JSONObject = JSONObject().apply {
        put("musicName", bean.musicName)
        put("musicUrl", bean.musicUrl)
        put("localPath", bean.localPath ?: "")
        put("isDownloaded", bean.isDownloaded)
        // B站扩展字段
        put("isBilibili", bean.isBilibili)
        put("bvid", bean.bvid ?: "")
        put("author", bean.author ?: "")
        put("duration", bean.duration)
    }

    private fun jsonToMusicBean(obj: JSONObject): MusicBean =
        MusicBean(obj.getString("musicName"), obj.getString("musicUrl")).apply {
            localPath = obj.optString("localPath", "").ifEmpty { null }
            isDownloaded = obj.optBoolean("isDownloaded", false)
            // 读取B站扩展字段（兼容旧数据）
            isBilibili = obj.optBoolean("isBilibili", false)
            bvid = obj.optString("bvid", "").ifEmpty { null }
            author = obj.optString("author", "").ifEmpty { null }
            duration = obj.optInt("duration", 0)
        }

    // ========== 屏蔽字管理 ==========
    fun loadBlockedWords(): HashSet<String> {
        val words = HashSet<String>()
        try {
            val file = relRead(BLOCKED_WORDS_FILE_REL)
            if (!file.exists()) return words
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { words.add(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return words
    }

    fun saveBlockedWords(words: Collection<String>) {
        try {
            val dir = relWrite(APPDATA_REL)
            if (!dir.exists()) dir.mkdirs()
            OutputStreamWriter(FileOutputStream(relWrite(BLOCKED_WORDS_FILE_REL)), Charsets.UTF_8).use { writer ->
                words.forEach { writer.write("$it\n") }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addBlockedWord(word: String) {
        val words = loadBlockedWords()
        words.add(word.trim())
        saveBlockedWords(words)
    }

    fun removeBlockedWord(word: String) {
        val words = loadBlockedWords()
        words.remove(word.trim())
        saveBlockedWords(words)
    }
}
