package com.tilixibiesi

import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.WebViewMetricsCleaner
import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.service.MusicPlayerService

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

class MyApplication : Application() {

    /**
     * 处于 started 状态的 Activity 数量。归零即代表应用整体不可见（进后台/被切走）。
     *
     * 为什么不用单个 Activity 的 onStop 直接判定：应用有多个 Activity
     * （MainPagerActivity / PlaylistDetailActivity / SelectMusicActivity），
     * 主页面跳到二级页时前者也会 onStop，但应用其实仍然可见——此时绝不能清数据。
     * 只有计数归零才能确定用户真的离开了应用。
     */
    private var startedActivities = 0

    override fun attachBaseContext(base: Context?) {
        // 注意：这里**不能**用 LanguageUtils.wrapContext() 包装 Application 的 base context。
        //
        // 原因：wrapContext() 会返回一个 ContextWrapper（LanguageUtils$LocalizedContext）。
        // 部分 ROM（实测 vivo/OriginOS）在实例化 AndroidManifest 里注册的 BroadcastReceiver
        // （如 BootReceiver）时，会把 Application 的 base context 直接强转为 ContextImpl，
        // 包装后即抛 ClassCastException：
        //   Unable to instantiate receiver com.tilixibiesi.BootReceiver:
        //   java.lang.ClassCastException: n3 cannot be cast to android.app.ContextImpl
        // 导致每次冷启动（收到开机/后台广播时）进程崩溃。
        //
        // 界面文案的本地化并不依赖这里：BaseActivity / BasePage 各自覆写 getResources()
        // 返回 LocalizedResources，布局 inflate 由 LocalizedViewFactory 覆盖，均与
        // Application 的 base context 无关。
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        // 恢复存储根目录（主存储 /sdcard/.Path 或 内部存储 /sdcard/Android/data/包名）
        StoragePaths.init(this)
        // 依赖 StoragePaths 定位语言 JSON，因此放在 init 之后刷新覆盖映射
        LanguageUtils.refreshLanguageMap(this)
        // 启动时只检查当前语言的资源是否需要下载/更新，需要则静默下载并在完成后切换
        LanguageUtils.checkCurrentLanguageOnStartup(this)
        // 应用进入后台（最后一个 Activity stop）时激进清理私有数据目录，
        // 只保留三个业务 prefs。详见 WebViewMetricsCleaner 的类注释。
        registerActivityLifecycleCallbacks(BackgroundPurgeCallbacks())
    }

    /**
     * 用「started Activity 计数」判定应用是否整体不可见，归零即触发激进清理。
     *
     * 不用 ProcessLifecycleOwner：本项目无 androidx.lifecycle 依赖（dependencies 为空），
     * 这里只需要一个计数，没必要为此引入整个 lifecycle 库。
     */
    private inner class BackgroundPurgeCallbacks : ActivityLifecycleCallbacks {

        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (startedActivities == 1) {
                // 回到前台：停止后台轮询，避免与正在运行的 WebView 互相拉扯
                MusicPlayerService.appVisible = true
                WebViewMetricsCleaner.onForeground()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
            if (startedActivities <= 0) {
                startedActivities = 0
                // 应用已不可见：先释放 WebView，再开始反复清理
                MusicPlayerService.appVisible = false
                WebViewMetricsCleaner.onBackground(this@MyApplication)
            }
        }

        // 其余回调与本功能无关
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
