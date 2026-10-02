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
    /** 纯文本场景（无障碍朗读、旧版列表）用的字符图标。 */
    val icon: String,
    /** 面板按钮和设置页用的线条图标（Lucide 风格，运行时着色）。 */
    val iconRes: Int,
) {
    CLICK("click", R.string.act_click, "●", R.drawable.ic_lu_mouse_pointer_click),
    LONG_PRESS("longpress", R.string.act_long_press, "◎", R.drawable.ic_lu_pointer),
    DRAG_LOCK("drag", R.string.act_drag_lock, "✥", R.drawable.ic_lu_hand),

    SCROLL_UP("scrollup", R.string.act_scroll_up, "↑", R.drawable.ic_lu_chevron_up),
    SCROLL_DOWN("scrolldown", R.string.act_scroll_down, "↓", R.drawable.ic_lu_chevron_down),
    SCROLL_LEFT("scrollleft", R.string.act_scroll_left, "←", R.drawable.ic_lu_chevron_left),
    SCROLL_RIGHT("scrollright", R.string.act_scroll_right, "→", R.drawable.ic_lu_chevron_right),

    SWIPE_UP("swipeup", R.string.act_swipe_up, "⤒", R.drawable.ic_lu_arrow_up_to_line),
    SWIPE_DOWN("swipedown", R.string.act_swipe_down, "⤓", R.drawable.ic_lu_arrow_down_to_line),
    SWIPE_LEFT("swipeleft", R.string.act_swipe_left, "⇤", R.drawable.ic_lu_arrow_left_to_line),
    SWIPE_RIGHT("swiperight", R.string.act_swipe_right, "⇥", R.drawable.ic_lu_arrow_right_to_line),

    NOTIFICATIONS("notifications", R.string.act_notifications, "☰", R.drawable.ic_lu_bell),
    POWER("power", R.string.act_power, "⏻", R.drawable.ic_lu_power),
    VOLUME_UP("volup", R.string.act_volume_up, "＋", R.drawable.ic_lu_volume_2),
    VOLUME_DOWN("voldown", R.string.act_volume_down, "－", R.drawable.ic_lu_volume_1),
    SCREENSHOT("screenshot", R.string.act_screenshot, "▣", R.drawable.ic_lu_scan),
    KEYBOARD("keyboard", R.string.act_keyboard, "⌨", R.drawable.ic_lu_keyboard),

    BACK("back", R.string.act_back, "◀", R.drawable.ic_lu_undo_2),
    HOME("home", R.string.act_home, "⌂", R.drawable.ic_lu_house),
    RECENTS("recents", R.string.act_recents, "▢", R.drawable.ic_lu_layers_2),

    SETTINGS("settings", R.string.act_settings, "⚙", R.drawable.ic_lu_settings),
    MINIMIZE("minimize", R.string.act_minimize, "✕", R.drawable.ic_lu_chevrons_down_up),
    NONE("none", R.string.act_none, "·", R.drawable.ic_lu_plus),
    ;

    companion object {
        fun fromId(id: String?): PadAction? = entries.firstOrNull { it.id == id }

        /** 默认按钮布局，顺序见 [BUTTON_SLOT_COUNT]：上 4、下 4、左 2、右 2、四角。 */
        val DEFAULT: List<PadAction> = listOf(
            CLICK, LONG_PRESS, DRAG_LOCK, SCROLL_UP, SCROLL_DOWN, NOTIFICATIONS,
            BACK, HOME, RECENTS, KEYBOARD, SCREENSHOT, MINIMIZE,
            SCROLL_LEFT, SCROLL_RIGHT, SWIPE_LEFT, SWIPE_RIGHT,
        )

        val ALL: List<PadAction> = entries.toList()
    }
}
