package com.tilixibiesi.data

import android.content.Context
import java.io.File

/**
 * 存储路径管理器：统一管理应用数据存放的根目录。
 *
 * 存储路径通过外部 am start 携带 extra（参数名 [EXTRA_STORAGE_ROOT]）传入并持久化：
 * - 两个预定义常用值：/sdcard/.Path（主存储，默认）与 /sdcard/Android/data/<包名>（内部存储）；
 * - 也接受任意非空路径。
 * - 启动时（[init]）优先恢复已持久化的路径，无记录则默认 /sdcard/.Path。
 * - 收到新的路径参数时调用 [applyNewRoot]：先把原根目录文件复制到新根目录（不覆盖目标已有文件），
 *   复制成功后切换并持久化当前根目录。
 *
 * 另支持按需复制单个文件：am start 携带 [EXTRA_COPY_FROM]（源文件）+ [EXTRA_COPY_TO]（目标路径）
 * 调用 [copyFileTo]，把源文件复制进应用创建的文件夹——用于把外部（如 shell 投放）的文件
 * 纳入本应用可自由读写的目录。
 */
object StoragePaths {
    /** extra 参数名：am start 传入的存储根路径 */
    const val EXTRA_STORAGE_ROOT = "storage_root"

    /** extra 参数名：am start 传入的待复制源文件路径 */
    const val EXTRA_COPY_FROM = "copy_from"

    /** extra 参数名：am start 传入的复制目标路径 */
    const val EXTRA_COPY_TO = "copy_to"

    /** 主存储路径（默认优先使用）：/sdcard/.Path */
    const val MAIN_ROOT = "/sdcard/.Path"

    /** 进程内当前根目录；启动时通过 [init] 从 SharedPreferences 恢复 */
    @Volatile
    var currentRoot: String = MAIN_ROOT
        private set

    /**
     * 应用启动时调用：恢复存储根。
     * 优先使用已持久化的任意路径；无记录则默认主存储路径 /sdcard/.Path。
     */
    fun init(context: Context) {
        currentRoot = SpUtils.getStorageRoot(context)?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?: MAIN_ROOT
    }

    /** 当前根目录 */
    fun root(): String = currentRoot

    /**
     * 解析相对路径对应的 File（总是当前根，用于读取）。
     * 由于当前只存在一个活跃根目录（迁移时文件已复制到新根），无需再回退到另一根。
     */
    fun resolveRead(relativePath: String): File = File(currentRoot, relativePath)

    /** 解析相对路径对应的 File（总是当前根，用于写入） */
    fun resolveWrite(relativePath: String): File = File(currentRoot, relativePath)

    /**
     * 应用一个新的存储根路径（例如通过 am start 传入的 extra）。
     * - 若 newRoot 非法或与当前根相同，不做任何操作；
     * - 若不同，先把原根目录下文件复制到新根目录（不覆盖目标已有文件），
     *   复制成功后切换当前根并持久化；删除原根目录仅为“尽力而为”，失败忽略。
     *
     * @return 是否成功应用（复制成功即成功）
     */
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
            // 尽力删除原根目录；失败（如用户改了所有者/权限）则忽略，不影响结果
            try {
                from.deleteRecursively()
            } catch (e: Exception) {
                // 原目录删除失败属预期，无需在意
            }
        }

        setRoot(context, normalized)
        return true
    }

    /** 直接设置当前根并持久化（不做迁移） */
    private fun setRoot(context: Context, newRoot: String) {
        currentRoot = newRoot
        SpUtils.saveStorageRoot(context, newRoot)
    }

    /**
     * 复制单个文件/目录到指定目标路径。
     * 供 am start 携带 copy_from / copy_to 参数调用：把源文件复制进应用创建的文件夹，
     * 以把外部（如 shell 投放）的文件纳入本应用可自由读写的目录。
     *
     * - 源为文件：把文件复制到目标目录（目标目录会自动创建，若目标已存在同名文件则覆盖）；
     * - 源为目录：把目录内容递归复制到目标目录（不覆盖目标已存在的文件）；
     * - 源不存在或失败返回 false。
     *
     * @return 是否复制成功
     */
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

    /** 规范化路径；接受任意非空路径（默认 /sdcard/.Path 与内部存储为两个预定义常用值），去掉末尾多余斜杠 */
    private fun normalizeRoot(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return path.trimEnd('/')
    }

    /**
     * 递归复制目录，目标已存在的文件不覆盖（保留用户自定义文件）。
     */
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