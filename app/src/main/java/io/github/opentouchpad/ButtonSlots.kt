package io.github.opentouchpad

/**
 * 18 个按键位置：TOP 1-4, BOTTOM 1-4, LEFT 1-2, RIGHT 1-2, 四角 TL TR BL BR,
 * 左列上方（默认移动键）, 右列下方（默认缩放键）。顺序和 [computeControlLayout] 的 slots 一致。
 */
internal const val BUTTON_SLOT_COUNT = 18
internal const val MOVE_GRIP_SLOT = 16
internal const val RESIZE_GRIP_SLOT = 17

/** 早期版本存 12 个位置，加了四角后存 16 个；那时移动键、缩放键是固定的，不在存储里。 */
private const val LEGACY_SLOT_COUNT = 12
private const val PRE_GRIP_SLOT_COUNT = 16

/**
 * 每个按键位置在 6 列 × 5 行网格里的 (列, 行)；中间 4 × 3 是触控板，外圈 18 格正好全是按键。
 * 设置页缩略图直接用这张表，单元测试核对它和 [computeControlLayout] 一致。
 */
internal val BUTTON_SLOT_CELLS: List<Pair<Int, Int>> =
    (1..4).map { it to 0 } + (1..4).map { it to 4 } +
        listOf(0 to 2, 0 to 3, 5 to 1, 5 to 2) +
        listOf(0 to 0, 5 to 0, 0 to 4, 5 to 4) +
        listOf(0 to 1, 5 to 3)

internal fun decodeButtonSlots(raw: String?): List<PadAction> {
    if (raw == null) return PadAction.DEFAULT

    val decoded = raw.split(',').map { PadAction.fromId(it) }
    if (decoded.none { it != null }) return PadAction.DEFAULT

    // 认不出的动作留空，不让后面的按钮挪位
    val kept = decoded.take(BUTTON_SLOT_COUNT).map { it ?: PadAction.NONE }
    // 老配置自动补齐：12 个位置补四角和移动/缩放键，16 个位置补移动/缩放键，都放在原来的默认位置
    val filled = if (kept.size >= LEGACY_SLOT_COUNT) {
        kept + PadAction.DEFAULT.drop(kept.size)
    } else {
        kept + List(PRE_GRIP_SLOT_COUNT - kept.size) { PadAction.NONE } + PadAction.DEFAULT.drop(PRE_GRIP_SLOT_COUNT)
    }
    return ensureMoveKey(filled)
}

/** 面板至少要有一个移动键，否则再也拖不动；万一一个都没有（存储损坏），放回默认位置。 */
internal fun ensureMoveKey(slots: List<PadAction>): List<PadAction> {
    if (PadAction.MOVE_PANEL in slots) return slots
    val list = slots.take(BUTTON_SLOT_COUNT).toMutableList()
    while (list.size < BUTTON_SLOT_COUNT) list.add(PadAction.NONE)
    list[MOVE_GRIP_SLOT] = PadAction.MOVE_PANEL
    return list
}

/**
 * 把 [slot] 改成 [picked]。会去掉最后一个「移动触控板」键时拒绝，返回 null（界面上提示用户）；
 * 缩放键不受限制。[slot] 越界也返回 null。
 */
internal fun replaceSlot(current: List<PadAction>, slot: Int, picked: PadAction): List<PadAction>? {
    if (slot !in 0 until BUTTON_SLOT_COUNT) return null
    val list = current.take(BUTTON_SLOT_COUNT).toMutableList()
    while (list.size < BUTTON_SLOT_COUNT) list.add(PadAction.NONE)
    list[slot] = picked
    return if (PadAction.MOVE_PANEL in list) list else null
}
