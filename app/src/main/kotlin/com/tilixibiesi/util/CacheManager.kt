package com.tilixibiesi.util

import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.model.MusicBean

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class CacheManager(private val cacheDir: String) {
    companion object {
        private const val CACHE_MAP_FILE = "cache_map.json"
    }

    private val downloadingUrls = HashSet<String>()

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    @SuppressLint("SetWorldReadable")
    fun loadCacheMap(): MutableMap<String, String> {
        val mapFile = File(cacheDir, CACHE_MAP_FILE)
        if (!mapFile.exists()) return mutableMapOf()
        try {
            val json = mapFile.readText()
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.getString(key)
            }
            return map
        } catch (e: Exception) {
            return mutableMapOf()
        }
    }

    fun saveCacheMap(map: Map<String, String>) {
        val mapFile = File(cacheDir, CACHE_MAP_FILE)
        try {
            mapFile.writeText(JSONObject(map).toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCachedFilePath(musicBean: MusicBean): String? {
        val map = loadCacheMap()
        // 1. 先查映射（无论 musicUrl 是否为空）
        val md5Name = map[musicBean.musicName]
        if (md5Name != null) {
            val file = File(cacheDir, md5Name)
            if (file.exists()) return file.absolutePath
        }
        // 2. 再用 musicUrl 做后备查找（此时才需要判空）
        if (musicBean.musicUrl.isNotEmpty()) {
            val urlMd5 = md5(musicBean.musicUrl)
            val directFile = File(cacheDir, urlMd5)
            if (directFile.exists()) return directFile.absolutePath
        }
        return null
    }

    fun startBackgroundCache(musicBean: MusicBean, context: Context) {
        if (!SpUtils.isAutoCacheEnabled(context)) return

        val url = musicBean.musicUrl
        if (url.isEmpty()) return
        if (musicBean.isDownloaded && musicBean.localPath != null && File(musicBean.localPath!!).exists()) return
        synchronized(downloadingUrls) {
            if (downloadingUrls.contains(url)) return
            downloadingUrls.add(url)
        }
        val cacheDirFile = File(cacheDir)
        if (!cacheDirFile.exists()) cacheDirFile.mkdirs()

        // 通知：让用户知道自动下载正在进行
        val musicName = musicBean.musicName
        val notifyId = DownloadNotifier.notifyId(musicName)
        DownloadNotifier.ensureChannel(context)
        DownloadNotifier.showProgress(
            context,
            notifyId,
            com.tilixibiesi.data.LanguageUtils.getString(context, com.tilixibiesi.R.string.download_downloading),
            musicName,
            0
        )

        AppExecutors.io.execute {
            try {
                val md5Name = md5(url)
                val tempFile = File(cacheDirFile, "$md5Name.tmp")
                val finalFile = File(cacheDirFile, md5Name)
                if (finalFile.exists()) {
                    val map = loadCacheMap()
                    map[musicBean.musicName] = md5Name
                    saveCacheMap(map)
                    DownloadNotifier.cancel(context, notifyId)
                    return@execute
                }
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.requestMethod = "GET"

                // 设置通用请求头（所有链接都适用）
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                conn.setRequestProperty("Accept", "*/*")

                // B 站链接必须带 Referer，否则 CDN 直接 403（已实测）。
                // 不再附带登录 Cookie：主页面播放与自动缓存都走无登录态链路。
                if (url.contains("bilibili") || url.contains("bilivideo") || url.contains("hdslb") || url.contains("upos")) {
                    conn.setRequestProperty("Referer", "https://www.bilibili.com/")
                    conn.setRequestProperty("Origin", "https://www.bilibili.com")
                }

                val code = conn.responseCode

                if (code == 200) {
                    val totalLength = conn.contentLength
                    var downloaded = 0L
                    conn.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (input.read(buffer).also { len = it } != -1) {
                                output.write(buffer, 0, len)
                                downloaded += len
                                if (totalLength > 0) {
                                    val progress = (downloaded * 100L / totalLength).toInt()
                                    DownloadNotifier.showProgress(
                                        context,
                                        notifyId,
                                        com.tilixibiesi.data.LanguageUtils.getString(context, com.tilixibiesi.R.string.download_downloading),
                                        "$musicName $progress%",
                                        progress
                                    )
                                }
                            }
                        }
                    }
                    if (tempFile.renameTo(finalFile)) {
                        val map = loadCacheMap()
                        map[musicBean.musicName] = md5Name
                        saveCacheMap(map)
                        musicBean.localPath = finalFile.absolutePath
                        // 下载结束：先清掉下载中的进度通知，再发一条完成通知
                        DownloadNotifier.finish(
                            context,
                            musicName,
                            com.tilixibiesi.data.LanguageUtils.getString(context, com.tilixibiesi.R.string.download_complete),
                            musicName
                        )
                    } else {
                        tempFile.delete()
                        DownloadNotifier.cancel(context, notifyId)
                    }
                } else {
                    DownloadNotifier.cancel(context, notifyId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                DownloadNotifier.cancel(context, notifyId)
            } finally {
                synchronized(downloadingUrls) {
                    downloadingUrls.remove(url)
                }
            }
        }
    }
}
