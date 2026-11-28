package com.tilixibiesi.util

import android.os.Looper

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object AppExecutors {

    private const val TAG = "AppExecutors"

    private val CPU_COUNT = Runtime.getRuntime().availableProcessors()

    private val CORE_THREADS = (CPU_COUNT * 2).coerceIn(4, 12)

    private val MAX_THREADS = (CPU_COUNT * 4).coerceIn(8, 24)

    private const val KEEP_ALIVE_SECONDS = 30L

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
                AppLog.d(TAG, "IO 队列已满，任务转入后备线程执行")
            }
            runCatching { fallback.execute(r) }
                .onFailure { AppLog.d(TAG, "后备线程执行失败: ${it.message}") }
        }
    }
}
