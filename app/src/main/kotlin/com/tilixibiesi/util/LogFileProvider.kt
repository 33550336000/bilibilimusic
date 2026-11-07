package com.tilixibiesi.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

import java.io.OutputStream

/**
 * 把 [AppLog] 的内存日志以 content:// 暴露给 ACTION_SEND 分享。
 *
 * 不落中转文件：这里用一对管道（[ParcelFileDescriptor.createPipe]），接收方打开
 * 时在后台线程把内存快照直接写进管道读端。项目没有依赖 androidx，用不了
 * FileProvider；而 API 24+ 的 EXTRA_STREAM 不能再用 file:// URI，管道是最贴合的
 * 等价实现——只读、不落盘、无临时文件残留。
 */
class LogFileProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY_SUFFIX = ".logfiles"

        private const val MIME_TYPE = "text/plain"

        private const val TAG = "LogFileProvider"

        fun uriFor(context: Context, fileName: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(context.packageName + AUTHORITY_SUFFIX)
            .appendPath(fileName)
            .build()
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw UnsupportedOperationException("只支持只读打开: $mode")
        }
        if (AppLog.isEmpty()) {
            throw java.io.FileNotFoundException("内存中暂无日志")
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        // 写端交给后台线程，拿不到日志的接收方也不会阻塞 provider 调用线程。
        AppExecutors.io.execute {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(writeFd)
            try {
                AppLog.writeTo(out)
            } catch (e: Exception) {
                AppLog.d(TAG, "写日志管道失败: ${e.message}")
            } finally {
                runCatching { out.close() }
            }
        }
        return readFd
    }

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val name = uri.lastPathSegment ?: AppLog.defaultFileName()
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> name
                OpenableColumns.SIZE -> null
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
