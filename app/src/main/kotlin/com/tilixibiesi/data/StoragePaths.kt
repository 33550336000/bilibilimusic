package com.tilixibiesi.data

import android.util.Log

import android.content.Context
import java.io.File

object StoragePaths {

    private const val TAG = "StoragePaths"
    const val EXTRA_STORAGE_ROOT = "storage_root"

    const val EXTRA_COPY_FROM = "copy_from"

    const val EXTRA_COPY_TO = "copy_to"

    const val MAIN_ROOT = "/sdcard/.Path"

    @Volatile
    var currentRoot: String = MAIN_ROOT
        private set

    fun init(context: Context) {
        currentRoot = SpUtils.getStorageRoot(context)?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?: MAIN_ROOT
    }

    fun root(): String = currentRoot

    fun resolveRead(relativePath: String): File = File(currentRoot, relativePath)

    fun resolveWrite(relativePath: String): File = File(currentRoot, relativePath)

    /**
     * 切换存储根目录。
     *
     * 旧实现在复制失败时静默继续，然后无条件删除源目录——等于数据丢失。
     * 现在要求：复制过程中出现的任何失败都中止迁移、保留源目录；
     * 只有确认目标端文件数与源端一致后才删除源。
     */
    @Synchronized
    fun applyNewRoot(context: Context, newRoot: String): Boolean {
        val normalized = normalizeRoot(newRoot) ?: return false
        if (normalized == currentRoot) return true

        val from = File(currentRoot)
        val to = File(normalized)

        if (from.exists()) {
            val copied = try {
                copyRecursivelyNoOverwrite(from, to)
            } catch (e: Exception) {
                Log.d(TAG, "存储根目录迁移失败，保留原目录: ${e.message}")
                return false
            }
            if (!copied) {
                Log.d(TAG, "存储根目录迁移不完整，保留原目录")
                return false
            }
            val srcCount = countFiles(from)
            val dstCount = countFiles(to)
            if (srcCount < 0 || dstCount < srcCount) {
                Log.d(TAG, "迁移校验未通过（源 $srcCount / 目标 $dstCount），保留原目录")
                return false
            }
            runCatching { from.deleteRecursively() }
                .onFailure { Log.d(TAG, "删除旧存储目录失败: ${it.message}") }
        }

        setRoot(context, normalized)
        return true
    }

    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        if (dir.isFile) return 1
        val children = dir.listFiles() ?: return -1
        var count = 0
        for (child in children) {
            val n = countFiles(child)
            if (n < 0) return -1
            count += n
        }
        return count
    }

    private fun setRoot(context: Context, newRoot: String) {
        currentRoot = newRoot
        SpUtils.saveStorageRoot(context, newRoot)
    }

    fun copyFileTo(sourcePath: String, destPath: String): Boolean {
        val src = File(sourcePath)
        val dst = File(destPath)
        if (!src.exists()) return false
        return try {
            if (src.isFile) {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                true
            } else {
                copyRecursivelyNoOverwrite(src, dst)
            }
        } catch (e: Exception) {
            Log.d(TAG, "复制文件失败: ${e.message}")
            false
        }
    }

    private fun normalizeRoot(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return path.trimEnd('/')
    }

    private fun copyRecursivelyNoOverwrite(src: File, dst: File): Boolean {
        if (src.isDirectory) {
            val children = src.listFiles() ?: return false
            var ok = true
            for (child in children) {
                if (!copyRecursivelyNoOverwrite(child, File(dst, child.name))) ok = false
            }
            return ok
        }
        if (src.isFile) {
            if (dst.exists()) return true
            dst.parentFile?.mkdirs()
            return runCatching { src.copyTo(dst, overwrite = false); true }
                .onFailure { Log.d(TAG, "复制 ${src.name} 失败: ${it.message}") }
                .getOrDefault(false)
        }
        return true
    }
}