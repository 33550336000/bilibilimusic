package com.tilixibiesi.data

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tilixibiesi.util.AppExecutors
import java.io.File
import java.nio.file.Files

object WebViewMetricsCleaner {

    private const val DIR = "app_webview"

    private const val SHARED_PREFS_DIR = "shared_prefs"

    val APP_PREFS = arrayOf(
        "MusicPlayerPrefs.xml",
        "app_settings.xml",
        "language_prefs.xml"
    )
    private val KEEP = APP_PREFS.toSet()

    private const val INTERVAL_MS = 3_000L
    private const val MAX_ROUNDS = 24
    private const val IDLE_ROUNDS = 4
    private const val FIRST_DELAY_MS = 800L

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var roundsLeft = 0
    @Volatile private var idleRounds = 0

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<AppBackgroundListener>()

    fun addBackgroundListener(listener: AppBackgroundListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeBackgroundListener(listener: AppBackgroundListener) {
        listeners.remove(listener)
    }


    fun onForeground() {
        handler.removeCallbacksAndMessages(null)
        roundsLeft = 0
        idleRounds = 0
    }

    fun onBackground(app: Application) {
        for (listener in listeners) {
            runCatching { listener.onAppBackground() }
        }
        schedule(app, MAX_ROUNDS)
    }

    private fun schedule(app: Application, rounds: Int) {
        handler.removeCallbacksAndMessages(null)
        roundsLeft = rounds
        idleRounds = 0
        postNext(app, FIRST_DELAY_MS)
    }

    private fun postNext(app: Application, delayMs: Long) {
        if (roundsLeft <= 0 || idleRounds >= IDLE_ROUNDS) return
        handler.postDelayed({
            if (roundsLeft <= 0) return@postDelayed
            roundsLeft--
            AppExecutors.io.execute {
                val deleted = purge(app.applicationContext)
                handler.post {
                    if (roundsLeft <= 0) return@post
                    if (deleted) idleRounds = 0 else idleRounds++
                    postNext(app, INTERVAL_MS)
                }
            }
        }, delayMs)
    }


    fun purge(context: Context): Boolean {
        return try {
            val app = context.applicationContext
            var deleted = aggressiveDelete(File(app.applicationInfo.dataDir))
            if (purgeDeviceProtected(app)) deleted = true
            deleted
        } catch (_: Exception) {
            false
        }
    }

    private fun purgeDeviceProtected(app: Context): Boolean {
        var deleted = false
        val deContext = runCatching { app.createDeviceProtectedStorageContext() }.getOrNull() ?: return false
        for (dir in arrayOf(deContext.cacheDir, deContext.codeCacheDir)) {
            val children = dir?.listFiles() ?: continue
            for (child in children) {
                if (deleteNode(child)) deleted = true
            }
        }
        return deleted
    }

    private fun aggressiveDelete(dataDir: File): Boolean {
        val children = dataDir.listFiles() ?: return false
        var deleted = false
        for (child in children) {
            if (child.name == SHARED_PREFS_DIR) {
                if (sweepSharedPrefs(child)) deleted = true
                continue
            }
            if (isSymlink(child)) continue
            if (deleteNode(child)) deleted = true
        }
        return deleted
    }

    private fun sweepSharedPrefs(prefsDir: File): Boolean {
        val files = prefsDir.listFiles() ?: return false
        var deleted = false
        for (f in files) {
            if (f.isFile && f.name in KEEP) continue
            if (deleteNode(f)) deleted = true
        }
        return deleted
    }

    private fun deleteNode(node: File): Boolean {
        if (!node.exists()) return false
        if (node.isDirectory && !isSymlink(node)) {
            node.listFiles()?.forEach { deleteNode(it) }
        }
        node.delete()
        return !node.exists()
    }

    private fun isSymlink(f: File): Boolean =
        try {
            Files.isSymbolicLink(f.toPath())
        } catch (_: Exception) {
            false
        }

    fun purgeWebViewArtifacts(context: Context) {
        try {
            val app = context.applicationContext
            val dataDir = File(app.applicationInfo.dataDir)
            runCatching { deleteNode(File(dataDir, DIR)) }
            runCatching { sweepSharedPrefs(File(dataDir, SHARED_PREFS_DIR)) }
            runCatching { app.deleteDatabase("webview.db") }
            runCatching { app.deleteDatabase("webviewCache.db") }
            runCatching<Unit> {
                app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
        } catch (_: Exception) {
        }
    }
}

fun interface AppBackgroundListener {
    fun onAppBackground()
}
