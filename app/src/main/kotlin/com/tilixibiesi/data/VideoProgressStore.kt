package com.tilixibiesi.data

import android.util.Log

import com.tilixibiesi.util.AtomicFileWriter

import android.content.Context
import org.json.JSONObject
import java.io.File

object VideoProgressStore {

    private const val TAG = "VideoProgressStore"

    private const val FILE_NAME = "progress.json"

    private const val MAX_ENTRIES = 500

    private val lock = Any()

    private var file: File? = null

    private var loaded = false

    private var dirty = false

    private val positions = LinkedHashMap<String, Int>()

    private fun resolveFile(context: Context): File {
        file?.let { return it }
        val dir = File(context.getExternalFilesDir(null), "Saveprogress")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME).also { file = it }
    }

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
        } catch (e: Exception) { Log.d(TAG, "读取播放进度失败，从空表开始: ${e.message}") }
    }

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

    fun remove(context: Context, bvid: String) {
        if (bvid.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked(resolveFile(context))
            if (positions.remove(bvid) != null) dirty = true
        }
    }

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
