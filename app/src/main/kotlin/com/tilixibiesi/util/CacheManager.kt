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

        /**
         * `cache_map.json` 的进程级互斥锁。
         *
         * 必须是**静态**的（而不是 `@Synchronized` 实例方法）：本类的实例并不唯一 ——
         * [MusicPlayerService][com.tilixibiesi.service.MusicPlayerService] 持有一个长生命周期实例，
         * 而 `StorageDialogs.showCacheManager()` 每次打开弹窗都会 `new` 一个。
         * 两个实例锁各自的 this，等于没锁。
         *
         * 这个文件是典型的「读-改-写」共享状态：后台缓存下载线程
         * （[startBackgroundCache]）与 UI 线程（缓存管理弹窗的删除/清空）会并发地
         * 「读出整份 map → 改一个键 → 整份写回」。没有互斥时后写者会覆盖前写者的
         * 修改，表现为「刚缓存好的歌没出现在列表里」或「删掉的又回来了」。
         */
        private val cacheMapLock = Any()

        /**
         * 正在下载的 URL 集合，用于去重。
         *
         * 与 [cacheMapLock] 同理放在 companion：当前只有
         * [MusicPlayerService][com.tilixibiesi.service.MusicPlayerService] 的常驻实例会调用
         * [startBackgroundCache]，但本类并非单例（弹窗每次 `new` 一个），
         * 放在实例上会让「同一 URL 被两个实例各下一份」在将来成为可能。
         */
        private val downloadingUrls = HashSet<String>()
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 读取整份映射。加锁以拿到一致快照，不会读到「写了一半」的文件
     * （写入侧由 [AtomicFileWriter] 保证原子替换）。
     */
    @SuppressLint("SetWorldReadable")
    fun loadCacheMap(): MutableMap<String, String> = synchronized(cacheMapLock) {
        readCacheMapLocked()
    }

    /** 不加锁的读取，仅供已持有 [cacheMapLock] 的调用方使用。 */
    private fun readCacheMapLocked(): MutableMap<String, String> {
        val mapFile = File(cacheDir, CACHE_MAP_FILE)
        if (!mapFile.exists()) return mutableMapOf()
        return try {
            val json = mapFile.readText()
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.getString(key)
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    /**
     * 整份覆盖映射。保留此入口是为了兼容「清空」这类确实要整体替换的调用，
     * 但**增量修改请改用 [updateCacheMap]**，否则会覆盖掉并发写入。
     */
    fun saveCacheMap(map: Map<String, String>) {
        synchronized(cacheMapLock) {
            writeCacheMapLocked(map)
        }
    }

    /**
     * 在锁内完成「读 → 改 → 写」，是修改单个条目的**唯一正确姿势**。
     *
     * 调用方拿到的是锁内读出的最新 map，改完立刻写回，因此不会丢掉
     * 其它线程在此期间写入的条目。
     */
    fun updateCacheMap(update: (MutableMap<String, String>) -> Unit) {
        synchronized(cacheMapLock) {
            val map = readCacheMapLocked()
            update(map)
            writeCacheMapLocked(map)
        }
    }

    /** 不加锁的写入，仅供已持有 [cacheMapLock] 的调用方使用。 */
    private fun writeCacheMapLocked(map: Map<String, String>) {
        val mapFile = File(cacheDir, CACHE_MAP_FILE)
        try {
            AtomicFileWriter.writeText(mapFile, JSONObject(map).toString())
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
                    updateCacheMap { it[musicBean.musicName] = md5Name }
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
                        updateCacheMap { it[musicBean.musicName] = md5Name }
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
