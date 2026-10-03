package com.tilixibiesi.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 全局 IO 线程池。
 *
 * 项目里有大量“一次性网络/文件”任务，原先每个任务都 new Thread，部分地方还会
 * 每次调用临时创建线程池。统一到这里的缓存线程池后，线程可复用，避免频繁创建销毁。
 */
object AppExecutors {

    /** 适合网络 / 文件 IO 的共享线程池。 */
    val io: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "tilixi-io").apply { isDaemon = true }
    }
}
