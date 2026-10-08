package com.tilixibiesi.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import com.tilixibiesi.data.LocalizedResources
import com.tilixibiesi.ui.LocalizedViewFactory

abstract class BasePage(base: Context) : ContextWrapper(base) {

    val activity: Activity
        get() = baseContext as? Activity
            ?: error("BasePage 必须以 Activity 作为 baseContext")

    var pageView: View? = null
        internal set

    var created: Boolean = false
        internal set

    var host: PageHost? = null
        internal set

    var intent: Intent = Intent()
        internal set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val receivers = mutableListOf<BroadcastReceiver>()


    private var localizedResources: Resources? = null

    override fun getResources(): Resources {
        var res = localizedResources
        if (res == null) {
            res = LocalizedResources(super.getResources())
            localizedResources = res
        }
        return res
    }

    val layoutInflater: LayoutInflater
        get() = LayoutInflater.from(this).also { LocalizedViewFactory.install(it) }

    fun <T : View> findViewById(id: Int): T? = pageView?.findViewById(id)

    fun setContentView(layoutResId: Int) {
        pageView = layoutInflater.inflate(layoutResId, null)
    }

    fun setContentView(v: View) {
        pageView = v
    }

    fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    fun post(action: () -> Unit) = mainHandler.post(action)

    fun postDelayed(delayMs: Long, action: () -> Unit) = mainHandler.postDelayed(action, delayMs)

    fun setVolumeControlStream(streamType: Int) {
        activity.volumeControlStream = streamType
    }

    fun startActivityForResult(intent: Intent, requestCode: Int) {
        host?.startActivityForResult(this, intent, requestCode)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag", "WrongConstant")
    fun registerPageReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
        receivers.add(receiver)
    }

    private companion object {
        const val RECEIVER_EXPORTED = 2
    }

    fun unregisterPageReceiver(receiver: BroadcastReceiver) {
        runCatching { activity.unregisterReceiver(receiver) }
        receivers.remove(receiver)
    }

    fun gotoPage(position: Int, smooth: Boolean = true) {
        host?.setPage(position, smooth)
    }


    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onPageShow() {}
    open fun onPageHide() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onDestroy() {
        receivers.toList().forEach { unregisterPageReceiver(it) }
    }

    open fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {}
    open fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
    }

    open fun onBackPressed(): Boolean = false

    open fun onConfigurationChanged(newConfig: Configuration) {}
    open fun onNewIntent(intent: Intent) {}
    open fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false


    internal fun performCreate(host: PageHost, savedInstanceState: Bundle?): View {
        this.host = host
        LocalizedViewFactory.install(LayoutInflater.from(this))
        onCreate(savedInstanceState)
        val v = pageView ?: error("${javaClass.simpleName} 未在 onCreate 中调用 setContentView")
        created = true
        return v
    }
}

interface PageHost {
    fun setPage(position: Int, smooth: Boolean = true)

    fun startActivityForResult(page: BasePage, intent: Intent, requestCode: Int)
}
