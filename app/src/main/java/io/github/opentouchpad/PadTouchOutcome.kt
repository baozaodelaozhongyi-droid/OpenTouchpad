package io.github.opentouchpad

enum class PadTouchOutcome {
    CLICK,
    LONG_PRESS,
    CUSTOM_SWIPE,
    MOVE_ONLY,
}

internal fun resolvePadTouchOutcome(
    longPressFired: Boolean,
    moved: Boolean,
    distancePx: Float,
    minSwipeDistancePx: Float,
): PadTouchOutcome = when {
    moved && distancePx >= minSwipeDistancePx && longPressFired -> PadTouchOutcome.CUSTOM_SWIPE
    moved -> PadTouchOutcome.MOVE_ONLY
    longPressFired -> PadTouchOutcome.LONG_PRESS
    else -> PadTouchOutcome.CLICK
}
