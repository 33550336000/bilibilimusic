package com.tilixibiesi.util

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

object AtomicFileWriter {

    private val tmpSeq = AtomicLong(0)

    private fun tmpFileFor(target: File): File =
        File(target.parentFile, "${target.name}.${tmpSeq.incrementAndGet()}.tmp")

    fun writeText(target: File, text: String): Boolean =
        write(target) { out -> out.write(text.toByteArray(Charsets.UTF_8)) }

    fun write(target: File, write: (OutputStream) -> Unit): Boolean {
        val dir = target.parentFile
        if (dir != null && !dir.exists() && !dir.mkdirs()) return false

        val tmp = tmpFileFor(target)
        return try {
            FileOutputStream(tmp).use { out ->
                write(out)
                out.flush()
            }
            if (!tmp.renameTo(target)) {
                if (tmp.exists()) {
                    tmp.copyTo(target, overwrite = true)
                }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }
}
