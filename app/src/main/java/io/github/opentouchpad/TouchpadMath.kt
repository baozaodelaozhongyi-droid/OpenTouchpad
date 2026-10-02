package io.github.opentouchpad

import kotlin.math.roundToInt

internal const val PANEL_MIN_WIDTH_DP = 110
internal const val CONTROL_MIN_SIZE_DP = 110
internal const val CONTROL_MAX_SIZE_DP = 2400
internal const val FLOATING_BALL_MIN_DP = 16
internal const val FLOATING_BALL_MAX_DP = 140
internal const val CURSOR_MIN_DP = 8
internal const val CURSOR_MAX_DP = 160
internal const val BUTTON_SPACING_MAX_DP = 24
internal const val DEFAULT_PANEL_WIDTH_DP = 168
internal const val CONTROL_EXTRA_HEIGHT_MAX_DP = 400
internal const val DEFAULT_BUTTON_SPACING_DP = 3

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
 * [slots] order: TOP 1-4, BOTTOM 1-4, LEFT 1-2, RIGHT 1-2, corners TL, TR, BL, BR.
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

internal const val CONTROL_EDGE_DP = 1
internal const val CONTROL_BUTTON_MAX_DP = 46
internal const val CONTROL_BUTTON_MIN_DP = 16

/** 6 列 × 5 行网格：按钮直径只由面板宽度和间距决定。 */
internal fun controlButtonSize(width: Int, density: Float, gapPx: Int): Int {
    val edge = (CONTROL_EDGE_DP * density).roundToInt()
    return ((width - 2 * edge - 5 * gapPx) / 6)
        .coerceIn((CONTROL_BUTTON_MIN_DP * density).roundToInt(), (CONTROL_BUTTON_MAX_DP * density).roundToInt())
}

/** 网格最紧凑时的面板高度（触控板高度 = 3 个按钮 + 2 个间距）。 */
internal fun controlGridHeight(width: Int, density: Float, gapPx: Int): Int {
    val edge = (CONTROL_EDGE_DP * density).roundToInt()
    return 2 * edge + 5 * controlButtonSize(width, density, gapPx) + 4 * gapPx
}

/**
 * Uniform grid layout. Every neighbour distance — button↔button and button↔touchpad — is
 * exactly [spacingPx], in rows, side columns and corners alike.
 *
 * Columns: corner/left | top 1..4 | corner/right. Rows: corner/top | left/right ×3 | corner/bottom.
 * Left column = move grip, LEFT 1, LEFT 2. Right column = RIGHT 1, RIGHT 2, resize grip.
 * Height above [controlGridHeight] only makes the touchpad taller; side-column buttons stay
 * packed with the same gap and are centred vertically.
 */
internal fun computeControlLayout(width: Int, height: Int, density: Float, spacingPx: Int): ControlLayout {
    val edge = (CONTROL_EDGE_DP * density).roundToInt()
    val gap = spacingPx.coerceAtLeast(0)
    val b = controlButtonSize(width, density, gap)
    val step = b + gap
    // 宽度被最小按钮尺寸卡住时，整体水平居中
    val gridW = 6 * b + 5 * gap
    val x0 = ((width - gridW) / 2).coerceAtLeast(0)
    val colX = List(6) { x0 + it * step }
    val topY = edge
    val bottomY = (height - edge - b).coerceAtLeast(topY + 4 * step)
    val padTop = topY + step
    val padH = bottomY - gap - padTop
    val pad = ControlRect(colX[1], padTop, 4 * b + 3 * gap, padH)
    // 侧列 3 个按钮按同样间距紧排，并在触控板高度内居中
    val sideStart = padTop + (padH - (3 * b + 2 * gap)) / 2
    val sideY = List(3) { sideStart + it * step }
    fun at(x: Int, y: Int) = ControlRect(x, y, b, b)
    val leftX = colX[0]
    val rightX = colX[5]
    val slots = (1..4).map { at(colX[it], topY) } +
        (1..4).map { at(colX[it], bottomY) } +
        listOf(at(leftX, sideY[1]), at(leftX, sideY[2])) +
        listOf(at(rightX, sideY[0]), at(rightX, sideY[1])) +
        listOf(at(leftX, topY), at(rightX, topY), at(leftX, bottomY), at(rightX, bottomY))
    return ControlLayout(
        button = b,
        pad = pad,
        moveGrip = at(leftX, sideY[0]),
        resizeGrip = at(rightX, sideY[2]),
        slots = slots,
    )
}

internal fun controlHeightRange(screenHeight: Int, density: Float): IntRange {
    val minPx = (CONTROL_MIN_SIZE_DP * density).roundToInt()
    val maxPx = minOf((CONTROL_MAX_SIZE_DP * density).roundToInt(), screenHeight)
    return minPx.coerceAtMost(maxPx)..maxPx
}
