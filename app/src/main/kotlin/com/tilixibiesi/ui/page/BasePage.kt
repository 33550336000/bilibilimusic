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

/**
 * 页面基类：把原本独立的 Activity 降格为「托管在同一个 Activity 内的一页」。
 *
 * 为什么要这么做：
 * 跨 Activity 无法实现「页面跟手拖动」——一旦 startActivity，旧 Activity 立刻进入
 * onPause 且不再收到任何 touch 事件，只剩下一段固定时长的 overridePendingTransition 动画。
 * 要让手指移动与页面位移 1:1 对应，多个页面必须共处同一个 Window。
 *
 * 本类继承 ContextWrapper，使页面内可以像 Activity 一样直接把 `this` 当 Context 使用
 * （Toast / AlertDialog / inflate / getSystemService 等全部原样可用），
 * 同时补齐 Activity 常用成员（findViewById / setContentView /
 * startActivityForResult / registerReceiver 等），让原 Activity 代码近乎原样迁移。
 *
 * 生命周期语义：
 *   onCreate(savedInstanceState) —— 与 Activity.onCreate 一致（此时已完成 inflate）
 *   onPageShow / onPageHide      —— 切页显隐回调
 *   onResume / onPause           —— 由宿主在「当前页」上回调
 *   onDestroy                    —— 宿主 Activity 销毁时回调
 */
abstract class BasePage(base: Context) : ContextWrapper(base) {

    /** 宿主 Activity */
    val activity: Activity
        get() = baseContext as? Activity
            ?: error("BasePage 必须以 Activity 作为 baseContext")

    /** 页面视图（setContentView 时赋值） */
    var pageView: View? = null
        internal set

    /** 页面是否已完成 onCreate */
    var created: Boolean = false
        internal set

    /** 页面宿主（提供切页、转发 Activity 结果等能力） */
    var host: PageHost? = null
        internal set

    /** 页面启动时携带的 Intent（宿主可更新，对应 Activity.setIntent） */
    var intent: Intent = Intent()
        internal set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val receivers = mutableListOf<BroadcastReceiver>()

    // ==================== 与 Activity 对齐的兼容 API ====================

    /** 覆盖 resources，使 getString / XML 取串都走语言 JSON 覆盖（与 BaseActivity 一致） */
    private var localizedResources: Resources? = null

    override fun getResources(): Resources {
        var res = localizedResources
        if (res == null) {
            res = LocalizedResources(super.getResources())
            localizedResources = res
        }
        return res
    }

    /** 页面 inflater：已安装布局本地化工厂 */
    val layoutInflater: LayoutInflater
        get() = LayoutInflater.from(this).also { LocalizedViewFactory.install(it) }

    fun <T : View> findViewById(id: Int): T? = pageView?.findViewById(id)

    /** inflate 布局并作为页面视图（对应 Activity.setContentView） */
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

    /**
     * 注册广播（对应 Activity.registerReceiver）。
     * 注：不命名为 registerReceiver —— ContextWrapper 中它是多个重载，无法安全覆写，
     * 因此用独立方法名，页面调用点也更清晰。
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag", "WrongConstant")
    fun registerPageReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        // 页面级广播同样显式声明导出状态；Android 13(API 33) 起为强制要求。
        // RECEIVER_EXPORTED(==2) 属 API 33 常量，compileSdk 30 下用等值字面量。
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
        receivers.add(receiver)
    }

    private companion object {
        /** 等价于 Context.RECEIVER_EXPORTED（API 33 新增），值固定为 2 */
        const val RECEIVER_EXPORTED = 2
    }

    fun unregisterPageReceiver(receiver: BroadcastReceiver) {
        runCatching { activity.unregisterReceiver(receiver) }
        receivers.remove(receiver)
    }

    /** 切换到指定页 */
    fun gotoPage(position: Int, smooth: Boolean = true) {
        host?.setPage(position, smooth)
    }

    // ==================== 生命周期 ====================

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

    /** 返回键：返回 true 表示已消费（默认不消费） */
    open fun onBackPressed(): Boolean = false

    open fun onConfigurationChanged(newConfig: Configuration) {}
    open fun onNewIntent(intent: Intent) {}
    open fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false

    // ==================== 内部流程 ====================

    /** 由宿主调用：创建页面视图。 */
    internal fun performCreate(host: PageHost, savedInstanceState: Bundle?): View {
        this.host = host
        LocalizedViewFactory.install(LayoutInflater.from(this))
        onCreate(savedInstanceState)
        val v = pageView ?: error("${javaClass.simpleName} 未在 onCreate 中调用 setContentView")
        created = true
        return v
    }
}

/** 页面宿主能力，由承载多个页面的 Activity 实现 */
interface PageHost {
    /** 切到指定页 */
    fun setPage(position: Int, smooth: Boolean = true)

    /** 以真实 Activity 方式启动并回传结果 */
    fun startActivityForResult(page: BasePage, intent: Intent, requestCode: Int)
}
