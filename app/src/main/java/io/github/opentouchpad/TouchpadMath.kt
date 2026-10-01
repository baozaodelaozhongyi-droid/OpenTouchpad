package io.github.opentouchpad

import kotlin.math.roundToInt

internal data class PanelPosition(val x: Int, val y: Int)

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
    !dragging && dwellMs <= 0

internal fun resizePanelHeight(startPx: Int, deltaY: Int, minPx: Int, maxPx: Int): Int =
    (startPx - deltaY).coerceIn(minPx, maxPx)
