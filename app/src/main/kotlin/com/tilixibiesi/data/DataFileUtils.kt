package com.tilixibiesi.data
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.model.PlaylistBean
import com.tilixibiesi.R
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.util.AtomicFileWriter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.*

object DataFileUtils {
    private val AUDIO_SUFFIXES: Set<String> = hashSetOf(
        ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a", ".opus", ".wma"
    )

    private const val APPDATA_REL = "system/axeron/long/Android/Appdata"
    private const val MUSIC_FILE_REL = "music.txt"
    private const val PLAYLIST_FILE_REL = "$APPDATA_REL/Playlist.txt"
    private const val DELETE_FILE_REL = "$APPDATA_REL/Delete.txt"
    private const val RENAME_FILE_REL = "$APPDATA_REL/change.txt"
    private const val BLOCKED_WORDS_FILE_REL = "$APPDATA_REL/blocked_words.txt"

    private fun relRead(rel: String): File = StoragePaths.resolveRead(rel)

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

    fun getDisplayName(rawName: String?): String {
        if (rawName == null) return ""
        renameMap[rawName]?.let { return it }
        return rawName.stripAudioExtension()
    }

    private fun String.stripAudioExtension(): String {
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
            writeRenameFile()
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
                writeRenameFile()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return removed
    }

    private fun writeRenameFile() {
        val sb = StringBuilder()
        renameMap.forEach { (k, v) -> sb.append(k).append('=').append(v).append('\n') }
        AtomicFileWriter.writeText(relWrite(RENAME_FILE_REL), sb.toString())
    }

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

    fun saveMusicList(list: List<MusicBean>) {
        try {
            val dir = relWrite(APPDATA_REL)
            if (!dir.exists()) dir.mkdirs()
            val arr = JSONArray()
            for (bean in list) {
                arr.put(musicBeanToJson(bean))
            }
            AtomicFileWriter.writeText(relWrite(MUSIC_FILE_REL), arr.toString())
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

    fun savePlaylists(playlists: List<PlaylistBean>) {
        try {
            val dir = relWrite(APPDATA_REL)
            if (!dir.exists()) dir.mkdirs()
            val arr = JSONArray()
            for (pl in playlists) {
                arr.put(playlistToJson(pl))
            }
            AtomicFileWriter.writeText(relWrite(PLAYLIST_FILE_REL), arr.toString())
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

    private fun musicBeanToJson(bean: MusicBean): JSONObject = JSONObject().apply {
        put("musicName", bean.musicName)
        put("musicUrl", bean.musicUrl)
        put("localPath", bean.localPath ?: "")
        put("isDownloaded", bean.isDownloaded)
        put("isBilibili", bean.isBilibili)
        put("bvid", bean.bvid ?: "")
        put("author", bean.author ?: "")
        put("duration", bean.duration)
        put("coverUrl", bean.coverUrl ?: "")
    }

    private fun jsonToMusicBean(obj: JSONObject): MusicBean =
        MusicBean(obj.getString("musicName"), obj.getString("musicUrl")).apply {
            localPath = obj.optString("localPath", "").ifEmpty { null }
            isDownloaded = obj.optBoolean("isDownloaded", false)
            isBilibili = obj.optBoolean("isBilibili", false)
            bvid = obj.optString("bvid", "").ifEmpty { null }
            author = obj.optString("author", "").ifEmpty { null }
            duration = obj.optInt("duration", 0)
            coverUrl = obj.optString("coverUrl", "").ifEmpty { null }
        }

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
            val sb = StringBuilder()
            words.forEach { sb.append(it).append('\n') }
            AtomicFileWriter.writeText(relWrite(BLOCKED_WORDS_FILE_REL), sb.toString())
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
