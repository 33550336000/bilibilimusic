package com.tilixibiesi.bili
import com.tilixibiesi.data.AppPaths
import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.util.AppExecutors
import com.tilixibiesi.util.AtomicFileWriter
import com.tilixibiesi.R

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

object BiliDownloadManager {
    private const val TAG = "BiliDownloadManager"
    private val handler = Handler(Looper.getMainLooper())

    private fun createProgressDialog(context: Context, message: String): Pair<AlertDialog, ProgressBar> {
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val textView = TextView(context).apply {
            text = message
            textSize = 16f
            setPadding(32, 32, 32, 16)
            setTextColor(Color.BLACK)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(textView)
            addView(progressBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 32
                rightMargin = 32
                bottomMargin = 32
            })
        }
        val dialog = AlertDialog.Builder(context, R.style.TransparentDialog)
            .setView(layout)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val minWidth = (context.resources.displayMetrics.widthPixels * 0.85).toInt()
        dialog.window?.setLayout(minWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        return Pair(dialog, progressBar)
    }

fun downloadVideoWithQuality(
    context: Context,
    title: String,
    videoUrl: String,
    audioUrl: String,
    desc: String
) {
    if (videoUrl.isEmpty()) {
        Toast.makeText(context, LanguageUtils.getString(context, R.string.video_url_unavailable_bili), Toast.LENGTH_SHORT).show()
        return
    }
    val (dialog, progressBar) = createProgressDialog(context, LanguageUtils.getString(context, R.string.download_desc, desc))
    AppExecutors.io.execute {
        try {
            val dir = AppPaths.mp4DownloadDir()
            dir.mkdirs()
            val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "_$desc.mp4"
            val target = File(dir, safeName)
            val vTemp = File(dir, ".tmp_video_${System.currentTimeMillis()}")
            val videoOk = downloadFileWithProgress(context, videoUrl, vTemp, progressBar, 0, if (audioUrl.isNotEmpty()) 40 else 100)
            if (!videoOk) {
                vTemp.delete()
                handler.post { dialog.dismiss() }
                return@execute
            }
            var aTemp: File? = null
            if (audioUrl.isNotEmpty()) {
                aTemp = File(dir, ".tmp_audio_${System.currentTimeMillis()}")
                val audioOk = downloadFileWithProgress(context, audioUrl, aTemp, progressBar, 40, 80)
                if (!audioOk) {
                    aTemp.delete()
                    vTemp.delete()
                    handler.post { dialog.dismiss() }
                    return@execute
                }
            }

            val success: Boolean
            val message: String
            if (aTemp != null && aTemp.exists()) {
                if (muxVideoAndAudio(vTemp, aTemp, target)) {
                    vTemp.delete()
                    aTemp.delete()
                    success = true
                    message = LanguageUtils.getString(context, R.string.download_complete_name, safeName)
                } else {
                    vTemp.renameTo(target)
                    aTemp.delete()
                    success = false
                    message = LanguageUtils.getString(context, R.string.mux_failed_keep_video, safeName)
                }
            } else {
                vTemp.renameTo(target)
                success = true
                message = LanguageUtils.getString(context, R.string.download_complete_name, safeName)
            }

            handler.post {
                dialog.dismiss()
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            handler.post {
                dialog.dismiss()
                Toast.makeText(context, LanguageUtils.getString(context, R.string.download_failed_msg, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
    fun downloadAudioOnly(
        context: Context,
        title: String,
        audioUrl: String,
        desc: String
    ) {
        if (audioUrl.isEmpty()) {
            Toast.makeText(context, LanguageUtils.getString(context, R.string.audio_url_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val (dialog, progressBar) = createProgressDialog(context, LanguageUtils.getString(context, R.string.download_desc, desc))
        AppExecutors.io.execute {
            try {
                val dir = AppPaths.mp4DownloadDir()
                dir.mkdirs()
                val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "_$desc.mp3"
                val target = File(dir, safeName)
                val ok = downloadFileWithProgress(context, audioUrl, target, progressBar, 0, 100)
                if (!ok) {
                    target.delete()
                    handler.post { dialog.dismiss() }
                    return@execute
                }
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(context, LanguageUtils.getString(context, R.string.download_complete_name, safeName), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                handler.post {
                    dialog.dismiss()
                    Toast.makeText(context, LanguageUtils.getString(context, R.string.download_failed_msg, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun downloadSubtitle(
        context: Context,
        title: String,
        track: BiliSubtitleTrack,
        cookie: String = "",
        trackIndex: Int = -1
    ): File? {
        return try {
            val cues = BiliSubtitleHelper.fetchCues(track, cookie)
            if (cues.isEmpty()) return null
            val dir = AppPaths.subtitleDir()
            if (!dir.exists()) dir.mkdirs()
            val lanPart = buildString {
                if (track.lan.isNotEmpty()) append('_').append(track.lan)
                if (track.isAi) append("_ai")
            }
            var safeName = sanitize(title) + lanPart + ".srt"
            if (File(dir, safeName).exists()) {
                safeName = sanitize(title) + lanPart + "_$trackIndex.srt"
            }
            val finalTarget = File(dir, safeName)
            AtomicFileWriter.writeText(finalTarget, BiliSubtitleHelper.toSrt(cues))
            finalTarget
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun downloadFileWithProgress(
        context: Context,
        urlStr: String,
        dest: File,
        progressBar: ProgressBar,
        fromPercent: Int,
        toPercent: Int
    ): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code !in 200..299) {
                dest.delete()
                handler.post {
                    Toast.makeText(context, LanguageUtils.getString(context, R.string.download_http_code, code), Toast.LENGTH_SHORT).show()
                }
                return false
            }

            val total = if (conn.contentLength > 0) conn.contentLength.toLong() else -1L
            var downloaded = 0L
            var lastPercent = -1
            var lastPostAt = 0L

            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        output.write(buffer, 0, len)
                        downloaded += len
                        val pct = if (total > 0) {
                            fromPercent + ((toPercent - fromPercent) * downloaded / total).toInt()
                        } else {
                            fromPercent + ((toPercent - fromPercent) * (downloaded % 1048576L) / 1048576L).toInt()
                        }.coerceIn(0, 100)

                        val now = System.currentTimeMillis()
                        if (pct != lastPercent && (now - lastPostAt >= 200 || pct >= toPercent)) {
                            lastPercent = pct
                            lastPostAt = now
                            handler.post { progressBar.progress = pct }
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            dest.delete()
            handler.post {
                Toast.makeText(context, LanguageUtils.getString(context, R.string.download_failed_class, e.javaClass.simpleName), Toast.LENGTH_SHORT).show()
            }
            return false
        } finally {
            conn?.disconnect()
        }
    }

private fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File): Boolean {
    var videoExtractor: MediaExtractor? = null
    var audioExtractor: MediaExtractor? = null
    var muxer: MediaMuxer? = null

    try {
        videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

        val videoTrackIndex = (0 until videoExtractor.trackCount).firstOrNull {
            videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: return false
        val audioTrackIndex = (0 until audioExtractor.trackCount).firstOrNull {
            audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return false

        videoExtractor.selectTrack(videoTrackIndex)
        audioExtractor.selectTrack(audioTrackIndex)

        val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
        val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outVideoTrack = muxer.addTrack(videoFormat)
        val outAudioTrack = muxer.addTrack(audioFormat)
        muxer.start()

        val buffer = ByteArray(256 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()


        fun readVideoSample(): Boolean {
            val size = videoExtractor.readSampleData(ByteBuffer.wrap(buffer), 0)
            if (size < 0) return false
            bufferInfo.size = size
            bufferInfo.presentationTimeUs = videoExtractor.sampleTime
            bufferInfo.flags = if ((videoExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(outVideoTrack, ByteBuffer.wrap(buffer, 0, size), bufferInfo)
            videoExtractor.advance()
            return true
        }

        fun readAudioSample(): Boolean {
            val size = audioExtractor.readSampleData(ByteBuffer.wrap(buffer), 0)
            if (size < 0) return false
            bufferInfo.size = size
            bufferInfo.presentationTimeUs = audioExtractor.sampleTime
            bufferInfo.flags = 0
            muxer.writeSampleData(outAudioTrack, ByteBuffer.wrap(buffer, 0, size), bufferInfo)
            audioExtractor.advance()
            return true
        }

        var hasVideo = videoExtractor.sampleTime in 0..Long.MAX_VALUE
        var hasAudio = audioExtractor.sampleTime in 0..Long.MAX_VALUE
        while (hasVideo || hasAudio) {
            when {
                hasVideo && (!hasAudio || videoExtractor.sampleTime <= audioExtractor.sampleTime) ->
                    hasVideo = readVideoSample()
                hasAudio -> hasAudio = readAudioSample()
            }
        }

        return true
    } catch (e: Exception) {
        return false
    } finally {
        try { videoExtractor?.release() } catch (_: Exception) {}
        try { audioExtractor?.release() } catch (_: Exception) {}
        try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
    }
}
}
