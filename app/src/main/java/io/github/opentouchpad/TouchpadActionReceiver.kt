package io.github.opentouchpad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收来自通知栏快捷操作的广播（例如展开/收起触控板面板）。
 */
class TouchpadActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TOGGLE_PANEL = "io.github.opentouchpad.ACTION_TOGGLE_PANEL"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_TOGGLE_PANEL) {
            TouchpadService.instance?.toggleMinimize()
        }
    }
}
