package com.tilixibiesi.data

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tilixibiesi.util.AppExecutors
import java.io.File
import java.nio.file.Files

/**
 * 应用私有数据目录（`/data/user/0/<包名>`）的清理收口。
 *
 * 只要应用里存在任何一个 WebView（本项目两处——点击特效层
 * [com.tilixibiesi.ui.widget.ClickFxOverlay] 与哔哩哔哩登录页 SearchPage），
 * Chromium 初始化时就会自作主张地在私有目录里建出一整套目录树，本类是它们的收口处。
 *
 * ## 两个入口，激进度不同
 *
 *  - [purge]：**激进清理**。删除私有数据目录下除三个 prefs（见 [APP_PREFS]）以外的
 *    **全部内容**——app_webview、databases、app_textures、code_cache、cache，
 *    以及顶层那些 variations_seed_new / last-exit-info / webview_data.lock / pref_store；
 *    并额外清理设备加密存储 `/data/user_de/0/<包名>` 下的 cache / code_cache
 *    （见 [purgeDeviceProtected]：系统统计的是这两个目录之和）。
 *    只在**应用进入后台**时调用（见 [onBackground]），前台绝不调用：前台删掉
 *    app_textures / code_cache / databases 会打断正在运行的应用自己。
 *  - [purgeWebViewArtifacts]：**定点清理**。只删 WebView 与缓存产物，保留数据库、
 *    app_textures 等运行期目录，供前台（页面销毁、启动自检）使用。
 *
 * ## 触发时机：应用不可见时才删
 *
 * 由 [MyApplication] 注册 ActivityLifecycleCallbacks 统计「started 状态的 Activity 数」，
 * 归零即视为应用整体不可见，此时：
 *  1. 先回调 [AppBackgroundListener]（特效层借此销毁 WebView，见下）；
 *  2. 立刻清一遍，随后每 [INTERVAL_MS] 再清一遍，直到连续 [IDLE_ROUNDS] 轮没删到东西
 *     或达到 [MAX_ROUNDS] 轮上限。
 *
 * 之所以要**反复清**而不是只清一次：Chromium 的落盘是延迟的。实测（vivo V2507A /
 * Android 16 / WebView 138.0.7204.179）在应用存活时删除 `app_webview/` 整个目录，
 * **8 秒后**目录连同 6 个文件（Session Storage 的 4 个 leveldb 文件 + last-exit-info
 * 等）被原样重建。所以「删一次」等于没删，必须在一个时间窗内反复清。
 *
 * 之所以还要**先销毁 WebView**：文件是被 Chromium 渲染进程写回来的。只要渲染进程还活着，
 * 清理就追不上它；先 destroy() 断掉写回源头，再清，目录才能真正保持为空。
 *
 * ## 唯一保留的三个文件
 *
 * [APP_PREFS] 全是应用自己的业务数据，与 WebView 无关，必须原样保留（B 站 Cookie 也在其中）：
 *  - `MusicPlayerPrefs.xml` —— SpUtils：全部业务设置 + B 站登录 Cookie
 *  - `app_settings.xml`     —— SpUtils：音量键切歌等
 *  - `language_prefs.xml`   —— LanguageUtils：界面语言
 *
 * ## 关于符号链接（重要）
 *
 * 某些机型上 `files/`、`lib/` 是**符号链接**，指向本包数据目录内的真实路径。
 * 若跟随链接递归删除，会把链接目标（也就是刚保留下来的 `shared_prefs/` 所在目录）
 * 一起删掉，导致应用设置被清空。因此：[aggressiveDelete] 跳过顶层链接，
 * 且 [deleteNode] 对**每一层**都只删链接本身、绝不进入，双重保险。
 *
 * 需在后台线程调用（[onBackground] 内部已切到 [AppExecutors.io]）；清理失败一律不影响业务。
 */
object WebViewMetricsCleaner {

    /** Chromium 数据目录名 */
    private const val DIR = "app_webview"

    /** SharedPreferences 目录名 */
    private const val SHARED_PREFS_DIR = "shared_prefs"

    /**
     * 激进清理时**必须保留**的文件（均位于 `shared_prefs/` 下）。
     * 这三个文件承载应用的全部持久化设置，删了等同于用户被重置。
     */
    val APP_PREFS = arrayOf(
        "MusicPlayerPrefs.xml",
        "app_settings.xml",
        "language_prefs.xml"
    )
    private val KEEP = APP_PREFS.toSet()

