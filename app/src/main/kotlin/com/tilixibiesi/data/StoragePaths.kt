package com.tilixibiesi.data

import android.content.Context
import java.io.File

object StoragePaths {
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

    @Synchronized
    fun applyNewRoot(context: Context, newRoot: String): Boolean {
        val normalized = normalizeRoot(newRoot) ?: return false
        if (normalized == currentRoot) return true

        val from = File(currentRoot)
        val to = File(normalized)

        if (from.exists()) {
            try {
                copyRecursivelyNoOverwrite(from, to)
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
            try {
                from.deleteRecursively()
            } catch (e: Exception) {
            }
        }

        setRoot(context, normalized)
        return true
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
            } else {
                copyRecursivelyNoOverwrite(src, dst)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun normalizeRoot(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return path.trimEnd('/')
    }

    private fun copyRecursivelyNoOverwrite(src: File, dst: File) {
        if (src.isDirectory) {
            val children = src.listFiles() ?: return
            for (child in children) {
                copyRecursivelyNoOverwrite(child, File(dst, child.name))
            }
        } else if (src.isFile) {
            if (!dst.exists()) {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = false)
            }
        }
    }
}