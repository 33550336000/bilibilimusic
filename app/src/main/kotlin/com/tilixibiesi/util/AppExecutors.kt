package com.tilixibiesi.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object AppExecutors {

    private const val MAX_THREADS = 32

    private const val KEEP_ALIVE_SECONDS = 60L

    private val threadCounter = AtomicInteger(1)

    val io: ExecutorService = ThreadPoolExecutor(
        0,
        MAX_THREADS,
        KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable ->
            Thread(runnable, "tilixi-io-${threadCounter.getAndIncrement()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.CallerRunsPolicy()
    )
}
