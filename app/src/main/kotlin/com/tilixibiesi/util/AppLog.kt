package com.tilixibiesi.util

import android.util.Log

object AppLog {

    private const val MAX_ENTRIES = 2000

    private const val DEDUP_WINDOW_MS = 3000L

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)

    private val lastSeen = HashMap<String, Long>()

    private val timeFormat = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    private class Entry(val time: Long, val tag: String, val message: String)

    fun d(tag: String, message: String): Int {
        record(tag, message)
        return Log.d(tag, message)
    }

    private fun record(tag: String, message: String) {

        synchronized(entries) {
            val now = System.currentTimeMillis()
            val key = tag + ": " + message
            val last = lastSeen[key]
            if (last != null && now - last < DEDUP_WINDOW_MS) return

            if (lastSeen.size >= MAX_ENTRIES) lastSeen.clear()
            lastSeen[key] = now
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(Entry(now, tag, message))
        }
    }

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

    fun defaultFileName(): String =
        "logcat-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".txt"
}
