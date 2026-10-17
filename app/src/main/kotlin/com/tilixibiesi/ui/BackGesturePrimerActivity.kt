package com.tilixibiesi.ui

import android.app.Activity
import android.os.Bundle

/**
 * 一次性的「让窗口走一遍 pause→resume」的空 Activity。
 *
 * 系统只有在窗口完成过一次真正的生命周期往返之后，才把该窗口纳入返回动画控制；
 * 在那之前注册的返回回调虽然能收到，但 [android.window.BackEvent] 的 progress 恒为 0，
 * 表现为「能返回却没有跟手预演」，只有离开主活动再回来才恢复。
 *
 * 这个 Activity 完全透明、不参与转场，启动后立刻结束自己，
 * 借这一次往返把主活动「预热」好，用户看不到任何中间态。
 *
 * 只在冷启动时由 [MainPagerActivity] 拉起一次，之后不再使用。
 */
class BackGesturePrimerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 立刻结束：本次往返已经让主活动走完 pause→resume
        finish()
        overridePendingTransition(0, 0)
    }
}
