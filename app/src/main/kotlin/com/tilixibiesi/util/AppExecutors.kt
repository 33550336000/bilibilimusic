package com.tilixibiesi.util

import android.os.Looper

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object AppExecutors {

    private const val MAX_THREADS = 32

    private const val KEEP_ALIVE_SECONDS = 60L

    private val threadCounter = AtomicInteger(1)

    private fun newThread(runnable: Runnable, prefix: String): Thread =
        Thread(runnable, "$prefix-${threadCounter.getAndIncrement()}").apply { isDaemon = true }

    val io: ExecutorService = ThreadPoolExecutor(
        0,
        MAX_THREADS,
        KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable -> newThread(runnable, "tilixi-io") },
        OverflowHandler()
    )

    /**
     * 线程池满载（32 个线程全部忙碌）时的兜底策略。
     *
     * 不能直接用 [ThreadPoolExecutor.CallerRunsPolicy]：线程池是从 UI 层调用的，
     * 满载时任务会同步跑在调用者线程上，磁盘 IO 或网络请求落到主线程就会卡帧甚至 ANR。
     * 也不能用 DiscardPolicy / DiscardOldestPolicy：这里的任务多是下载、写盘、解析，
     * 静默丢弃会直接丢失用户操作。
     *
     * 因此保留「任务一定会执行」的语义，只掐掉「不许在主线程执行」这一条：
     * 调用者本来就是后台线程时照旧同步执行（避免无谓的线程创建）；
     * 调用者若是主线程，则另起一个守护线程承接，主线程立刻返回。
     */
    private class OverflowHandler : RejectedExecutionHandler {
        override fun rejectedExecution(r: Runnable, executor: ThreadPoolExecutor) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                newThread(r, "tilixi-io-overflow").start()
            } else {
                r.run()
            }
        }
    }
}
