package io.github.opentouchpad

/** TOP 1-4, BOTTOM 1-4, LEFT 1-2, RIGHT 1-2, corners TL, TR, BL, BR. */
internal const val BUTTON_SLOT_COUNT = 16
private const val LEGACY_SLOT_COUNT = 12

internal fun decodeButtonSlots(raw: String?): List<PadAction> {
    if (raw == null) return PadAction.DEFAULT

    val decoded = raw.split(',').map { PadAction.fromId(it) }
    if (decoded.none { it != null }) return PadAction.DEFAULT

    val kept = decoded.take(BUTTON_SLOT_COUNT).map { it ?: PadAction.NONE }
    // 旧版本存的是 12 个槽位：新增的四角槽位补上默认动作。
    return if (kept.size >= LEGACY_SLOT_COUNT) kept + PadAction.DEFAULT.drop(kept.size) else kept
}
