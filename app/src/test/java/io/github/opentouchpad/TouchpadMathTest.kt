package io.github.opentouchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadMathTest {
    @Test
    fun arrowCursorUsesTopLeftAsThePointerHotspot() {
        assertEquals(100, cursorViewOrigin(100f, 100f).x)
        assertEquals(100, cursorViewOrigin(100f, 100f).y)
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
    fun panelWidthCanShrinkToCompactAccessibilityLayout() {
        assertEquals(180, PANEL_MIN_WIDTH_DP)
        assertEquals(180, resizePanelWidth(startPx = 420, deltaX = -300, minPx = PANEL_MIN_WIDTH_DP, maxPx = 720))
        assertEquals(720, resizePanelWidth(startPx = 420, deltaX = 500, minPx = PANEL_MIN_WIDTH_DP, maxPx = 720))
    }

    @Test
    fun themeModeIdsRoundTripAndUnknownValuesFollowSystem() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromId("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromId("dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId("system"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId("removed"))
        assertFalse(ThemeMode.LIGHT.resolvesToDark(systemIsDark = true))
        assertTrue(ThemeMode.DARK.resolvesToDark(systemIsDark = false))
        assertTrue(ThemeMode.SYSTEM.resolvesToDark(systemIsDark = true))
        assertFalse(ThemeMode.SYSTEM.resolvesToDark(systemIsDark = false))
    }

    @Test
    fun floatingBallPositionIsKeptInsideScreen() {
        assertEquals(
            PanelPosition(900, 1740),
            clampFloatingBallPosition(1200, 2100, 180, 1080, 1920),
        )
    }
}
