package com.tilixibiesi.data

import java.io.File

object AppPaths {
    fun mp4DownloadDir(): File = StoragePaths.resolveWrite("system/axeron/long/Android/mp4/")

    fun clickFxDir(): File = StoragePaths.resolveWrite("system/axeron/long/Android/BA/")

    const val CLICK_FX_FILE = "ba.html"

    fun subtitleDir(): File = StoragePaths.resolveWrite("system/axeron/long/Android/subtitle/")

    const val CLICK_FX_URL = "https://language.tibao.dpdns.org/ba.html"

    fun clickFxFile(): File = File(clickFxDir(), CLICK_FX_FILE)
}