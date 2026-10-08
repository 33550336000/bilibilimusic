package com.tilixibiesi.data

import com.tilixibiesi.service.MusicPlayerService
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.R
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PlaybackStatsManager {
    private val handler = Handler(Looper.getMainLooper())
    private var updaterRunnable: Runnable? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    private var tvToday: WeakReference<TextView>? = null
    private var tvTotal: WeakReference<TextView>? = null

    fun initViews(todayView: TextView, totalView: TextView) {
        tvToday = WeakReference(todayView)
        tvTotal = WeakReference(totalView)
    }

    fun releaseViews() {
        tvToday?.clear()
        tvTotal?.clear()
        tvToday = null
        tvTotal = null
    }

    fun startUpdater() {
        stopUpdater()
        updaterRunnable = object : Runnable {
            override fun run() {
                updateDisplay()
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(updaterRunnable!!)
    }

    fun stopUpdater() {
        updaterRunnable?.let { handler.removeCallbacks(it) }
        updaterRunnable = null
    }

    fun refresh() {
        updateDisplay()
    }

    private fun updateDisplay() {
        val ctx = tvToday?.get()?.context ?: tvTotal?.get()?.context ?: return
        val enabled = SpUtils.isShowTodayDurationEnabled(ctx)
        val name = MusicPlayerService.currentPlayingRawName
            ?: MusicPlayerService.currentPlayingName
        val todayView = tvToday?.get()
        val totalView = tvTotal?.get()
        val todayDurations = if (!enabled) null else loadTodayDurations()

        if (enabled) {
            val totalSeconds = todayDurations?.values?.sum() ?: 0L
            totalView?.apply {
                text = LanguageUtils.getString(ctx, R.string.format_total_duration, formatDuration(totalSeconds, ctx))
                visibility = View.VISIBLE
                try {
                    setTextColor(Color.parseColor(SpUtils.getFontColor(ctx)))
                } catch (_: Exception) {
                    setTextColor(Color.BLACK)
                }
            }
        } else {
            totalView?.visibility = View.GONE
        }
        if (!enabled || name == null) {
            todayView?.visibility = View.GONE
        } else {
            val seconds = try {
                todayDurations?.get(name) ?: 0L
            } catch (_: Exception) {
                0L
            }
            todayView?.apply {
                text = LanguageUtils.getString(ctx, R.string.format_today_duration, formatDuration(seconds, ctx))
                visibility = View.VISIBLE
                try {
                    setTextColor(Color.parseColor(SpUtils.getFontColor(ctx)))
                } catch (_: Exception) {
                    setTextColor(Color.BLACK)
                }
            }
        }
    }

    fun formatDuration(seconds: Long, context: Context): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) {
            parts.add(LanguageUtils.getString(context, R.string.duration_hours_format, h.toInt()))
        }
        if (m > 0) {
            parts.add(LanguageUtils.getString(context, R.string.duration_minutes_format, m.toInt()))
        }
        parts.add(LanguageUtils.getString(context, R.string.duration_seconds_format, s.toInt()))
        return parts.joinToString("")
    }

    private class CachedShard(
        val lastModified: Long,
        val parsed: Map<String, Map<String, Long>>
    )

    private val shardCache = HashMap<String, CachedShard>()
    private val shardCacheLock = Any()

    fun loadPlaybackDetails(): Map<String, Map<String, Long>> {
        val result = mutableMapOf<String, MutableMap<String, Long>>()
        forEachShard { parsed ->
            parsed.forEach { (name, dateMap) ->
                val m = result.getOrPut(name) { mutableMapOf() }
                dateMap.forEach { (date, seconds) ->
                    m[date] = (m[date] ?: 0L) + seconds
                }
            }
        }
        return result
    }

    fun loadSongDetails(rawName: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        forEachShard { parsed ->
            parsed[rawName]?.forEach { (date, seconds) ->
                result[date] = (result[date] ?: 0L) + seconds
            }
        }
        return result
    }

    private fun forEachShard(action: (Map<String, Map<String, Long>>) -> Unit) {
        synchronized(shardCacheLock) {
            val dir = StoragePaths.resolveRead(PlaybackDetails.DIR_REL)
            val todayName = "${dateFormat.format(Date())}.json"
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?: return
            val seen = HashSet<String>(files.size)
            for (file in files) {
                seen.add(file.name)
                readShard(file, todayName)?.let(action)
            }
            shardCache.keys.retainAll(seen)
        }
    }

    private fun readShard(file: File, todayName: String): Map<String, Map<String, Long>>? {
        if (file.name == todayName) {
            return runCatching { PlaybackDetails.parse(file.readText()) }.getOrNull()
        }
        val mtime = file.lastModified()
        shardCache[file.name]?.let { if (it.lastModified == mtime) return it.parsed }
        val parsed = runCatching { PlaybackDetails.parse(file.readText()) }.getOrNull() ?: return null
        shardCache[file.name] = CachedShard(mtime, parsed)
        return parsed
    }

    private fun loadTodayDurations(): Map<String, Long> {
        val today = dateFormat.format(Date())
        val result = mutableMapOf<String, Long>()

        runCatching {
            val shard = File(StoragePaths.resolveRead(PlaybackDetails.DIR_REL), "$today.json")
            if (shard.exists()) {
                PlaybackDetails.parse(shard.readText()).forEach { (name, dateMap) ->
                    dateMap[today]?.let { result[name] = it }
                }
            }
        }

        return result
    }
}