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
    fun themeTransitionResolvesReversibleActionsCorrectly() {
        // 初始暗色，无进行中的扩散波
        assertEquals(ThemeTransitionAction.NO_OP, resolveThemeTransitionAction(baseDark = true, currentRevealDark = null, targetDark = true))
        assertEquals(ThemeTransitionAction.START_REVEAL, resolveThemeTransitionAction(baseDark = true, currentRevealDark = null, targetDark = false))

        // 初始暗色，亮色波正在扩散中
        // 用户再次点击亮色：继续扩散
        assertEquals(ThemeTransitionAction.EXPAND_REVEAL, resolveThemeTransitionAction(baseDark = true, currentRevealDark = false, targetDark = false))
        // 用户点击暗色（系统或深色）：反转收缩回底层
        assertEquals(ThemeTransitionAction.REVERSE_TO_BASE, resolveThemeTransitionAction(baseDark = true, currentRevealDark = false, targetDark = true))

        // 初始亮色，暗色波正在扩散中
        assertEquals(ThemeTransitionAction.EXPAND_REVEAL, resolveThemeTransitionAction(baseDark = false, currentRevealDark = true, targetDark = true))
        assertEquals(ThemeTransitionAction.REVERSE_TO_BASE, resolveThemeTransitionAction(baseDark = false, currentRevealDark = true, targetDark = false))
    }

    @Test
    fun revealDurationScalesWithDistanceAndClamps() {
        assertEquals(380L, computeRevealDuration(fromRadius = 0f, toRadius = 1000f, maxRadius = 1000f))
        assertEquals(114L, computeRevealDuration(fromRadius = 300f, toRadius = 0f, maxRadius = 1000f))
        assertEquals(100L, computeRevealDuration(fromRadius = 50f, toRadius = 0f, maxRadius = 1000f))
        assertEquals(380L, computeRevealDuration(fromRadius = 0f, toRadius = 2000f, maxRadius = 1000f))
    }

    @Test
    fun swipeDirectionResolvesCorrectly() {
        assertEquals(SwipeDirection.LEFT, resolveSwipeDirection(dx = -50f, dy = 10f))
        assertEquals(SwipeDirection.RIGHT, resolveSwipeDirection(dx = 50f, dy = -10f))
        assertEquals(SwipeDirection.UP, resolveSwipeDirection(dx = 10f, dy = -60f))
        assertEquals(SwipeDirection.DOWN, resolveSwipeDirection(dx = -10f, dy = 60f))
        // 对角线等值偏向水平
        assertEquals(SwipeDirection.LEFT, resolveSwipeDirection(dx = -40f, dy = 40f))
    }

    @Test
    fun swipeGestureThresholdDetection() {
        assertTrue(isSwipeGesture(dx = 15f, dy = 20f, thresholdPx = 20f))
        assertFalse(isSwipeGesture(dx = 5f, dy = 5f, thresholdPx = 20f))
    }

    @Test
    fun ballSwipeActionResolvesConfiguredAction() {
        assertEquals(PadAction.HOME, resolveBallSwipeAction(SwipeDirection.UP, PadAction.HOME, PadAction.NOTIFICATIONS, PadAction.RECENTS, PadAction.RECENTS))
        assertEquals(PadAction.NOTIFICATIONS, resolveBallSwipeAction(SwipeDirection.DOWN, PadAction.HOME, PadAction.NOTIFICATIONS, PadAction.RECENTS, PadAction.RECENTS))
        assertEquals(PadAction.RECENTS, resolveBallSwipeAction(SwipeDirection.LEFT, PadAction.HOME, PadAction.NOTIFICATIONS, PadAction.RECENTS, PadAction.RECENTS))
        assertEquals(PadAction.RECENTS, resolveBallSwipeAction(SwipeDirection.RIGHT, PadAction.HOME, PadAction.NOTIFICATIONS, PadAction.RECENTS, PadAction.RECENTS))
    }

    @Test
    fun ballLongPressHoldDurationHasSafeMinimum() {
        assertEquals(450L, resolveBallLongPressHoldMs(configuredLongPressMs = 200))
        assertEquals(450L, resolveBallLongPressHoldMs(configuredLongPressMs = 300))
        assertEquals(600L, resolveBallLongPressHoldMs(configuredLongPressMs = 600))
        assertEquals(1000L, resolveBallLongPressHoldMs(configuredLongPressMs = 1000))
    }

    @Test
    fun ballLongPressCancelsOnMovementBeyondSlop() {
        assertFalse(shouldCancelBallLongPress(distPx = 4f, slopPx = 6f))
        assertFalse(shouldCancelBallLongPress(distPx = 6f, slopPx = 6f))
        assertTrue(shouldCancelBallLongPress(distPx = 6.1f, slopPx = 6f))
        assertTrue(shouldCancelBallLongPress(distPx = 15f, slopPx = 6f))
    }

    @Test
    fun dampedSwipeOffsetTracksDirectionAndStaysWithinMax() {
        // Zero movement
        val zero = computeDampedSwipeOffset(0f, 0f, maxOffsetPx = 20f)
        assertEquals(0f, zero.x, 0.001f)
        assertEquals(0f, zero.y, 0.001f)

        // Direction preservation (right)
        val right = computeDampedSwipeOffset(50f, 0f, maxOffsetPx = 20f)
        assertTrue(right.x > 0f)
        assertEquals(0f, right.y, 0.001f)
        assertTrue(right.x < 20f)

        // Direction preservation (up)
        val up = computeDampedSwipeOffset(0f, -60f, maxOffsetPx = 20f)
        assertEquals(0f, up.x, 0.001f)
        assertTrue(up.y < 0f)
        assertTrue(kotlin.math.abs(up.y) < 20f)

        // Large swipe asymptotes smoothly towards max offset without exceeding it
        val far = computeDampedSwipeOffset(500f, 0f, maxOffsetPx = 20f)
        assertTrue(far.x in 19.9f..20f)
        assertTrue(far.x <= 20f)

        // Proportionality on diagonals
        val diag = computeDampedSwipeOffset(-40f, 40f, maxOffsetPx = 20f)
        assertEquals(kotlin.math.abs(diag.x), diag.y, 0.001f)
        assertTrue(diag.x < 0f)
        assertTrue(diag.y > 0f)
    }

    @Test
    fun ballDragTracksLinearlyFromOriginWithoutCompounding() {
        val startX = 200
        val startY = 300
        val steps = listOf(
            2f to 5f,
            4f to 10f,
            6f to 15f,
            20f to 50f,
        )
        for ((dx, dy) in steps) {
            val targetX = startX + dx.toInt()
            val targetY = startY + dy.toInt()
            assertEquals(startX + dx.toInt(), targetX)
            assertEquals(startY + dy.toInt(), targetY)
        }
    }

    @Test
    fun ballSwipeTrackerCancelsWhenDraggingBackToCenterWithHysteresis() {
        val tracker = BallSwipeTracker(swipeThresholdPx = 16f, cancelThresholdPx = 8f)

        // 1. Initial touch: not armed, not cancelled
        assertFalse(tracker.hasArmedSwipe)
        assertFalse(tracker.isCancelledByReturn)

        // 2. Small move below threshold: does not arm, does not trigger cancel
        assertFalse(tracker.onMove(distPx = 10f))
        assertFalse(tracker.hasArmedSwipe)
        assertFalse(tracker.isCancelledByReturn)

        // 3. Move beyond swipe threshold: arms gesture
        assertFalse(tracker.onMove(distPx = 20f))
        assertTrue(tracker.hasArmedSwipe)
        assertFalse(tracker.isCancelledByReturn)

        // 4. Move partially back (in hysteresis band 8..16): stays armed
        assertFalse(tracker.onMove(distPx = 12f))
        assertTrue(tracker.hasArmedSwipe)
        assertFalse(tracker.isCancelledByReturn)

        // 5. Crosses into center cancel zone (<= 8): triggers cancellation vibration!
        assertTrue(tracker.onMove(distPx = 7f))
        assertFalse(tracker.hasArmedSwipe)
        assertTrue(tracker.isCancelledByReturn)

        // 6. Continues moving deeper into center: stays cancelled, does not re-vibrate
        assertFalse(tracker.onMove(distPx = 2f))
        assertFalse(tracker.hasArmedSwipe)
        assertTrue(tracker.isCancelledByReturn)

        // 7. Pushes out again beyond threshold: re-arms!
        assertFalse(tracker.onMove(distPx = 22f))
        assertTrue(tracker.hasArmedSwipe)
        assertFalse(tracker.isCancelledByReturn)

        // 8. Pulls back into center zone again: triggers cancellation vibration again!
        assertTrue(tracker.onMove(distPx = 5f))
        assertFalse(tracker.hasArmedSwipe)
        assertTrue(tracker.isCancelledByReturn)

        // 9. Reset clears all state
        tracker.reset()
        assertFalse(tracker.hasArmedSwipe)
        assertFalse(tracker.isCancelledByReturn)
    }

    @Test
    fun cancelThresholdUsesReasonableProportion() {
        assertEquals(10f, resolveBallCancelThreshold(swipeThresholdPx = 20f, minCancelPx = 8f))
        assertEquals(8f, resolveBallCancelThreshold(swipeThresholdPx = 12f, minCancelPx = 8f))
        assertEquals(6f, resolveBallCancelThreshold(swipeThresholdPx = 8f, minCancelPx = 8f))
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

    @Test
    fun isPointInsideRectMatchesBounds() {
        assertTrue(isPointInsideRect(100f, 200f, 50, 100, 200, 300))
        assertTrue(isPointInsideRect(50f, 100f, 50, 100, 200, 300))
        assertTrue(isPointInsideRect(250f, 400f, 50, 100, 200, 300))
        assertFalse(isPointInsideRect(49f, 200f, 50, 100, 200, 300))
        assertFalse(isPointInsideRect(251f, 200f, 50, 100, 200, 300))
        assertFalse(isPointInsideRect(100f, 99f, 50, 100, 200, 300))
        assertFalse(isPointInsideRect(100f, 401f, 50, 100, 200, 300))
        assertFalse(isPointInsideRect(100f, 200f, 50, 100, 0, 300))
        assertFalse(isPointInsideRect(100f, 200f, 50, 100, 200, -10))
    }
}
