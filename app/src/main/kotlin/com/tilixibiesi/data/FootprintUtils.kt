package com.tilixibiesi.data

import android.content.Context
import com.tilixibiesi.R
import java.io.FileWriter

object FootprintUtils {
    private const val BASE_DIR_REL = "system/axeron/long/Android/Appdata"
    private const val PLAYLIST_FOOTPRINT_FILE_REL = "$BASE_DIR_REL/Playlist List Footprints.txt"

    fun recordPlaylistEvent(event: String) = appendLine(PLAYLIST_FOOTPRINT_FILE_REL, event)

    fun readPlaylistFootprints(context: Context): String = readFootprint(context, PLAYLIST_FOOTPRINT_FILE_REL)

    fun clearPlaylistFootprints() = clearFootprint(PLAYLIST_FOOTPRINT_FILE_REL)

    private fun appendLine(relativePath: String, line: String) {
        ensureDir()
        try {
            FileWriter(StoragePaths.resolveWrite(relativePath), true).use { it.append("$line\n") }
        } catch (_: Exception) {
        }
    }

    private fun readFootprint(context: Context, relativePath: String): String {
        return try {
            val file = StoragePaths.resolveRead(relativePath)
            if (file.exists()) file.readText()
            else LanguageUtils.getString(context, R.string.no_record)
        } catch (_: Exception) {
            LanguageUtils.getString(context, R.string.read_failed)
        }
    }

    private fun clearFootprint(relativePath: String) {
        try {
            StoragePaths.resolveRead(relativePath).delete()
        } catch (_: Exception) {
        }
    }

    private fun ensureDir() {
        StoragePaths.resolveWrite(BASE_DIR_REL).mkdirs()
    }
}
