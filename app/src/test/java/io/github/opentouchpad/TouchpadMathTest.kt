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
        assertEquals(330..1080, controlWidthRange(screenWidth = 1080, density = 3f))
        assertEquals(330..1920, controlHeightRange(screenHeight = 1920, density = 3f))
        assertEquals(110..240, controlWidthRange(screenWidth = 240, density = 1f))
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
        assertEquals(110, PANEL_MIN_WIDTH_DP)
        assertEquals(120, resizePanelWidth(startPx = 420, deltaX = -300, minPx = PANEL_MIN_WIDTH_DP, maxPx = 720))
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

    private fun gridOf(width: Int, gap: Int, extra: Int = 0, density: Float = 1f): ControlLayout {
        val h = controlGridHeight(width, density, gap) + extra
        return computeControlLayout(width, h, density, gap)
    }

    private fun assertNoOverlap(l: ControlLayout) {
        val all = l.slots + l.pad
        for (i in all.indices) for (j in i + 1 until all.size) {
            val p = all[i]; val q = all[j]
            val overlap = p.x < q.x + q.w && q.x < p.x + p.w && p.y < q.y + q.h && q.y < p.y + p.h
            assertFalse("$p overlaps $q", overlap)
        }
    }

    @Test
    fun everyGapIsTheSameInRowsColumnsCornersAndAroundTouchpad() {
        for (gap in listOf(0, 3, 8)) {
            val l = gridOf(168, gap)
            val b = l.button
            val (tl, tr, bl, br) = l.slots.subList(12, 16)
            val top = listOf(tl) + l.slots.subList(0, 4) + tr
            val bottom = listOf(bl) + l.slots.subList(4, 8) + br
            for (row in listOf(top, bottom)) for (i in 1 until row.size) {
                assertEquals("row gap", gap, row[i].x - (row[i - 1].x + b))
                assertEquals(row[0].y, row[i].y)
            }
            val left = listOf(tl, l.slots[16], l.slots[8], l.slots[9], bl)
            val right = listOf(tr, l.slots[10], l.slots[11], l.slots[17], br)
            for (col in listOf(left, right)) for (i in 1 until col.size) {
                assertEquals("column gap", gap, col[i].y - (col[i - 1].y + b))
                assertEquals(col[0].x, col[i].x)
            }
            // touchpad keeps the same gap to every neighbour
            assertEquals(gap, l.pad.y - (tl.y + b))
            assertEquals(gap, bl.y - (l.pad.y + l.pad.h))
            assertEquals(gap, l.pad.x - (tl.x + b))
            assertEquals(gap, tr.x - (l.pad.x + l.pad.w))
            assertNoOverlap(l)
        }
    }

    @Test
    fun extraHeightOnlyMakesTouchpadTaller() {
        val a = gridOf(168, 3)
        val b = gridOf(168, 3, extra = 60)
        assertEquals(a.button, b.button)
        assertEquals(a.pad.w, b.pad.w)
        assertEquals(a.pad.h + 60, b.pad.h)
        // side buttons stay packed with the same gap
        assertEquals(3, b.slots[8].y - (b.slots[16].y + b.button))
        assertEquals(3, b.slots[17].y - (b.slots[11].y + b.button))
        assertNoOverlap(b)
    }

    @Test
    fun widthControlsButtonSizeAndSmallPanelsStayValid() {
        assertTrue(gridOf(240, 3).button > gridOf(168, 3).button)
        val tiny = gridOf(PANEL_MIN_WIDTH_DP, 3)
        assertTrue(tiny.button >= CONTROL_BUTTON_MIN_DP)
        assertNoOverlap(tiny)
        // densities other than 1 also keep equal gaps
        val hi = gridOf(168 * 3, 9, density = 3f)
        assertEquals(9, hi.slots[1].x - (hi.slots[0].x + hi.button))
        assertEquals(9, hi.slots[8].y - (hi.slots[16].y + hi.button))
    }

    @Test
    fun dragTrailSkipsTinyStepsAndStaysBounded() {
        val trail = mutableListOf<TrailPoint>()
        appendTrailPoint(trail, 0f, 0f, 4f)
        appendTrailPoint(trail, 1f, 1f, 4f)
        assertEquals(1, trail.size)
        for (i in 1..1000) appendTrailPoint(trail, i * 5f, 0f, 4f)
        assertTrue(trail.size <= DRAG_TRAIL_MAX_POINTS)
        assertEquals(TrailPoint(0f, 0f), trail.first())
        assertEquals(TrailPoint(5000f, 0f), trail.last())
    }

    @Test
    fun dragMoveDurationIsClamped() {
        assertEquals(250L, dragMoveDurationMs(10f))
        assertEquals(500L, dragMoveDurationMs(600f))
        assertEquals(1500L, dragMoveDurationMs(100000f))
        assertEquals(10f, trailLength(listOf(TrailPoint(0f, 0f), TrailPoint(6f, 8f))), 0.001f)
    }

    @Test
    fun touchAreaIgnoresEdgeBandAndRoundedCorners() {
        // 100x60 pad, corner radius 20, edge 8 → valid area (8,8)-(92,52), inner radius 12
        assertTrue(insideTouchArea(50f, 30f, 100f, 60f, 20f, 8f))   // centre
        assertFalse(insideTouchArea(4f, 30f, 100f, 60f, 20f, 8f))   // left band
        assertFalse(insideTouchArea(96f, 30f, 100f, 60f, 20f, 8f))  // right band
        assertFalse(insideTouchArea(50f, 3f, 100f, 60f, 20f, 8f))   // top band
        assertFalse(insideTouchArea(50f, 56f, 100f, 60f, 20f, 8f))  // bottom band
        assertTrue(insideTouchArea(8f, 30f, 100f, 60f, 20f, 8f))    // exactly on the inner edge counts
        assertFalse(insideTouchArea(10f, 10f, 100f, 60f, 20f, 8f))  // past the edge band but outside the rounded corner
        assertFalse(insideTouchArea(90f, 50f, 100f, 60f, 20f, 8f))  // same, bottom-right corner
        assertTrue(insideTouchArea(20f, 20f, 100f, 60f, 20f, 8f))   // inside the inner rounded corner
        // outside the outer rounded corner (the old tappable square corner) is ignored too
        assertFalse(insideTouchArea(1f, 1f, 100f, 60f, 20f, 8f))
    }

    @Test
    fun touchAreaEdgeZeroMeansOff() {
        assertTrue(insideTouchArea(0f, 0f, 100f, 60f, 20f, 0f))
        assertTrue(insideTouchArea(100f, 60f, 100f, 60f, 20f, 0f))
        assertTrue(insideTouchArea(1f, 30f, 100f, 60f, 0f, 0f))
    }

    @Test
    fun touchAreaEdgeBandIsCappedOnSmallPads() {
        // 32dp band on a 60px-tall pad would swallow it; the band is capped at a third of the short side
        assertTrue(insideTouchArea(50f, 30f, 100f, 60f, 20f, 32f))
        assertFalse(insideTouchArea(50f, 15f, 100f, 60f, 20f, 32f))
        // square-corner pad (radius smaller than the band): corners are plain rectangles
        assertTrue(insideTouchArea(10f, 10f, 100f, 60f, 4f, 10f))
        assertFalse(insideTouchArea(9f, 30f, 100f, 60f, 4f, 10f))
    }

    @Test
    fun edgeWidthSettingRange() {
        assertEquals(0 to 32, 0 to PAD_EDGE_DEAD_ZONE_MAX_DP)
        assertEquals(10, PAD_EDGE_DEAD_ZONE_DEFAULT_DP)
    }
}