    /** 后台反复清理的间隔 */
    private const val INTERVAL_MS = 3_000L
    /** 后台最多清多少轮（约 72 秒），防止无限轮询 */
    private const val MAX_ROUNDS = 24
    /** 连续多少轮没删到东西就收手 */
    private const val IDLE_ROUNDS = 4
    /** 进入后台后延迟多久开始第一轮：给 Activity 销毁与 WebView 释放留出时间 */
    private const val FIRST_DELAY_MS = 800L

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** 剩余轮数；0 表示未在清理（前台即为此状态） */
    @Volatile private var roundsLeft = 0
    @Volatile private var idleRounds = 0

    /**
     * 应用进入后台时的回调。特效层等 WebView 持有者借此先销毁自己，
     * 否则 Chromium 会把刚删掉的目录立刻写回来。
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<AppBackgroundListener>()

    fun addBackgroundListener(listener: AppBackgroundListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeBackgroundListener(listener: AppBackgroundListener) {
        listeners.remove(listener)
    }

    // ==================== 生命周期 ====================

    /**
     * 应用仍可见（有 Activity 处于 started）：停止后台轮询。
     *
     * 回到前台后 Chromium 会按需重建运行期文件，这是正常的——此时不再主动删，
     * 否则会和正在运行的 WebView 互相拉扯。
     */
    fun onForeground() {
        handler.removeCallbacksAndMessages(null)
        roundsLeft = 0
        idleRounds = 0
    }

    /**
     * 应用整体不可见（最后一个 started Activity 已 stop）：
     * 先让监听者释放 WebView，再开始激进清理。
     *
     * @param app 传 Application，避免持有 Activity
     */
    fun onBackground(app: Application) {
        // 释放 WebView 必须回主线程（WebView.destroy 只能在创建它的线程调用）
        for (listener in listeners) {
            runCatching { listener.onAppBackground() }
        }
        schedule(app, MAX_ROUNDS)
    }

    private fun schedule(app: Application, rounds: Int) {
        handler.removeCallbacksAndMessages(null)
        roundsLeft = rounds
        idleRounds = 0
        postNext(app, FIRST_DELAY_MS)
    }

    private fun postNext(app: Application, delayMs: Long) {
        if (roundsLeft <= 0 || idleRounds >= IDLE_ROUNDS) return
        handler.postDelayed({
            if (roundsLeft <= 0) return@postDelayed
            roundsLeft--
            // 文件删除放到 IO 线程：目录树条目多，且不能阻塞主线程
            AppExecutors.io.execute {
                val deleted = purge(app.applicationContext)
                handler.post {
                    // 期间若已回到前台（roundsLeft 归零），立刻收手
                    if (roundsLeft <= 0) return@post
                    if (deleted) idleRounds = 0 else idleRounds++
                    postNext(app, INTERVAL_MS)
                }
            }
        }, delayMs)
    }

    // ==================== 清理实现 ====================

