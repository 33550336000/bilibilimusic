package com.tilixibiesi.util

import android.os.Looper
import android.util.Log

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用级线程池。
 *
 * 旧实现是 `0 核心线程 + SynchronousQueue`：每个任务都要新建线程，池满后还会在
 * **调用线程**内联执行——主线程投递 IO 时就会真的在主线程跑 IO。
 * 现在改为固定核心线程 + 有界队列提供背压，拒绝任务转入独立的后备线程，
 * 保证"从主线程投递的任务绝不会在主线程执行"。
 */
object AppExecutors {

    private const val TAG = "AppExecutors"

    private val CPU_COUNT = Runtime.getRuntime().availableProcessors()

    /** 核心线程数：以 CPU 核数为基准，兼顾网络等待型任务。 */
    private val CORE_THREADS = (CPU_COUNT * 2).coerceIn(4, 12)

    private val MAX_THREADS = (CPU_COUNT * 4).coerceIn(8, 24)

    private const val KEEP_ALIVE_SECONDS = 30L

    /** 有界队列提供背压，避免瞬时任务无限堆叠把内存吃光。 */
    private const val QUEUE_CAPACITY = 256

    private val threadCounter = AtomicInteger(1)

    private val ioThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "tilixi-io-${threadCounter.getAndIncrement()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    val io: ExecutorService = ThreadPoolExecutor(
        CORE_THREADS,
        MAX_THREADS,
        KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(QUEUE_CAPACITY),
        ioThreadFactory,
        OverflowHandler()
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    /**
     * 队列满时的兜底：绝不在调用线程内联执行，
     * 改投独立的后备串行线程，保证调用方（可能是主线程）立刻返回。
     */
    private class OverflowHandler : RejectedExecutionHandler {

        private val fallback: ExecutorService = ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            ThreadFactory { runnable ->
                Thread(runnable, "tilixi-io-fallback").apply { isDaemon = true }
            }
        )

        override fun rejectedExecution(r: Runnable, executor: ThreadPoolExecutor) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Log.d(TAG, "IO 队列已满，任务转入后备线程执行")
            }
            runCatching { fallback.execute(r) }
                .onFailure { Log.d(TAG, "后备线程执行失败: ${it.message}") }
        }
    }
}
