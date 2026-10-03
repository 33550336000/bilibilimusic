package com.tilixibiesi.util

/**
 * 视频播放服务 → 播放器实例的单向桥。
 *
 * 视频播放在 [com.tilixibiesi.bili.BiliVideoPlayer] 内（Activity 主线程、TextureView 渲染），
 * 控制指令却来自另一个组件（通知的 PendingIntent → [com.tilixibiesi.service.VideoPlaybackService]）。
 * 两者之间没有可序列化的引用通道（服务静态持有 Activity 实例会泄漏整棵视图树），
 * 因此只把最小必要能力收敛在这里：暂停 / 恢复 / 切集 / 查询是否在播。
 *
 * 由播放器在真正起播（onPrepared）时挂上、在 close() 时摘下；
 * 服务侧每次取用前先判空，播放器已关闭则指令自然被丢弃。
 */
object VideoPlaybackController {

    /** 播放器侧实现的控制面 */
    interface Target {
        /** 当前是否处于播放中（MediaPlayer 真实状态） */
        fun isPlaying(): Boolean
        /** 暂停；已暂停时无副作用 */
        fun pause()
        /** 继续播放；已在播放时无副作用 */
        fun resume()
        /** 切上一集；无上一集时无副作用 */
        fun switchPrevious() {}
        /** 切下一集；无下一集时无副作用 */
        fun switchNext() {}
        /** 当前播放位置（毫秒）；未就绪时返回 0 */
        fun getPosition(): Int = 0
        /** 当前视频总时长（毫秒）；未就绪时返回 0 */
        fun getDuration(): Int = 0
        /** 定位到指定位置（毫秒） */
        fun seekTo(positionMs: Int) {}
    }

    @Volatile
    private var target: Target? = null

    /** 播放器起播时调用：挂上控制面 */
    @Synchronized
    fun attach(t: Target) {
        target = t
    }

    /**
     * 播放器关闭时调用。
     *
     * 只在传入的实例正是当前持有者时才清除——快速切视频（A 的收尾晚于 B 起播）时，
     * 后到的 A.close() 不应该把已经挂上的 B 摘掉。
     */
    @Synchronized
    fun detach(t: Target) {
        if (target === t) target = null
    }

    /** 当前是否有播放器托管中（不关心播放/暂停） */
    fun isAttached(): Boolean = target != null

    /** 服务侧调用：暂停视频。播放器已关闭时静默忽略 */
    fun pause() {
        runCatching { target?.pause() }
    }

    /** 服务侧调用：继续播放视频。播放器已关闭时静默忽略 */
    fun resume() {
        runCatching { target?.resume() }
    }

    /** 当前是否有播放器托管中且正在播放 */
    fun isPlaying(): Boolean = runCatching { target?.isPlaying() == true }.getOrDefault(false)

    fun switchPrevious() {
        runCatching { target?.switchPrevious() }
    }

    fun switchNext() {
        runCatching { target?.switchNext() }
    }

    /** 当前播放位置（毫秒） */
    fun getPosition(): Int = runCatching { target?.getPosition() ?: 0 }.getOrDefault(0)

    /** 当前视频总时长（毫秒） */
    fun getDuration(): Int = runCatching { target?.getDuration() ?: 0 }.getOrDefault(0)

    /** 定位到指定位置（毫秒） */
    fun seekTo(positionMs: Int) {
        runCatching { target?.seekTo(positionMs) }
    }
}
