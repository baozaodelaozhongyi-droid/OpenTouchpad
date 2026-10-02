package io.github.opentouchpad

enum class PadTouchOutcome {
    CLICK,
    LONG_PRESS,
    CUSTOM_SWIPE,
    DWELL_CLICK,
    MOVE_ONLY,
}

internal fun resolvePadTouchOutcome(
    longPressFired: Boolean,
    dwellFired: Boolean,
    moved: Boolean,
    distancePx: Float,
    minSwipeDistancePx: Float,
): PadTouchOutcome = when {
    dwellFired -> PadTouchOutcome.DWELL_CLICK
    moved && distancePx >= minSwipeDistancePx && longPressFired -> PadTouchOutcome.CUSTOM_SWIPE
    moved -> PadTouchOutcome.MOVE_ONLY
    longPressFired -> PadTouchOutcome.LONG_PRESS
    else -> PadTouchOutcome.CLICK
}
