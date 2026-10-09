package com.tilixibiesi

import com.tilixibiesi.data.LanguageUtils
import com.tilixibiesi.data.SettingsStore
import com.tilixibiesi.data.SpUtils
import com.tilixibiesi.data.WebViewMetricsCleaner
import com.tilixibiesi.data.StoragePaths
import com.tilixibiesi.service.MusicPlayerService

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

class MyApplication : Application() {

    private var startedActivities = 0

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        StoragePaths.init(this)
        // 进入应用即恢复本地已有设置，不再等到打开设置页面才加载
        loadLocalSettings()
        LanguageUtils.refreshLanguageMap(this)
        LanguageUtils.checkCurrentLanguageOnStartup(this)
        registerActivityLifecycleCallbacks(BackgroundPurgeCallbacks())
    }

    private fun loadLocalSettings() {
        runCatching {
            if (!SpUtils.isAutoLoadDefaultEnabled(this)) return@runCatching
            SettingsStore.loadFromFile(this)
        }
    }

    private inner class BackgroundPurgeCallbacks : ActivityLifecycleCallbacks {

        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (startedActivities == 1) {
                MusicPlayerService.appVisible = true
                WebViewMetricsCleaner.onForeground()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
            if (startedActivities <= 0) {
                startedActivities = 0
                MusicPlayerService.appVisible = false
                WebViewMetricsCleaner.onBackground(this@MyApplication)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
