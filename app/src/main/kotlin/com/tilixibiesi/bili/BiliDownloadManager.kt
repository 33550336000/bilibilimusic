package com.tilixibiesi.bili
import com.tilixibiesi.data.AppPaths
import com.tilixibiesi.data.LanguageUtils
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

    /** 创建带水平进度条的自定义对话框，返回对话框和进度条引用 */
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
    Thread {
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
                return@Thread
            }
            var aTemp: File? = null
            if (audioUrl.isNotEmpty()) {
                aTemp = File(dir, ".tmp_audio_${System.currentTimeMillis()}")
                val audioOk = downloadFileWithProgress(context, audioUrl, aTemp, progressBar, 40, 80)
                if (!audioOk) {
                    aTemp.delete()
                    vTemp.delete()
                    handler.post { dialog.dismiss() }
                    return@Thread
                }
            }

            // 合并、文件操作都在后台线程完成
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

            // 最后才通知主线程
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
    }.start()
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
        Thread {
            try {
                val dir = AppPaths.mp4DownloadDir()
                dir.mkdirs()
                val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "_$desc.mp3"
                val target = File(dir, safeName)
                val ok = downloadFileWithProgress(context, audioUrl, target, progressBar, 0, 100)
                if (!ok) {
                    target.delete()
                    handler.post { dialog.dismiss() }
                    return@Thread
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
        }.start()
    }

    /**
     * 下载字幕并转成 SRT 落盘。
     *
     * 必须在后台线程调用（`BiliSubtitleHelper` 内部是同步 HTTP）。
     *
     * @param title 视频标题，用作文件名（与视频下载保持同一套清洗规则）
     * @param track 字幕轨道，来自 `BiliSubtitleHelper.fetchTracks`
     * @return 成功写出后的文件；任一环节失败返回 null
     */
    fun downloadSubtitle(
        context: Context,
        title: String,
        track: BiliSubtitleTrack,
        cookie: String = "",
        /** 同一视频里 lan 可能重复（如"中文"与"中文（繁体）"），用它区分 */
        trackIndex: Int = -1
    ): File? {
        return try {
            // cookie 必须透传：B 站字幕 JSON 未登录时常返回空 body，
            // 表现为"轨道列表里有字幕，下载却总是失败"
            val cues = BiliSubtitleHelper.fetchCues(track, cookie)
            if (cues.isEmpty()) return null
            val dir = AppPaths.subtitleDir()
            if (!dir.exists()) dir.mkdirs()
            // 文件名：标题 + lan（AI 字幕加 _ai 后缀），避免多语言互相覆盖。
            // 人传「中文」与 AI「中文」的 lan 不同（zh / ai-zh）本已能区分，
            // 但 lan 相同的重复轨道（多个 AI 字幕都叫 ai-zh）仍需 trackIndex 兜底。
            val lanPart = buildString {
                if (track.lan.isNotEmpty()) append('_').append(track.lan)
                if (track.isAi) append("_ai")
            }
            var safeName = sanitize(title) + lanPart + ".srt"
            if (File(dir, safeName).exists()) {
                safeName = sanitize(title) + lanPart + "_$trackIndex.srt"
            }
            val finalTarget = File(dir, safeName)
            finalTarget.writeText(BiliSubtitleHelper.toSrt(cues))
            finalTarget
        } catch (_: Exception) {
            null
        }
    }

    /** 文件名清洗：与视频下载保持一致，去掉 Windows/Android 都不允许的字符 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    /** 修改：接收 ProgressBar 代替旧的 ProgressDialog；返回是否下载成功。 */
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

                        // 进度节流：百分比变化且距上次至少 200ms 才刷 UI，避免每 16KB 都 post 一次。
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

        // 使用原始 PTS（sampleTime）按时间顺序归并写入。
        // 不能各自从 0 按固定帧率/固定采样率推算——B 站视频帧率可能是 29.97/23.976 等非整数，
        // 音频 AAC 每帧样本数也可能不是 1024，推算会导致音画逐渐错位、不对应。
        // MediaMuxer 要求样本按 presentationTime 非递减写入，故用双指针边读边归并。

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

        // 双指针归并：每次取时间戳更小的一侧写入，保证 PTS 非递减
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
