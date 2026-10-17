package com.tilixibiesi.data

import android.content.Context
import com.tilixibiesi.util.AtomicFileWriter
import java.io.File

/**
 * B 站登录 Cookie 的本地文件存储。
 *
 * 文件位置：`<存储根目录>/system/axeron/long/Android/Appdata/cookie/bilibili.txt`
 * 存储根目录默认是 `/sdcard/.Path`，即
 * `/sdcard/.Path/system/axeron/long/Android/Appdata/cookie/bilibili.txt`。
 *
 * 设计要点：
 * 1. 登录成功后（搜索页抓到 SESSDATA / bili_jct）立刻写入该文件；
 * 2. 每次使用都从本地文件读取，不在内存里长期缓存，因此外部替换或删除该文件后立即生效；
 * 3. 只有文件不存在或内容为空时，才回退到旧版本存在 SharedPreferences 的记录，
 *    并顺手迁移到文件，之后不再依赖 SharedPreferences。
 */
object BiliCookieStore {

    private const val COOKIE_DIR_REL = "system/axeron/long/Android/Appdata/cookie"

    private const val COOKIE_FILE_REL = "$COOKIE_DIR_REL/bilibili.txt"

    fun cookieFile(): File = StoragePaths.resolveWrite(COOKIE_FILE_REL)

    /** 从本地文件加载 Cookie；文件缺失时回退并迁移旧版 SharedPreferences 记录。 */
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

    /** 保存 Cookie 到本地文件；空字符串等同于清除。 */
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
            // 文件已落盘，旧版 SharedPreferences 记录不再需要
            SpUtils.clearLegacyBiliCookie(context)
        } else {
            // 极端情况下（存储不可写）文件没写成，保留一份旧的存储方式兜底
            SpUtils.saveLegacyBiliCookie(context, value)
        }
    }

    fun clear(context: Context) {
        runCatching { cookieFile().delete() }
        SpUtils.clearLegacyBiliCookie(context)
    }

    fun has(context: Context): Boolean = get(context).isNotEmpty()
}
