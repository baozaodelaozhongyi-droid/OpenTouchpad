package io.github.opentouchpad

import kotlin.math.roundToInt
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
    fun dwellRearmsOnlyAfterCursorLeavesTheFiredSpot() {
        // 还没触发过：总是计时
        assertTrue(shouldRearmDwell(Float.NaN, Float.NaN, 100f, 100f, minDistancePx = 24f))
        // 触发后原地微抖：不重新计时，避免连点
        assertFalse(shouldRearmDwell(100f, 100f, 100f, 100f, minDistancePx = 24f))
        assertFalse(shouldRearmDwell(100f, 100f, 110f, 110f, minDistancePx = 24f))
        // 光标真正移开：重新计时
        assertTrue(shouldRearmDwell(100f, 100f, 124f, 100f, minDistancePx = 24f))
        assertTrue(shouldRearmDwell(100f, 100f, 80f, 130f, minDistancePx = 24f))
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
    fun ballDeformationCalculatesExpectedSquashStretchAndOrientation() {
        // 1. Zero displacement -> neutral
        val zero = computeBallDeformation(dx = 0f, dy = 0f, maxOffsetPx = 30f)
        assertEquals(1f, zero.stretch, 0.001f)
        assertEquals(1f, zero.squash, 0.001f)
        assertEquals(0f, zero.rotationDeg, 0.001f)
        assertEquals(0f, zero.iconTranslationX, 0.001f)
        assertEquals(0f, zero.iconTranslationY, 0.001f)

        // 2. Right drag (angle 0)
        val right = computeBallDeformation(dx = 30f, dy = 0f, maxOffsetPx = 30f)
        assertEquals(0f, right.rotationDeg, 0.01f)
        assertTrue(right.stretch > 1.1f)
        assertTrue(right.squash < 0.92f)
        assertTrue(right.iconTranslationX > 1f)
        assertEquals(0f, right.iconTranslationY, 0.01f)

        // 3. Down drag (angle 90)
        val down = computeBallDeformation(dx = 0f, dy = 30f, maxOffsetPx = 30f)
        assertEquals(90f, down.rotationDeg, 0.01f)
        assertTrue(down.stretch > 1.1f)
        assertTrue(down.squash < 0.92f)
        assertEquals(0f, down.iconTranslationX, 0.01f)
        assertTrue(down.iconTranslationY > 1f)

        // 4. Up-Left drag (angle -135 or 135 depending on coordinate)
        val upLeft = computeBallDeformation(dx = -30f, dy = -30f, maxOffsetPx = 30f)
        assertEquals(-135f, upLeft.rotationDeg, 0.01f)
        assertTrue(upLeft.stretch > 1.1f)
        assertTrue(upLeft.iconTranslationX < 0f)
        assertTrue(upLeft.iconTranslationY < 0f)

        // 5. Area conservation check: stretch * squash ~ 1.0
        val area = right.stretch * right.squash
        assertTrue("Area should be preserved near 1.0 (actual $area)", area in 0.95f..1.05f)

        // 6. Extreme drag saturation: never exceeds safe bounds
        val extreme = computeBallDeformation(dx = 500f, dy = 500f, maxOffsetPx = 30f)
        assertTrue(extreme.stretch <= 1.25f)
        assertTrue(extreme.squash >= 0.80f)
    }

    @Test
    fun floatingBallPositionIsKeptInsideScreen() {
        assertEquals(
            PanelPosition(900, 1740),
            clampFloatingBallPosition(1200, 2100, 180, 1080, 1920),
        )
    }

    private fun gridOf(width: Int, gap: Int, extra: Int = 0, density: Float = 1f, buttonCount: Int = 16): ControlLayout {
        val h = controlGridHeight(width, density, gap, buttonCount) + extra
        return computeControlLayout(width, h, density, gap, buttonCount)
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
    fun selectableButtonModesGenerateCorrectSlotCountsAndShapes() {
        for (count in listOf(4, 8, 12, 16)) {
            val l = gridOf(168, 3, buttonCount = count)
            assertEquals(count, l.slots.size)
            val edgeCount = count / 4
            val topSlots = l.slots.subList(0, edgeCount)
            val bottomSlots = l.slots.subList(edgeCount, 2 * edgeCount)
            val leftSlots = l.slots.subList(2 * edgeCount, 3 * edgeCount)
            val rightSlots = l.slots.subList(3 * edgeCount, 4 * edgeCount)

            for (slot in topSlots + bottomSlots) {
                assertEquals(l.button, slot.h)
                if (count == 16) {
                    assertEquals("Mode 16 buttons should be circular (square rect)", l.button, slot.w)
                } else {
                    assertTrue("Mode $count top/bottom buttons should be capsules (w > h)", slot.w > slot.h)
                }
            }

            for (slot in leftSlots + rightSlots) {
                assertEquals(l.button, slot.w)
                when (count) {
                    4 -> assertTrue("Mode 4 side button should be a vertical capsule (h > w)", slot.h > slot.w)
                    8, 12 -> assertEquals("Mode $count side buttons should be circular (h == w) at minimum height", l.button, slot.h)
                    16 -> assertTrue("Mode 16 side buttons should be compact valid buttons", slot.h > 0 && slot.h <= l.button)
                }
            }

            assertNoOverlap(l)
        }
    }

    @Test
    fun eighteenButtonModeGeneratesCircularSlotsAndMatchesGrid() {
        val l = gridOf(168, 3, buttonCount = 18)
        assertEquals(18, l.slots.size)
        for (slot in l.slots) {
            assertEquals("Mode 18 buttons should be circular (b x b)", l.button, slot.w)
            assertEquals("Mode 18 buttons should be circular (b x b)", l.button, slot.h)
        }
        assertNoOverlap(l)
    }

    @Test
    fun controlMinPadHeightIsCompactForLowerMinimumHeight() {
        val density = 3f
        val gap = 9
        val width = (168 * density).toInt()
        val b = controlButtonSize(width, density, gap)

        // 4 and 8 keys: minimum pad height is 2 buttons + 1 gap (~53 dp)
        assertEquals(2 * b + gap, controlMinPadHeight(b, gap, 4))
        assertEquals(2 * b + gap, controlMinPadHeight(b, gap, 8))

        // 12, 16 and 18 keys: minimum pad height is 3 buttons + 2 gap (~81 dp)
        assertEquals(3 * b + 2 * gap, controlMinPadHeight(b, gap, 12))
        assertEquals(3 * b + 2 * gap, controlMinPadHeight(b, gap, 16))
        assertEquals(3 * b + 2 * gap, controlMinPadHeight(b, gap, 18))

        // Grid height is 2 * edge + 2 * b + 2 * gap + minPadH
        for (count in listOf(4, 8, 12, 16, 18)) {
            val minPadH = controlMinPadHeight(b, gap, count)
            val expectedGridH = 2 * (CONTROL_EDGE_DP * density).roundToInt() + 2 * b + 2 * gap + minPadH
            assertEquals(expectedGridH, controlGridHeight(width, density, gap, count))
        }
    }

    @Test
    fun everyGapIsTheSameAroundTouchpadAndAdjacentButtons() {
        for (count in listOf(4, 8, 12, 16)) {
            val edgeCount = count / 4
            for (gap in listOf(0, 3, 8)) {
                val l = gridOf(168, gap, buttonCount = count)
                val topSlots = l.slots.subList(0, edgeCount)
                val bottomSlots = l.slots.subList(edgeCount, 2 * edgeCount)
                val leftSlots = l.slots.subList(2 * edgeCount, 3 * edgeCount)
                val rightSlots = l.slots.subList(3 * edgeCount, 4 * edgeCount)

                // 检查触控板与四周按键的间距
                assertEquals("gap to top slots", gap, l.pad.y - (topSlots[0].y + topSlots[0].h))
                assertEquals("gap to bottom slots", gap, bottomSlots[0].y - (l.pad.y + l.pad.h))
                assertEquals("gap to left slots", gap, l.pad.x - (leftSlots[0].x + leftSlots[0].w))
                assertEquals("gap to right slots", gap, rightSlots[0].x - (l.pad.x + l.pad.w))

                // 检查同一边相邻按钮之间的间距
                for (row in listOf(topSlots, bottomSlots)) {
                    for (i in 1 until row.size) {
                        assertEquals("horizontal adjacent button gap", gap, row[i].x - (row[i - 1].x + row[i - 1].w))
                        assertEquals(row[0].y, row[i].y)
                    }
                }
                for (col in listOf(leftSlots, rightSlots)) {
                    for (i in 1 until col.size) {
                        assertEquals("vertical adjacent button gap", gap, col[i].y - (col[i - 1].y + col[i - 1].h))
                        assertEquals(col[0].x, col[i].x)
                    }
                }

                assertNoOverlap(l)
            }
        }
    }

    @Test
    fun extraHeightStretchesSideButtonsWithTouchpad() {
        for (count in listOf(4, 8, 12, 16)) {
            val a = gridOf(168, 3, buttonCount = count)
            val b = gridOf(168, 3, extra = 60, buttonCount = count)
            assertEquals(a.button, b.button)
            assertEquals(a.pad.w, b.pad.w)
            assertEquals(a.pad.h + 60, b.pad.h)

            val edgeCount = count / 4
            val leftIndex = 2 * edgeCount
            // 侧边按键顶部与触控板对齐
            assertEquals(a.pad.y, a.slots[leftIndex].y)
            assertEquals(b.pad.y, b.slots[leftIndex].y)

            // 侧边按键高度跟着触控板高度拉伸
            val totalSideHA = (0 until edgeCount).sumOf { a.slots[leftIndex + it].h } + (edgeCount - 1) * 3
            val totalSideHB = (0 until edgeCount).sumOf { b.slots[leftIndex + it].h } + (edgeCount - 1) * 3
            assertEquals(a.pad.h, totalSideHA)
            assertEquals(b.pad.h, totalSideHB)
            assertEquals(totalSideHA + 60, totalSideHB)

            assertNoOverlap(b)
        }
    }

    @Test
    fun extraHeightStretchesSideButtonsInEighteenMode() {
        val a = gridOf(168, 3, buttonCount = 18)
        val b = gridOf(168, 3, extra = 60, buttonCount = 18)
        assertEquals(a.button, b.button)
        assertEquals(a.pad.w, b.pad.w)
        assertEquals(a.pad.h + 60, b.pad.h)

        // 侧边 3 个按钮高度总和严格填满触控板高度，并跟着拉伸
        val left18A = listOf(a.slots[16], a.slots[8], a.slots[9])
        val left18B = listOf(b.slots[16], b.slots[8], b.slots[9])
        assertEquals(a.pad.y, left18A[0].y)
        assertEquals(b.pad.y, left18B[0].y)
        val totalSideA = left18A.sumOf { it.h } + 2 * 3
        val totalSideB = left18B.sumOf { it.h } + 2 * 3
        assertEquals(a.pad.h, totalSideA)
        assertEquals(b.pad.h, totalSideB)
        assertEquals(totalSideA + 60, totalSideB)
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
        val edgeCount = 16 / 4
        assertEquals(9, hi.slots[1].x - (hi.slots[0].x + hi.slots[0].w))
        assertEquals(9, hi.slots[2 * edgeCount + 1].y - (hi.slots[2 * edgeCount].y + hi.slots[2 * edgeCount].h))
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

    @Test
    fun dragMotionStateAtRestReturnsBaseScaleAndNoParallax() {
        val state = computeDragMotionState(speedPxPerSec = 0f, dirX = 1f, dirY = 0f, baseScale = 1.16f)
        assertEquals(1.16f, state.stretch, 0.001f)
        assertEquals(1.16f, state.squash, 0.001f)
        assertEquals(0f, state.rotationDeg, 0.001f)
        assertEquals(0f, state.iconTranslationX, 0.001f)
        assertEquals(0f, state.iconTranslationY, 0.001f)
    }

    @Test
    fun dragMotionStateAtSpeedElongatesAndLagsIcon() {
        val stateRight = computeDragMotionState(
            speedPxPerSec = 1200f,
            dirX = 1f,
            dirY = 0f,
            baseScale = 1.16f,
            refSpeedPxPerSec = 1200f,
            maxExtraStretch = 0.22f,
            maxSquash = 0.18f,
            maxParallaxPx = 10f,
        )
        assertTrue(stateRight.stretch > 1.16f)
        assertTrue(stateRight.squash < 1.16f)
        assertEquals(0f, stateRight.rotationDeg, 0.01f)
        assertTrue(stateRight.iconTranslationX < 0f) // 往右移动时图标向左滞后
        assertEquals(0f, stateRight.iconTranslationY, 0.001f)

        val stateDown = computeDragMotionState(
            speedPxPerSec = 1200f,
            dirX = 0f,
            dirY = 1f,
            baseScale = 1.16f,
            refSpeedPxPerSec = 1200f,
            maxExtraStretch = 0.22f,
            maxSquash = 0.18f,
            maxParallaxPx = 10f,
        )
        assertEquals(90f, stateDown.rotationDeg, 0.01f)
        assertTrue(stateDown.iconTranslationY < 0f) // 往下移动时图标向上滞后
        assertEquals(0f, stateDown.iconTranslationX, 0.001f)
    }

    @Test
    fun lerpAngleDegHandlesBoundarySmoothly() {
        assertEquals(45f, lerpAngleDeg(0f, 90f, 0.5f), 0.01f)
        // 350° 与 10° 之间经过 0°
        assertEquals(0f, lerpAngleDeg(350f, 10f, 0.5f), 0.01f)
        assertEquals(0f, lerpAngleDeg(10f, 350f, 0.5f), 0.01f)
    }

    @Test
    fun sliderControllerClampsValuesProperly() {
        var recordedValue = -1
        val controller = MainActivity.SliderController { newValue ->
            recordedValue = newValue.coerceIn(10, 100)
        }
        controller.setValue(5)
        assertEquals(10, recordedValue)
        controller.setValue(50)
        assertEquals(50, recordedValue)
        controller.setValue(150)
        assertEquals(100, recordedValue)
    }

    @Test
    fun onPanelResizedCallbackDispatchesDimensions() {
        var receivedW = 0
        var receivedH = 0
        TouchpadService.onPanelResized = { w, h ->
            receivedW = w
            receivedH = h
        }
        TouchpadService.onPanelResized?.invoke(320, 45)
        assertEquals(320, receivedW)
        assertEquals(45, receivedH)
        TouchpadService.onPanelResized = null
    }
}
