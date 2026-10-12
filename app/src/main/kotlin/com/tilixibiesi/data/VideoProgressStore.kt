package com.tilixibiesi.data

import com.tilixibiesi.util.AtomicFileWriter

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 视频播放进度的持久化存储。
 *
 * 早期实现是「一个视频一个 .progress 文件」，文件里只有几字节的播放位置，
 * 但每个文件在 ext4 上至少占用一个 4KB 块，看过的视频越多浪费越大
 * （实测 74 个文件内容合计 350 字节，却占用了 296KB）。
 *
 * 现在改为「内存 Map + 单个 JSON 文件」：
 *  - 读取走内存，不再每次打开视频都读一次磁盘；
 *  - 写入合并后统一落盘，由调用方控制节流节奏；
 *  - 无论看过多少视频，磁盘上始终只有一个文件，块占用恒定为 O(1)。
 *
 * 所有公开方法都通过同一把锁串行化，避免「读-改-写」互相覆盖。
 */
object VideoProgressStore {

    private const val FILE_NAME = "progress.json"

    /** 最多记住多少个视频的进度，超出后按最久未访问淘汰。 */
    private const val MAX_ENTRIES = 500

    private val lock = Any()

    private var file: File? = null

    private var loaded = false

    private var dirty = false

    /** 按最近访问排序：末尾最新，超出上限时从头部淘汰。 */
    private val positions = LinkedHashMap<String, Int>()

    private fun resolveFile(context: Context): File {
        file?.let { return it }
        val dir = File(context.getExternalFilesDir(null), "Saveprogress")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME).also { file = it }
    }

    /** 首次访问时把文件读进内存，之后所有读取都不再碰磁盘。必须在锁内调用。 */
    private fun ensureLoadedLocked(target: File) {
        if (loaded) return
        loaded = true
        if (!target.exists()) return
        try {
            val obj = JSONObject(target.readText())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optInt(key, -1)
                if (value > 0) positions[key] = value
            }
        } catch (_: Exception) {
            // 文件损坏时放弃旧数据，不影响播放
        }
    }

    /** 读取播放位置，未记录过返回 0。 */
    fun get(context: Context, bvid: String): Int = synchronized(lock) {
        if (bvid.isEmpty()) {
            0
        } else {
            ensureLoadedLocked(resolveFile(context))
            val value = positions.remove(bvid)
            if (value == null) {
                0
            } else {
                positions[bvid] = value
                value
            }
        }
    }

    /** 仅更新内存，不落盘；由 [flush] 统一写入。 */
    fun put(context: Context, bvid: String, position: Int) {
        if (bvid.isEmpty() || position <= 0) return
        synchronized(lock) {
            ensureLoadedLocked(resolveFile(context))
            positions.remove(bvid)
            positions[bvid] = position
            dirty = true
            while (positions.size > MAX_ENTRIES) {
                val oldest = positions.keys.firstOrNull() ?: break
                positions.remove(oldest)
            }
        }
    }

    /** 清除某个视频的进度，例如播放到结尾时。 */
    fun remove(context: Context, bvid: String) {
        if (bvid.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked(resolveFile(context))
            if (positions.remove(bvid) != null) dirty = true
        }
    }

    /**
     * 把内存中的进度写入文件。
     *
     * @param sync 暂停、退出、切集等「不可丢失」的时机传 true，写入前 fsync 落盘；
     *   周期性的常规保存传 false，避免频繁 fsync 带来的延迟与闪存磨损。
     */
    fun flush(context: Context, sync: Boolean = false): Boolean = synchronized(lock) {
        if (!dirty) {
            true
        } else {
            val json = JSONObject()
            for ((key, value) in positions) json.put(key, value)
            val ok = AtomicFileWriter.writeText(resolveFile(context), json.toString(), sync)
            if (ok) dirty = false
            ok
        }
    }
}
