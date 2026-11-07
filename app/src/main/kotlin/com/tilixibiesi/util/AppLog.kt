package com.tilixibiesi.util

import android.util.Log

/**
 * 应用内日志入口。
 *
 * 存在的理由：标准 SDK 没有任何把日志导出成文件的 API（android.util.Log 只写不读，
 * READ_LOGS 是 signature|privileged 权限），而拉起 /system/bin/logcat 子进程去读
 * 自己日志在多机型上并不可靠——实测同一时刻 root 能读十几万行、应用 uid 只读到
 * 寥寥数行且不含当前进程。所以这里在写日志的同时，把条目留一份在内存环形缓冲里，
 * 导出时直接从内存取，不依赖 logcat、不依赖 SELinux 策略、不依赖 root。
 *
 * 缓冲有固定上限，满了丢最旧；进程被杀即清空——这是内存方案的固有限制。
 * 每个条目仍会照常转发给 android.util.Log，logcat 里的表现不变。
 */
object AppLog {

    /** 环形缓冲容量。当前全仓日志量很低（只在异常/失败分支打点），2000 条足够。 */
    private const val MAX_ENTRIES = 2000

    /**
     * 同一条日志在这个间隔内只留一条。
     *
     * 有些 catch 位于逐帧绘制路径上（LiquidGlassLayout 的采样与 shader 绘制），
     * 一旦失败会每帧触发。不折叠的话几秒就能把缓冲刷满，把真正有用的日志挤出去。
     */
    private const val DEDUP_WINDOW_MS = 3000L

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)

    /** tag + message 的最近一次记录时刻，用于上面说的折叠。 */
    private val lastSeen = HashMap<String, Long>()

    private val timeFormat = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    private class Entry(val time: Long, val tag: String, val message: String)

    fun d(tag: String, message: String): Int {
        record(tag, message)
        return Log.d(tag, message)
    }

    private fun record(tag: String, message: String) {
        // 简单用锁：日志量低，争用可以忽略；synchronized 比并发容器更好保证
        // "判满丢弃" 与 "追加" 的原子性。
        synchronized(entries) {
            val now = System.currentTimeMillis()
            val key = tag + ": " + message
            val last = lastSeen[key]
            if (last != null && now - last < DEDUP_WINDOW_MS) return
            // 消息里可能拼了异常描述，key 不是有限集；满了直接清空重来，
            // 代价只是该轮折叠失效一次。
            if (lastSeen.size >= MAX_ENTRIES) lastSeen.clear()
            lastSeen[key] = now
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(Entry(now, tag, message))
        }
    }

    /**
     * 把内存中留存的全部日志写进给定输出流，每条一行，格式：
     * `MM-dd HH:mm:ss.SSS  D  tag: message`
     *
     * 直接写流而不落中转文件：导出走 SAF 的 openOutputStream，分享走管道型
     * ContentProvider，两者都不需要在磁盘上先存一份。
     *
     * @return 写出的条目数，0 表示内存里没有日志。
     */
    fun writeTo(out: java.io.OutputStream): Int {
        val snapshot: List<Entry>
        synchronized(entries) {
            snapshot = entries.toList()
        }
        val writer = out.bufferedWriter(Charsets.UTF_8)
        for (e in snapshot) {
            writer.write(timeFormat.format(java.util.Date(e.time)))
            writer.write("  D  ")
            writer.write(e.tag)
            writer.write(": ")
            writer.write(e.message)
            writer.write("\n")
        }
        writer.flush()
        return snapshot.size
    }

    fun isEmpty(): Boolean = synchronized(entries) { entries.isEmpty() }

    /** 导出文件名，例如 `logcat-20261107-083300.txt`。 */
    fun defaultFileName(): String =
        "logcat-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".txt"
}
