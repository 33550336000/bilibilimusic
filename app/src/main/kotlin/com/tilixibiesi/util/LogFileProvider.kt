package com.tilixibiesi.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

import java.io.File

/**
 * 把 [LogExporter] 落盘的日志文件以 content:// 暴露出去，供 ACTION_SEND 分享。
 *
 * 项目没有依赖 androidx，用不了 FileProvider；而 API 24+ 的 EXTRA_STREAM 不能再用
 * file:// URI（FileUriExposedException），所以这里是最小的等价实现：
 * 只读、不导出、仅接受 filesDir/logs 下的文件，路径经 canonicalPath 校验防穿越。
 */
class LogFileProvider : ContentProvider() {

    companion object {
        private const val TAG = "LogFileProvider"

        private const val AUTHORITY_SUFFIX = ".logfiles"

        private const val MIME_TYPE = "text/plain"

        fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

        fun uriFor(context: Context, file: File): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(file.name)
            .build()
    }

    private fun resolve(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val dir = LogExporter.logDir(context ?: return null).canonicalFile
        val file = File(dir, name).canonicalFile
        if (file.parentFile != dir) {
            AppLog.d(TAG, "拒绝越权路径: $name")
            return null
        }
        return file
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") {
            AppLog.d(TAG, "只允许只读打开: $mode")
            return null
        }
        val file = resolve(uri) ?: return null
        if (!file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val columns = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        )
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
