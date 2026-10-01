package io.github.opentouchpad

import android.accessibilityservice.AccessibilityService

/**
 * 触控板按钮可以绑定的动作。
 *
 * 设计上刻意和原应用的功能对齐（通知栏、电源菜单、音量、截图、键盘、四方向滑动等），
 * 但实现全部是独立写的：不引用、不复制原应用的任何代码或资源。
 */
enum class PadAction(
    val id: String,
    val labelRes: Int,
    val icon: String,
) {
    CLICK("click", R.string.act_click, "●"),
    LONG_PRESS("longpress", R.string.act_long_press, "◎"),
    DRAG_LOCK("drag", R.string.act_drag_lock, "✥"),

    SCROLL_UP("scrollup", R.string.act_scroll_up, "↑"),
    SCROLL_DOWN("scrolldown", R.string.act_scroll_down, "↓"),
    SCROLL_LEFT("scrollleft", R.string.act_scroll_left, "←"),
    SCROLL_RIGHT("scrollright", R.string.act_scroll_right, "→"),

    SWIPE_UP("swipeup", R.string.act_swipe_up, "⤒"),
    SWIPE_DOWN("swipedown", R.string.act_swipe_down, "⤓"),
    SWIPE_LEFT("swipeleft", R.string.act_swipe_left, "⇤"),
    SWIPE_RIGHT("swiperight", R.string.act_swipe_right, "⇥"),

    NOTIFICATIONS("notifications", R.string.act_notifications, "☰"),
    POWER("power", R.string.act_power, "⏻"),
    VOLUME_UP("volup", R.string.act_volume_up, "＋"),
    VOLUME_DOWN("voldown", R.string.act_volume_down, "－"),
    SCREENSHOT("screenshot", R.string.act_screenshot, "▣"),
    KEYBOARD("keyboard", R.string.act_keyboard, "⌨"),

    BACK("back", R.string.act_back, "◀"),
    HOME("home", R.string.act_home, "⌂"),
    RECENTS("recents", R.string.act_recents, "▢"),

    SETTINGS("settings", R.string.act_settings, "⚙"),
    MINIMIZE("minimize", R.string.act_minimize, "✕"),
    NONE("none", R.string.act_none, "·"),
    ;

    companion object {
        fun fromId(id: String?): PadAction? = entries.firstOrNull { it.id == id }

        /** 默认按钮布局：第一行点击族，第二行系统动作。 */
        val DEFAULT: List<PadAction> = listOf(
            CLICK, LONG_PRESS, DRAG_LOCK, SCROLL_UP, SCROLL_DOWN, NOTIFICATIONS,
            BACK, HOME, RECENTS, KEYBOARD, SCREENSHOT, MINIMIZE,
        )

        val ALL: List<PadAction> = entries.toList()
    }
}
