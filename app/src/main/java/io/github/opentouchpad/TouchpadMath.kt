package io.github.opentouchpad

import kotlin.math.roundToInt

internal const val PANEL_MIN_WIDTH_DP = 180
internal const val CONTROL_MIN_SIZE_DP = 180
internal const val CONTROL_MAX_SIZE_DP = 2400
internal const val FLOATING_BALL_MIN_DP = 16
internal const val FLOATING_BALL_MAX_DP = 140
internal const val CURSOR_MIN_DP = 8
internal const val CURSOR_MAX_DP = 160
internal const val BUTTON_SPACING_MAX_DP = 80

internal data class PanelPosition(val x: Int, val y: Int)

internal data class CustomSwipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float) {
    val distance: Float
        get() = kotlin.math.hypot(endX - startX, endY - startY)
}

internal fun customSwipeFrom(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    minDistancePx: Float,
): CustomSwipe? {
    val swipe = CustomSwipe(startX, startY, endX, endY)
    return swipe.takeIf { it.distance >= minDistancePx }
}

internal fun cursorViewOrigin(cursorX: Float, cursorY: Float): PanelPosition =
    PanelPosition(cursorX.roundToInt(), cursorY.roundToInt())

internal fun clampPanelPosition(
    x: Int,
    y: Int,
    panelWidth: Int,
    panelHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): PanelPosition {
    val maxX = (screenWidth - panelWidth.coerceAtLeast(0)).coerceAtLeast(0)
    val maxY = (screenHeight - panelHeight.coerceAtLeast(0)).coerceAtLeast(0)
    return PanelPosition(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
}

internal fun pixelsToDp(pixels: Int, density: Float): Int =
    if (density > 0f) (pixels / density).roundToInt().coerceAtLeast(1) else pixels

internal fun shouldScheduleLongPress(dwellMs: Int, dragging: Boolean): Boolean =
    !dragging

internal fun resizePanelHeight(startPx: Int, deltaY: Int, minPx: Int, maxPx: Int): Int =
    (startPx - deltaY).coerceIn(minPx, maxPx)

internal fun resizePanelWidth(startPx: Int, deltaX: Int, minPx: Int, maxPx: Int): Int =
    (startPx + deltaX).coerceIn(minPx, maxPx)

internal fun clampFloatingBallPosition(
    x: Int,
    y: Int,
    ballSize: Int,
    screenWidth: Int,
    screenHeight: Int,
): PanelPosition {
    val maxX = (screenWidth - ballSize.coerceAtLeast(0)).coerceAtLeast(0)
    val maxY = (screenHeight - ballSize.coerceAtLeast(0)).coerceAtLeast(0)
    return PanelPosition(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
}

internal fun controlWidthRange(screenWidth: Int, density: Float): IntRange {
    val minPx = (CONTROL_MIN_SIZE_DP * density).roundToInt()
    val maxPx = minOf((CONTROL_MAX_SIZE_DP * density).roundToInt(), screenWidth)
    return minPx.coerceAtMost(maxPx)..maxPx
}

internal fun resizeControlHeight(startPx: Int, deltaY: Int, minPx: Int, maxPx: Int): Int =
    (startPx + deltaY).coerceIn(minPx, maxPx)

internal data class ControlRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + w && py >= y && py < y + h
}

/**
 * Geometry of the full control surface.
 * [slots] order: TOP 1-4, BOTTOM 1-4, LEFT 1-2, RIGHT 1-2.
 * Button size and the touchpad rectangle depend only on the panel size; [spacingPx]
 * only changes the distance between neighbouring buttons inside each row/column.
 */
internal data class ControlLayout(
    val button: Int,
    val pad: ControlRect,
    val moveGrip: ControlRect,
    val resizeGrip: ControlRect,
    val slots: List<ControlRect>,
)

internal fun computeControlLayout(width: Int, height: Int, density: Float, spacingPx: Int): ControlLayout {
    fun d(v: Int) = (v * density).roundToInt()
    val edge = d(4)
    val inner = d(6)
    val button = minOf(
        d(72),
        (width - 2 * edge - 2 * inner) / 6,
        (height - 2 * edge - 2 * inner) / 4,
    ).coerceAtLeast(d(16))
    val padLeft = edge + button + inner
    val padTop = edge + button + inner
    val padW = (width - 2 * padLeft).coerceAtLeast(d(40))
    val padH = (height - 2 * padTop).coerceAtLeast(d(40))

    fun spread(count: Int, start: Int, end: Int): List<Int> {
        val avail = end - start
        val maxGap = if (count > 1) ((avail - count * button) / (count - 1)).coerceAtLeast(0) else 0
        val gap = spacingPx.coerceIn(0, maxGap)
        val group = count * button + (count - 1) * gap
        val s = start + (avail - group) / 2
        return List(count) { s + it * (button + gap) }
    }

    val rowXs = spread(4, padLeft, padLeft + padW)
    val colYs = spread(2, padTop, padTop + padH)
    val topY = edge
    val bottomY = height - edge - button
    val leftX = edge
    val rightX = width - edge - button
    val slots = rowXs.map { ControlRect(it, topY, button, button) } +
        rowXs.map { ControlRect(it, bottomY, button, button) } +
        colYs.map { ControlRect(leftX, it, button, button) } +
        colYs.map { ControlRect(rightX, it, button, button) }
    return ControlLayout(
        button = button,
        pad = ControlRect(padLeft, padTop, padW, padH),
        moveGrip = ControlRect(leftX, topY, button, button),
        resizeGrip = ControlRect(rightX, bottomY, button, button),
        slots = slots,
    )
}

internal fun controlHeightRange(screenHeight: Int, density: Float): IntRange {
    val minPx = (CONTROL_MIN_SIZE_DP * density).roundToInt()
    val maxPx = minOf((CONTROL_MAX_SIZE_DP * density).roundToInt(), screenHeight)
    return minPx.coerceAtMost(maxPx)..maxPx
}
