package io.github.opentouchpad

private const val BUTTON_SLOT_COUNT = 12

internal fun decodeButtonSlots(raw: String?): List<PadAction> {
    if (raw == null) return PadAction.DEFAULT

    val decoded = raw.split(',').map { PadAction.fromId(it) }
    if (decoded.none { it != null }) return PadAction.DEFAULT

    return decoded
        .take(BUTTON_SLOT_COUNT)
        .map { it ?: PadAction.NONE }
}
