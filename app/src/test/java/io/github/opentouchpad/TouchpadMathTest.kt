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
    fun customSwipeNeedsARealPath() {
        assertEquals(null, customSwipeFrom(10f, 20f, 15f, 23f, minDistancePx = 8f))
        assertEquals(
            CustomSwipe(10f, 20f, 40f, 60f),
            customSwipeFrom(10f, 20f, 40f, 60f, minDistancePx = 8f),
        )
    }

    @Test
    fun customSwipeUsesIndependentControlSizeBounds() {
        assertEquals(540..1080, controlWidthRange(screenWidth = 1080, density = 3f))
        assertEquals(540..1920, controlHeightRange(screenHeight = 1920, density = 3f))
        assertEquals(180..240, controlWidthRange(screenWidth = 240, density = 1f))
    }

    @Test
    fun padReleaseDistinguishesClickLongPressAndCustomSwipe() {
        assertEquals(PadTouchOutcome.CLICK, resolvePadTouchOutcome(false, false, false, 0f, 12f))
        assertEquals(PadTouchOutcome.MOVE_ONLY, resolvePadTouchOutcome(false, false, true, 80f, 12f))
        assertEquals(PadTouchOutcome.LONG_PRESS, resolvePadTouchOutcome(true, false, false, 0f, 12f))
        assertEquals(PadTouchOutcome.CUSTOM_SWIPE, resolvePadTouchOutcome(true, false, true, 80f, 12f))
        assertEquals(PadTouchOutcome.MOVE_ONLY, resolvePadTouchOutcome(true, false, true, 4f, 12f))
        assertEquals(PadTouchOutcome.DWELL_CLICK, resolvePadTouchOutcome(false, true, false, 0f, 12f))
    }

    @Test
    fun controlHeightResizeGrowsDownwardAndClamps() {
        assertEquals(620, resizeControlHeight(startPx = 420, deltaY = 200, minPx = 180, maxPx = 900))
        assertEquals(180, resizeControlHeight(startPx = 420, deltaY = -500, minPx = 180, maxPx = 900))
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
    fun longPressSchedulingIsDisabledOnlyDuringDragLock() {
        assertTrue(shouldScheduleLongPress(dwellMs = 800, dragging = false))
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

    @Test
    fun buttonSpacingDoesNotResizeTouchpadOrButtons() {
        val a = computeControlLayout(1000, 800, 1f, spacingPx = 0)
        val b = computeControlLayout(1000, 800, 1f, spacingPx = 60)
        assertEquals(a.pad, b.pad)
        assertEquals(a.button, b.button)
        assertEquals(a.moveGrip, b.moveGrip)
        assertEquals(a.resizeGrip, b.resizeGrip)
        assertTrue(b.slots[1].x - b.slots[0].x > a.slots[1].x - a.slots[0].x)
    }

    @Test
    fun gripsSitInCornersWithButtonSizeAndNoOverlap() {
        val l = computeControlLayout(900, 700, 1f, spacingPx = 200)
        assertEquals(l.button, l.moveGrip.w)
        assertEquals(l.button, l.resizeGrip.h)
        assertEquals(4, l.moveGrip.x)
        assertEquals(900 - 4 - l.button, l.resizeGrip.x)
        assertEquals(700 - 4 - l.button, l.resizeGrip.y)
        val all = l.slots + l.moveGrip + l.resizeGrip + l.pad
        for (i in all.indices) for (j in i + 1 until all.size) {
            val p = all[i]; val q = all[j]
            val overlap = p.x < q.x + q.w && q.x < p.x + p.w && p.y < q.y + q.h && q.y < p.y + p.h
            assertFalse("$p overlaps $q", overlap)
        }
    }
}
