package com.tilixibiesi.util

import com.tilixibiesi.bili.BiliApiHelper
import com.tilixibiesi.bili.BiliCoverLoader
import com.tilixibiesi.bili.BiliSearchHelper
import com.tilixibiesi.data.AppPaths
import com.tilixibiesi.data.DataFileUtils
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.model.MusicBean
import com.tilixibiesi.R

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class CacheManager {

    companion object {
        private const val BILI_DIR_NAME = "bilibili"

        private const val QUALITY_INDEX_FILE = "quality_index.json"

        private val downloadingKeys = HashSet<String>()

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "mp4"
        )

        fun isBiliAudioFile(file: File): Boolean =
            file.parentFile?.name == BILI_DIR_NAME

        fun nameOf(file: File): String = file.name.substringBeforeLast('.')

        fun baseNameOf(bean: MusicBean): String {
            val actual = DataFileUtils.stripAudioExtension(bean.musicName)
            return actual.ifEmpty { bean.musicName }
        }

        fun isAudioFile(file: File): Boolean =
            file.isFile && !file.name.endsWith(".tmp") &&
                file.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

        fun listAudioFiles(): List<File> {
            val result = mutableListOf<File>()
            for (dir in arrayOf(AppPaths.audioCacheDir(), AppPaths.biliAudioCacheDir())) {
                if (!dir.isDirectory) continue
                dir.listFiles()?.forEach { if (isAudioFile(it)) result.add(it) }
            }
            return result.sortedWith(compareBy({ isBiliAudioFile(it) }, { it.name }))
        }

        fun findAudioFile(name: String): File? {
            val target = AppPaths.sanitizeName(name)
            if (target.isEmpty()) return null
            return listAudioFiles().firstOrNull { nameOf(it) == target }
        }

        fun audioFileName(baseName: String, extension: String): String =
            AppPaths.sanitizeName(baseName) + "." + extension.trimStart('.')
    }

    fun getCachedFilePath(musicBean: MusicBean): String? =
        findAudioFile(baseNameOf(musicBean))?.absolutePath

    fun startBackgroundCache(musicBean: MusicBean, context: Context, audioUrl: String) {
        if (!SpUtils.isAutoCacheEnabled(context)) return
        if (audioUrl.isEmpty() || !audioUrl.startsWith("http")) return
        if (musicBean.isDownloaded && musicBean.localPath != null &&
            File(musicBean.localPath!!).exists()
        ) return
        synchronized(downloadingKeys) {
            if (!downloadingKeys.add(musicBean.musicName)) return
        }

        val isBili = musicBean.isBilibili
        val dir = if (isBili) AppPaths.biliAudioCacheDir() else AppPaths.audioCacheDir()
        if (!dir.exists()) dir.mkdirs()

        val baseName = baseNameOf(musicBean)
        val notifyName = DataFileUtils.getDisplayName(musicBean.musicName)
            .ifEmpty { baseName }
        val notifyId = DownloadNotifier.notifyId(musicBean.musicName)
        DownloadNotifier.ensureChannel(context)
        DownloadNotifier.showProgress(
            context,
            notifyId,
            LanguageUtils.getString(context, R.string.download_downloading),
            notifyName,
            0
        )

        AppExecutors.io.execute {
            try {
                val qualityId = if (isBili) SpUtils.getBiliAudioQuality(context) else 0
                val existing = findAudioFile(baseName)?.takeIf { it.parentFile == dir }
                val extension = existing?.name?.substringAfterLast('.')
                    ?: audioExtension(audioUrl, isBili)
                val finalFile = File(dir, audioFileName(baseName, extension))

                if (finalFile.exists() && !needsRedownload(finalFile, isBili, qualityId)) {
                    musicBean.localPath = finalFile.absolutePath
                    DownloadNotifier.cancel(context, notifyId)
                    downloadCoverIfNeeded(musicBean)
                    return@execute
                }

                val tempFile = File(dir, finalFile.name + ".tmp")
                val ok = downloadToFile(context, audioUrl, tempFile, notifyId, notifyName)
                if (!ok) {
                    tempFile.delete()
                    DownloadNotifier.cancel(context, notifyId)
                    return@execute
                }
                if (finalFile.exists()) finalFile.delete()
                if (tempFile.renameTo(finalFile)) {
                    if (isBili) writeQuality(finalFile, qualityId)
                    musicBean.localPath = finalFile.absolutePath
                    DownloadNotifier.finish(
                        context,
                        musicBean.musicName,
                        LanguageUtils.getString(context, R.string.download_complete),
                        finalFile.name
                    )
                } else {
                    tempFile.delete()
                    DownloadNotifier.cancel(context, notifyId)
                }
                downloadCoverIfNeeded(musicBean)
            } catch (e: Exception) {
                e.printStackTrace()
                DownloadNotifier.cancel(context, notifyId)
            } finally {
                synchronized(downloadingKeys) {
                    downloadingKeys.remove(musicBean.musicName)
                }
            }
        }
    }

    private fun needsRedownload(file: File, isBili: Boolean, qualityId: Int): Boolean {
        if (!isBili) return false
        val recorded = readQualityIndex()[file.name] ?: return false
        return recorded != qualityId
    }

    private fun downloadToFile(
        context: Context,
        url: String,
        tempFile: File,
        notifyId: Int,
        displayName: String
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            conn.setRequestProperty("Accept", "*/*")
            if (BiliApiHelper.isBiliUrl(url)) {
                conn.setRequestProperty("Referer", "https://www.bilibili.com/")
                conn.setRequestProperty("Origin", "https://www.bilibili.com")
            }

            if (conn.responseCode != 200) return false

            val totalLength = conn.contentLength
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        output.write(buffer, 0, len)
                        downloaded += len
                        if (totalLength > 0) {
                            val progress = (downloaded * 100L / totalLength).toInt()
                            DownloadNotifier.showProgress(
                                context,
                                notifyId,
                                LanguageUtils.getString(context, R.string.download_downloading),
                                "$displayName $progress%",
                                progress
                            )
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun downloadCoverIfNeeded(musicBean: MusicBean) {
        if (!musicBean.isBilibili) return
        val bvid = musicBean.bvid?.takeIf { it.isNotEmpty() } ?: return
        val baseName = baseNameOf(musicBean)

        val coverUrl = BiliCoverLoader.highestQualityUrl(
            musicBean.coverUrl?.takeIf { it.isNotEmpty() }
                ?: BiliSearchHelper.getVideoDetail(bvid)?.coverUrl.orEmpty()
        )
        if (coverUrl.isEmpty()) return

        val dir = AppPaths.biliCoverDir()
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, audioFileName(baseName, coverExtension(coverUrl)))
        if (target.exists() && target.length() > 0) return

        val tempFile = File(dir, target.name + ".tmp")
        val ok = try {
            val conn = URL(coverUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Referer", "https://www.bilibili.com/")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                if (conn.responseCode == 200) {
                    conn.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    true
                } else false
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (e: Exception) {
            false
        }
        if (ok && tempFile.length() > 0) {
            if (target.exists()) target.delete()
            if (!tempFile.renameTo(target)) tempFile.delete()
        } else {
            tempFile.delete()
        }
    }

    fun localCoverFile(baseName: String): File =
        BiliCoverLoader.localCoverFile(baseName)

    fun biliCacheQualityMismatch(musicBean: MusicBean, qualityId: Int): Boolean {
        val file = findAudioFile(baseNameOf(musicBean)) ?: return false
        if (!isBiliAudioFile(file)) return false
        return readQualityIndex()[file.name] != qualityId
    }

    fun removeOutdatedBiliCache(musicBean: MusicBean, qualityId: Int): Boolean {
        val file = findAudioFile(baseNameOf(musicBean)) ?: return false
        if (!isBiliAudioFile(file)) return false
        val recorded = readQualityIndex()[file.name] ?: return false
        if (recorded == qualityId) return false
        if (!file.delete()) return false
        writeQualityIndex(readQualityIndex().toMutableMap().apply { remove(file.name) })
        return true
    }

    private fun readQualityIndex(): Map<String, Int> {
        val file = File(AppPaths.biliAudioCacheDir(), QUALITY_INDEX_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            val obj = JSONObject(file.readText())
            val map = mutableMapOf<String, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.optInt(key, -1)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeQuality(file: File, qualityId: Int) {
        val map = synchronized(qualityIndexLock) {
            readQualityIndex().toMutableMap().apply { put(file.name, qualityId) }
        }
        writeQualityIndex(map)
    }

    private fun writeQualityIndex(map: Map<String, Int>) {
        synchronized(qualityIndexLock) {
            try {
                val indexFile = File(AppPaths.biliAudioCacheDir(), QUALITY_INDEX_FILE)
                indexFile.parentFile?.mkdirs()
                val obj = JSONObject()
                map.forEach { (name, id) -> obj.put(name, id) }
                AtomicFileWriter.writeText(indexFile, obj.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun audioExtension(url: String, isBili: Boolean): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (isBili) return if (ext == "mp4") "mp4" else "m4a"
        return if (ext in AUDIO_EXTENSIONS) ext else "mp3"
    }

    private fun coverExtension(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png", "webp", "gif", "jpeg", "jpg" -> if (ext == "jpeg") "jpg" else ext
            else -> "jpg"
        }
    }

    private val qualityIndexLock = Any()
}
