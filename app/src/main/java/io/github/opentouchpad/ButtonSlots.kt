package io.github.opentouchpad

import android.content.Context

enum class ButtonLayoutMode(val count: Int) {
    FOUR(4),
    EIGHT(8),
    TWELVE(12),
    SIXTEEN(16);

    val edgeCount: Int get() = count / 4

    companion object {
        val ALL = entries.toList()
        fun fromCount(c: Int): ButtonLayoutMode = entries.firstOrNull { it.count == c } ?: SIXTEEN
    }
}

/** 默认 4 键位：上下左右各一个气泡/胶囊型按钮。首键为移动键。 */
internal val DEFAULT_BUTTONS_4: List<PadAction> = listOf(
    PadAction.MOVE_PANEL, // 上
    PadAction.HOME,       // 下
    PadAction.BACK,       // 左
    PadAction.RECENTS,    // 右
)

/** 默认 8 键位：上下左右各两个紧凑气泡/胶囊型按钮。 */
internal val DEFAULT_BUTTONS_8: List<PadAction> = listOf(
    // 上 (2)
    PadAction.MOVE_PANEL, PadAction.MINIMIZE,
    // 下 (2)
    PadAction.BACK, PadAction.HOME,
    // 左 (2)
    PadAction.NOTIFICATIONS, PadAction.SCREENSHOT,
    // 右 (2)
    PadAction.SCROLL_UP, PadAction.SCROLL_DOWN,
)

/** 默认 12 键位：上下左右各三个紧凑气泡/胶囊型按钮。 */
internal val DEFAULT_BUTTONS_12: List<PadAction> = listOf(
    // 上 (3)
    PadAction.MOVE_PANEL, PadAction.NOTIFICATIONS, PadAction.MINIMIZE,
    // 下 (3)
    PadAction.BACK, PadAction.HOME, PadAction.RECENTS,
    // 左 (3)
    PadAction.SCROLL_UP, PadAction.SCROLL_DOWN, PadAction.SCREENSHOT,
    // 右 (3)
    PadAction.VOLUME_UP, PadAction.VOLUME_DOWN, PadAction.RESIZE_PANEL,
)

/** 默认 16 键位：上下左右各四个按钮，从胶囊型压缩成圆形。 */
internal val DEFAULT_BUTTONS_16: List<PadAction> = listOf(
    // 上 (4)
    PadAction.MOVE_PANEL, PadAction.CLICK, PadAction.LONG_PRESS, PadAction.MINIMIZE,
    // 下 (4)
    PadAction.BACK, PadAction.HOME, PadAction.RECENTS, PadAction.RESIZE_PANEL,
    // 左 (4)
    PadAction.NOTIFICATIONS, PadAction.SCREENSHOT, PadAction.KEYBOARD, PadAction.POWER,
    // 右 (4)
    PadAction.SCROLL_UP, PadAction.SCROLL_DOWN, PadAction.VOLUME_UP, PadAction.VOLUME_DOWN,
)

internal fun defaultButtonsFor(count: Int): List<PadAction> = when (count) {
    4 -> DEFAULT_BUTTONS_4
    8 -> DEFAULT_BUTTONS_8
    12 -> DEFAULT_BUTTONS_12
    else -> DEFAULT_BUTTONS_16
}

internal fun decodeButtonSlots(raw: String?, count: Int = 16): List<PadAction> {
    val defaults = defaultButtonsFor(count)
    if (raw == null) return defaults

    val decoded = raw.split(',').map { PadAction.fromId(it) }
    if (decoded.none { it != null }) return defaults

    val kept = decoded.take(count).map { it ?: PadAction.NONE }
    val filled = if (kept.size >= count) kept else kept + defaults.drop(kept.size)
    return ensureMoveKey(filled, count)
}

/** 面板至少要有一个移动键，否则再也拖不动；万一一个都没有，放回首个按键位置。 */
internal fun ensureMoveKey(slots: List<PadAction>, count: Int = slots.size): List<PadAction> {
    val actualCount = count.coerceAtLeast(1)
    val list = slots.take(actualCount).toMutableList()
    while (list.size < actualCount) list.add(PadAction.NONE)
    if (PadAction.MOVE_PANEL in list) return list
    list[0] = PadAction.MOVE_PANEL
    return list
}

/**
 * 把 [slot] 改成 [picked]。会去掉最后一个「移动触控板」键时拒绝，返回 null；
 * 越界也返回 null。
 */
internal fun replaceSlot(current: List<PadAction>, slot: Int, picked: PadAction, count: Int = current.size): List<PadAction>? {
    if (slot !in 0 until count) return null
    val list = current.take(count).toMutableList()
    while (list.size < count) list.add(PadAction.NONE)
    list[slot] = picked
    return if (PadAction.MOVE_PANEL in list) list else null
}

/** 获取各槽位的无障碍/说明文本。 */
internal fun slotPositionDescription(index: Int, count: Int, context: Context): String {
    val edgeCount = (count / 4).coerceAtLeast(1)
    val edge = index / edgeCount
    val edgeIndex = (index % edgeCount) + 1
    val res = if (edgeCount == 1) {
        when (edge) {
            0 -> R.string.slot_top_single
            1 -> R.string.slot_bottom_single
            2 -> R.string.slot_left_single
            else -> R.string.slot_right_single
        }
    } else {
        when (edge) {
            0 -> R.string.slot_top_indexed
            1 -> R.string.slot_bottom_indexed
            2 -> R.string.slot_left_indexed
            else -> R.string.slot_right_indexed
        }
    }
    return if (edgeCount == 1) context.getString(res) else context.getString(res, edgeIndex)
}