    /**
     * 激进清理：删除私有数据目录下除 [APP_PREFS] 以外的**全部内容**。
     *
     * 删除项（实测残留清单里的每一项都在此覆盖）：
     *  - `app_webview/`（含 Default/ 下的 Local Storage、Session Storage、Shared Dictionary、
     *    shared_proto_db、blob_storage、PersistentOriginTrials、Cookies、Web Data 等）
     *  - `app_webview/variations_seed_new`、`variations_stamp`、`last-exit-info`、
     *    `webview_data.lock`、`pref_store`
     *  - `shared_prefs/` 下非 [APP_PREFS] 的文件（WebViewChromiumPrefs.xml、
     *    AwOriginVisitLoggerPrefs.xml 及任何 .bak 残留）
     *  - `databases/`、`app_textures/`、`code_cache/`、`cache/`（整个目录连根删）
     *  - 其它任何顶层条目（`no_backup/`、`profiles/` 等）
     *  - **设备加密存储** `/data/user_de/0/<包名>` 下的 cache / code_cache
     *    —— 见 [purgeDeviceProtected]：系统的「数据」统计把它也算在内
     *
     * 跳过项：顶层符号链接（见类注释「关于符号链接」）。
     *
     * @return 本次是否真的删掉了东西
     */
    fun purge(context: Context): Boolean {
        return try {
            val app = context.applicationContext
            var deleted = aggressiveDelete(File(app.applicationInfo.dataDir))
            if (purgeDeviceProtected(app)) deleted = true
            deleted
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 清理设备加密存储（DE，`/data/user_de/0/<包名>`）。
     *
     * 为什么必须单独处理：系统的「应用数据」占用统计把 CE（/data/user/0）与
     * DE（/data/user_de/0）**两个目录相加**。1:1 实测（vivo V2507A）：
     * CE 清到只剩 prefs 后是 55KB，而 DE 仍有 103KB（`cache/` + `code_cache/`），
     * 合计 158KB——这正是设置里「数据」显示仍有「百kb」的原因，只清 CE 永远降不下去。
     *
     * DE 里只有 cache / code_cache 两类可再生目录，因此逐个清空内容、保留目录本身
     * （目录本身不占多少，且缺失时某些 ROM 会再建）。
     *
     * 说明：用 [android.content.Context.createDeviceProtectedStorageContext] 的公共 API
     * 拿到该目录，避免硬编码 `/data/user_de` 路径。
     */
    private fun purgeDeviceProtected(app: Context): Boolean {
        var deleted = false
        val deContext = runCatching { app.createDeviceProtectedStorageContext() }.getOrNull() ?: return false
        for (dir in arrayOf(deContext.cacheDir, deContext.codeCacheDir)) {
            val children = dir?.listFiles() ?: continue
            for (child in children) {
                if (deleteNode(child)) deleted = true
            }
        }
        return deleted
    }

    private fun aggressiveDelete(dataDir: File): Boolean {
        val children = dataDir.listFiles() ?: return false
        var deleted = false
        for (child in children) {
            // shared_prefs 要逐文件判断，不能整目录删
            if (child.name == SHARED_PREFS_DIR) {
                if (sweepSharedPrefs(child)) deleted = true
                continue
            }
            // 符号链接一律跳过：files/ 之类的链接目标就在本包数据目录内，
            // 跟随删除会连带删掉保留中的 prefs（详见类注释）
            if (isSymlink(child)) continue
            if (deleteNode(child)) deleted = true
        }
        return deleted
    }

    private fun sweepSharedPrefs(prefsDir: File): Boolean {
        val files = prefsDir.listFiles() ?: return false
        var deleted = false
        for (f in files) {
            // 只保留三个业务 prefs，其余（WebView 的、临时的）全删
            if (f.isFile && f.name in KEEP) continue
            if (deleteNode(f)) deleted = true
        }
        return deleted
    }

    /**
     * 删除文件或目录树；返回是否确实删掉了（原先存在的才算）。
     *
     * **不能直接用 `File.deleteRecursively()`**：它内部会跟随符号链接，
     * 若目录树里存在指向别处的链接，会把链接目标一起删掉——而链接目标很可能
     * 就是保留中的 `shared_prefs/`。这里按「子项优先」的顺序手工删除，
     * 且**只删链接本身、不进入链接**，从根上避免误删。
     */
    private fun deleteNode(node: File): Boolean {
        if (!node.exists()) return false
        if (node.isDirectory && !isSymlink(node)) {
            node.listFiles()?.forEach { deleteNode(it) }
        }
        node.delete()
        return !node.exists()
    }

    private fun isSymlink(f: File): Boolean =
        try {
            Files.isSymbolicLink(f.toPath())
        } catch (_: Exception) {
            false
        }

    /**
     * 定点清理：只清 WebView 与缓存产物，**保留** databases / app_textures / code_cache。
     *
     * 供前台使用（哔哩哔哩登录页关闭后、启动自检）：此时应用正在运行，
     * 激进地连 code_cache、app_textures 一起删会打断应用自身（丢掉已编译代码缓存）。
     *
     * 清理项：`app_webview/`、`shared_prefs/` 下 WebView 遗留文件、旧版 WebView 数据库、
     * `cache/` 内容。
     */
    fun purgeWebViewArtifacts(context: Context) {
        try {
            val app = context.applicationContext
            val dataDir = File(app.applicationInfo.dataDir)
            runCatching { deleteNode(File(dataDir, DIR)) }
            runCatching { sweepSharedPrefs(File(dataDir, SHARED_PREFS_DIR)) }
            runCatching { app.deleteDatabase("webview.db") }
            runCatching { app.deleteDatabase("webviewCache.db") }
            // 显式声明 Unit：listFiles()?.forEach 的返回值是 Unit?，
            // 会让 runCatching 的泛型 R 无从推断（编译错误 CANNOT_INFER_PARAMETER_TYPE）
            runCatching<Unit> {
                // cacheDir 本身就是 File，不能再包一层 File(...)
                app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
        } catch (_: Exception) {
            // 清理属于尽力而为，任何异常都不应向上传播
        }
    }
}

/** 应用整体进入后台时的回调，见 [WebViewMetricsCleaner.addBackgroundListener] */
fun interface AppBackgroundListener {
    fun onAppBackground()
}
