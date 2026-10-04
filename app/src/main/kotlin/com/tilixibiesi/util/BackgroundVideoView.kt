package com.tilixibiesi.util

import android.content.Context
import android.media.MediaPlayer
import android.widget.VideoView

/**
 * 背景视频层：把「是否当前页」显式建模为 [isActive]，只有当前页才播放并出声。
 *
 * ## 为什么需要这个类
 *
 * 分页容器 [com.tilixibiesi.ui.widget.HorizontalPager] 会常驻当前页 ±1 共 3 个页面实例
 * （刻意设计：保留滚动位置与页面状态，不随切页销毁），而每个页面都有一层背景视频。
 * 若各自独立播放，就会出现 **3 路背景音同时响**（且进度各不相同）。
 *
 * 另有一个隐蔽点：`onPrepared` 是**异步**回调，页面完全可能在视频就绪之前就被切走。
 * 因此绝不能像原先那样在 `onPrepared` 里无条件 `start()` —— 那样切走的页面照样出声。
 * 这里在就绪时按**当时**的 [isActive] 决定是否开播，从根上避免「后台页面偷跑」。
 *
 * 非当前页同时**暂停**（不再解码新帧）并**静音**，回到该页再恢复播放位置。
 */
class BackgroundVideoView(context: Context) : VideoView(context) {

    /** 绑定的源文件路径：用于判断能否复用现有实例，避免每次 onResume 重建后从头播放 */
    var sourcePath: String = ""
        private set

    /**
     * 是否为「当前页」。
     * true：播放并出声；false：暂停解码并静音。
     */
    var isActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyState()
        }

    private var prepared = false
    private var player: MediaPlayer? = null
    /** 是否已释放：释放后到达的 onPrepared 必须忽略，否则会重新出声 */
    private var released = false

    /**
     * 绑定源文件并安装回调。
     *
     * 刻意**不**在这里播放：是否开播由 [isActive] 与 `onPrepared` 共同决定，
     * 两者谁后到都能正确收敛（就绪时看 isActive，激活时看 prepared）。
     */
    fun bind(path: String) {
        sourcePath = path
        // 回调必须先于 setVideoPath 安装：否则本地小文件可能在回调装好前就已就绪，
        // 导致 onPrepared 被漏掉、背景永远不播。
        setOnPreparedListener { mp ->
            // 已释放则忽略：异步回调可能在 release 之后才到
            if (released) return@setOnPreparedListener
            prepared = true
            player = mp
            mp.isLooping = true
            applyState()
        }
        setOnErrorListener { _, _, _ -> true }
        setVideoPath(path)
    }

    /** 按当前 [isActive] 收敛播放状态；未就绪时只记状态，等 onPrepared 再落实 */
    private fun applyState() {
        if (released) return
        val mp = player
        if (isActive) {
            if (prepared && !isPlaying) runCatching { start() }
            runCatching { mp?.setVolume(1f, 1f) }
        } else {
            if (prepared && isPlaying) runCatching { pause() }
            // 即使暂停尚未生效也先静音：切页瞬间不会漏出一帧声音
            runCatching { mp?.setVolume(0f, 0f) }
        }
    }

    /** 停止并释放解码器（供替换背景、页面销毁时调用）；释放后不可再播放 */
    fun release() {
        released = true
        // 先静音再停：stopPlayback 到真正停下之间可能还有一帧声音
        runCatching { player?.setVolume(0f, 0f) }
        runCatching { stopPlayback() }
        player = null
        prepared = false
    }
}
