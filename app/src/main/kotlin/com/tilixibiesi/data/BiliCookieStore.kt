package com.tilixibiesi.data

import android.content.Context
import com.tilixibiesi.util.AtomicFileWriter
import java.io.File

object BiliCookieStore {

    private const val COOKIE_DIR_REL = "system/axeron/long/Android/Appdata/cookie"

    private const val COOKIE_FILE_REL = "$COOKIE_DIR_REL/bilibili.txt"

    fun cookieFile(): File = StoragePaths.resolveWrite(COOKIE_FILE_REL)

    fun get(context: Context): String {
        val file = cookieFile()
        val fromFile = if (file.exists()) {
            runCatching { file.readText().trim() }.getOrDefault("")
        } else {
            ""
        }
        if (fromFile.isNotEmpty()) return fromFile

        val legacy = SpUtils.readLegacyBiliCookie(context)
        if (legacy.isNotEmpty()) {
            save(context, legacy)
            return legacy
        }
        return ""
    }

    fun save(context: Context, cookie: String) {
        val value = cookie.trim()
        if (value.isEmpty()) {
            clear(context)
            return
        }

        val file = cookieFile()
        file.parentFile?.mkdirs()
        val written = AtomicFileWriter.writeText(file, value)
        if (written && file.exists()) {
            SpUtils.clearLegacyBiliCookie(context)
        } else {
            SpUtils.saveLegacyBiliCookie(context, value)
        }
    }

    fun clear(context: Context) {
        runCatching { cookieFile().delete() }
        SpUtils.clearLegacyBiliCookie(context)
    }

    fun has(context: Context): Boolean = get(context).isNotEmpty()
}
