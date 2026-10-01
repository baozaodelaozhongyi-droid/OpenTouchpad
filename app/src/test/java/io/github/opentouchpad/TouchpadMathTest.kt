package io.github.opentouchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadMathTest {
    @Test
    fun arrowCursorUsesItsTipAsThePointerHotspot() {
        assertEquals(96, cursorViewOrigin(100f, 100f, 50).x)
        assertEquals(98, cursorViewOrigin(100f, 100f, 50).y)
    }

    @Test
    fun panelPositionIsKeptInsideScreen() {
        assertEquals(
            PanelPosition(0, 1200),
            clampPanelPosition(-40, 1400, 380, 720, 1080, 1920),
        )
    }

    @Test
    fun pixelsToDpUsesFractionalDensity() {
        assertEquals(36, pixelsToDp(100, 2.75f))
    }

    @Test
    fun dwellModeReplacesLongPressScheduling() {
        assertFalse(shouldScheduleLongPress(dwellMs = 800, dragging = false))
        assertTrue(shouldScheduleLongPress(dwellMs = 0, dragging = false))
        assertFalse(shouldScheduleLongPress(dwellMs = 0, dragging = true))
    }

    @Test
    fun resizeDeltaIsClampedWithoutRebuildingThePanel() {
        assertEquals(300, resizePanelHeight(startPx = 240, deltaY = -60, minPx = 80, maxPx = 960))
        assertEquals(80, resizePanelHeight(startPx = 240, deltaY = 500, minPx = 80, maxPx = 960))
    }

    @Test
    fun panelWidthResizeGrowsWithRightwardDragAndClampsToBounds() {
        assertEquals(520, resizePanelWidth(startPx = 420, deltaX = 100, minPx = 280, maxPx = 720))
        assertEquals(280, resizePanelWidth(startPx = 420, deltaX = -300, minPx = 280, maxPx = 720))
        assertEquals(720, resizePanelWidth(startPx = 420, deltaX = 500, minPx = 280, maxPx = 720))
    }

    @Test
    fun floatingBallPositionIsKeptInsideScreen() {
        assertEquals(
            PanelPosition(900, 1740),
            clampFloatingBallPosition(1200, 2100, 180, 1080, 1920),
        )
    }
}
