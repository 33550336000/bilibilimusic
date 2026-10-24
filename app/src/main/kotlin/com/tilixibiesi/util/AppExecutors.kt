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
