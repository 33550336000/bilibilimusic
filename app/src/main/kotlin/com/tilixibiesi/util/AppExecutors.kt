package com.tilixibiesi.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局 IO 线程池。
 *
 * 项目里有大量「一次性网络 / 文件」任务，统一到这里执行：线程可复用，
 * 不必每个任务都 `Thread{}.start()`，也避免调用方各自临时建池。
 *
 * ## 为什么从 newCachedThreadPool 换掉
 *
 * 原实现是 [java.util.concurrent.Executors.newCachedThreadPool]，
 * 它的上限是 `Integer.MAX_VALUE` —— 即**无上限**：只要任务提交得比执行快，
 * 它就会一直创建新线程。本项目的任务量由用户行为驱动（批量缓存下载、
 * 搜索翻页、字幕多批采样并发触发），瞬时堆积几十上百个线程是可能的，
 * 每个线程默认 1MB 栈，在手机上足以造成明显的内存压力甚至 OOM。
 *
 * ## 现在这套配置为什么是安全的
 *
 * 用 [ThreadPoolExecutor] + [SynchronousQueue] 保留了 cached pool 的语义
 * （不排队，有空闲线程就直接交付，否则立刻开新线程），但把线程数**封顶**在
 * [MAX_THREADS]。关键在于拒绝策略选了 [ThreadPoolExecutor.CallerRunsPolicy]：
 * 线程数打满后，新任务不再被丢弃，而是**由提交者线程自己执行**。
 *
 * 这一条同时解决了一个真实的死锁风险：项目里有两处「fan-out 后阻塞等待」的
 * 逻辑（[com.tilixibiesi.bili.BiliSubtitleHelper] 的 4 路采样、
 * `SearchPage` 拉画质与字幕的 2 路并行）。它们从池线程里再提交子任务并
 * `CountDownLatch.await()`。若线程全部被这类「等待中」的外层任务占满，
 * 子任务就会被永久搁置、无人执行 —— 典型的工作线程饥饿死锁。
 * 采用 CallerRunsPolicy 后，提交不下去的子任务会被等待者自己跑掉，
 * 等待的 latch 必然能倒数到 0，**进度一定向前**，死锁在结构上不可能发生。
 *
 * 因此这里的取舍是：宁可让个别任务在调用线程上串行执行（变慢），
 * 也不让它被丢弃或永远排队。
 */
object AppExecutors {

    /** 并发线程上限。取 32：足以覆盖字幕 4 路 + 画质/字幕 2 路等嵌套并发，又能挡住失控堆积。 */
    private const val MAX_THREADS = 32

    /** 空闲线程存活时间：与 cached pool 默认一致。 */
    private const val KEEP_ALIVE_SECONDS = 60L

    private val threadCounter = AtomicInteger(1)

    /**
     * 适合网络 / 文件 IO 的共享线程池。
     *
     * 线程为 daemon：进程退出时不会被这些后台任务拖住。
     */
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
