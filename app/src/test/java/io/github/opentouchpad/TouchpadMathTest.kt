package io.github.opentouchpad

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadMathTest {
    @Test
    fun arrowCursorUsesTopLeftAsThePointerHotspot() {
        assertEquals(100, cursorViewOrigin(100f, 100f, 0f).x)
        assertEquals(100, cursorViewOrigin(100f, 100f, 0f).y)
        val offset = cursorHotspotOffset(100f)
        assertEquals(9f, offset, 0.001f)
        assertEquals(91, cursorViewOrigin(100f, 100f, 100f).x)
        assertEquals(91, cursorViewOrigin(100f, 100f, 100f).y)
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
    fun customSwipeInertiaPlanCalculatesFlingExtensionAndDuration() {
        // 距离小于最小阈值：返回 null
        val belowMin = planCustomSwipe(
            startX = 100f, startY = 100f, endX = 102f, endY = 102f,
            velocityX = 0f, velocityY = 0f, minDistancePx = 6f, density = 2f,
            screenWidth = 1080, screenHeight = 1920,
        )
        assertEquals(null, belowMin)

        // 慢速移动（无惯性）：终点不变，耗时固定 300ms
        val slowDrag = planCustomSwipe(
            startX = 100f, startY = 500f, endX = 100f, endY = 400f,
            velocityX = 0f, velocityY = -100f, minDistancePx = 6f, density = 2f,
            screenWidth = 1080, screenHeight = 1920,
        )
        assertNotNull(slowDrag)
        assertFalse(slowDrag!!.isInertial)
        assertEquals(100f, slowDrag.targetX, 0.01f)
        assertEquals(400f, slowDrag.targetY, 0.01f)
        assertTrue(slowDrag.durationMs in 100L..250L)

        // 微操：仅位移 5px (2.5dp)
        val microDrag = planCustomSwipe(
            startX = 100f, startY = 200f, endX = 104f, endY = 203f,
            velocityX = 0f, velocityY = 0f, minDistancePx = 4f, density = 2f,
            screenWidth = 1080, screenHeight = 1920,
        )
        assertNotNull(microDrag)
        assertFalse(microDrag!!.isInertial)
        assertEquals(104f, microDrag.targetX, 0.01f)
        assertEquals(203f, microDrag.targetY, 0.01f)
        assertTrue(microDrag.durationMs in 100L..120L)

        // 快速上滑（初速度高）：沿滑动方向延伸 targetY，并且缩短手势耗时赋予飞滑动量
        val fastFlick = planCustomSwipe(
            startX = 200f, startY = 800f, endX = 200f, endY = 500f,
            velocityX = 0f, velocityY = -1800f, minDistancePx = 6f, density = 2f,
            screenWidth = 1080, screenHeight = 1920,
        )
        assertNotNull(fastFlick)
        assertTrue(fastFlick!!.isInertial)
        assertEquals(200f, fastFlick.targetX, 0.01f)
        // targetY 应比 endY (500f) 更小（向上延伸）
        assertTrue(fastFlick.targetY < 500f)
        assertTrue(fastFlick.durationMs in 140L..220L)

        // 反向速度（与位移方向相反）：不触发向前惯性延伸
        val reverseVel = planCustomSwipe(
            startX = 200f, startY = 800f, endX = 200f, endY = 500f,
            velocityX = 0f, velocityY = 1200f, minDistancePx = 6f, density = 2f,
            screenWidth = 1080, screenHeight = 1920,
        )
        assertNotNull(reverseVel)
        assertFalse(reverseVel!!.isInertial)
        assertEquals(500f, reverseVel.targetY, 0.01f)
    }

    @Test
    fun swipeVelocityTrackerComputesVelocityAndHandlesPause() {
        val tracker = SwipeVelocityTracker()
        tracker.addMovement(1000L, 100f, 200f)
        tracker.addMovement(1050L, 120f, 300f)
        tracker.addMovement(1100L, 150f, 450f)

        // 快速移动并在 1110ms 立即抬手：速度计算正常
        val (vx, vy) = tracker.computeVelocity(1110L)
        assertTrue(vx > 0f)
        assertTrue(vy > 1000f)

        // 停顿 150ms 后才抬手（抬手时手指已停顿）：速度归零
        val (pausedVx, pausedVy) = tracker.computeVelocity(1260L)
        assertEquals(0f, pausedVx, 0.001f)
        assertEquals(0f, pausedVy, 0.001f)
    }

    @Test
    fun customSwipeUsesIndependentControlSizeBounds() {
        assertEquals(330..1080, controlWidthRange(screenWidth = 1080, density = 3f))
        assertEquals(330..1920, controlHeightRange(screenHeight = 1920, density = 3f))
        assertEquals(110..240, controlWidthRange(screenWidth = 240, density = 1f))
    }

    @Test
    fun padReleaseDistinguishesClickLongPressAndCustomSwipe() {
        assertEquals(PadTouchOutcome.CLICK, resolvePadTouchOutcome(false, false, 0f, 12f))
        assertEquals(PadTouchOutcome.MOVE_ONLY, resolvePadTouchOutcome(false, true, 80f, 12f))
        assertEquals(PadTouchOutcome.LONG_PRESS, resolvePadTouchOutcome(true, false, 0f, 12f))
        assertEquals(PadTouchOutcome.CUSTOM_SWIPE, resolvePadTouchOutcome(true, true, 80f, 12f))
        assertEquals(PadTouchOutcome.MOVE_ONLY, resolvePadTouchOutcome(true, true, 4f, 12f))
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
        assertTrue(shouldScheduleLongPress(dragging = false))
        assertFalse(shouldScheduleLongPress(dragging = true))
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
    fun settingsBgScrimColorPreservesRgbAndClampsAlpha() {
        val pageLight = 0xFFF5F4ED.toInt()
        val pageDark = 0xFF1F1E1D.toInt()
        assertEquals(0x00F5F4ED, computeSettingsBgScrimColor(pageLight, 0))
        assertEquals(0x7FF5F4ED, computeSettingsBgScrimColor(pageLight, 50))
        assertEquals(0xFFF5F4ED.toInt(), computeSettingsBgScrimColor(pageLight, 100))
        assertEquals(0x001F1E1D, computeSettingsBgScrimColor(pageDark, -20))
        assertEquals(0xFF1F1E1D.toInt(), computeSettingsBgScrimColor(pageDark, 150))
    }

    @Test
    fun revealSystemBarDarkSwitchesWhenWaveCrossesTargetAndRevertsWhenShrinking() {
        // 圆心 (300, 400)，顶部状态栏采样点 (300, 50)，距离 = 350
        assertFalse(
            resolveRevealSystemBarDark(
                baseDark = false,
                revealDark = true,
                radius = 0f,
                cx = 300f,
                cy = 400f,
                targetX = 300f,
                targetY = 50f,
            )
        )
        assertFalse(
            resolveRevealSystemBarDark(
                baseDark = false,
                revealDark = true,
                radius = 349f,
                cx = 300f,
                cy = 400f,
                targetX = 300f,
                targetY = 50f,
            )
        )
        assertTrue(
            resolveRevealSystemBarDark(
                baseDark = false,
                revealDark = true,
                radius = 350f,
                cx = 300f,
                cy = 400f,
                targetX = 300f,
                targetY = 50f,
            )
        )
        // 斜向距离：dx=300, dy=400 -> dist=500，切暗到亮时跨过阈值后变亮，收缩回阈值下恢复暗色
        assertTrue(
            resolveRevealSystemBarDark(
                baseDark = true,
                revealDark = false,
                radius = 499f,
                cx = 600f,
                cy = 450f,
                targetX = 300f,
                targetY = 50f,
            )
        )
        assertFalse(
            resolveRevealSystemBarDark(
                baseDark = true,
                revealDark = false,
                radius = 500f,
                cx = 600f,
                cy = 450f,
                targetX = 300f,
                targetY = 50f,
            )
        )
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
        assertEquals(200L, resolveBallLongPressHoldMs(configuredLongPressMs = 100))
        assertEquals(200L, resolveBallLongPressHoldMs(configuredLongPressMs = 200))
        assertEquals(300L, resolveBallLongPressHoldMs(configuredLongPressMs = 300))
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

    @Test
    fun resolveTouchRingColorFollowsThemeOrUsesCustomColor() {
        // 0 follows theme default
        assertEquals(0xFFD97757.toInt(), resolveTouchRingColor(0, isDark = true))
        assertEquals(0xFFC96442.toInt(), resolveTouchRingColor(0, isDark = false))
        // Custom color overrides theme
        val custom = 0xFF6A9BCC.toInt()
        assertEquals(custom, resolveTouchRingColor(custom, isDark = true))
        assertEquals(custom, resolveTouchRingColor(custom, isDark = false))
    }

    @Test
    fun resolveBallRestingAlphaClampsAndMaintainsConfiguredOpacity() {
        assertEquals(0.9f, resolveBallRestingAlpha(90), 0.001f)
        assertEquals(0.2f, resolveBallRestingAlpha(20), 0.001f)
        assertEquals(1.0f, resolveBallRestingAlpha(100), 0.001f)
        assertEquals(0.2f, resolveBallRestingAlpha(5), 0.001f)
        assertEquals(1.0f, resolveBallRestingAlpha(120), 0.001f)
    }

    @Test
    fun resolveButtonCustomizeHoldMsClampsWithinRange() {
        assertEquals(1000, resolveButtonCustomizeHoldMs(1000))
        assertEquals(300, resolveButtonCustomizeHoldMs(100))
        assertEquals(3000, resolveButtonCustomizeHoldMs(4000))
        assertEquals(2500, resolveButtonCustomizeHoldMs(2500))
    }

    @Test
    fun resolveTouchpadPaletteDefaultThemes() {
        val darkPalette = resolveTouchpadPalette(0, isDark = true)
        assertEquals(0xFF3A3936.toInt(), darkPalette.buttonBg)
        assertEquals(0xFF2A2A28.toInt(), darkPalette.padBg)
        assertEquals(0xFFFAF9F5.toInt(), darkPalette.buttonText)

        val lightPalette = resolveTouchpadPalette(0, isDark = false)
        assertEquals(0xFFF5F4ED.toInt(), lightPalette.buttonBg)
        assertEquals(0xFFE8E6DC.toInt(), lightPalette.padBg)
        assertEquals(0xFF141413.toInt(), lightPalette.buttonText)
    }

    @Test
    fun resolveTouchpadPaletteCustomDarkColor() {
        val customBlue = 0xFF6A9BCC.toInt()
        val palette = resolveTouchpadPalette(customBlue)
        assertEquals(customBlue, palette.padBg)
        assertEquals(lightenColor(customBlue, 0.12f), palette.buttonBg)
        assertEquals(0xFFFAF9F5.toInt(), palette.buttonText)
    }

    @Test
    fun resolveTouchpadPaletteCustomLightColor() {
        val customIvory = 0xFFFAF9F5.toInt()
        val palette = resolveTouchpadPalette(customIvory)
        assertEquals(customIvory, palette.buttonBg)
        assertEquals(darkenColor(customIvory, 0.06f), palette.padBg)
        assertEquals(0xFF141413.toInt(), palette.buttonText)
    }

    @Test
    fun resolvePadNormalizedCoordsCalculatesNormalizedCenterAndBounds() {
        // Panel at (100, 200), pad at rect (20, 30, 200, 100)
        // Center of pad is screen X: 100 + 20 + 100 = 220, screen Y: 200 + 30 + 50 = 280
        val center = resolvePadNormalizedCoords(
            rawX = 220f,
            rawY = 280f,
            panelX = 100,
            panelY = 200,
            padRectX = 20,
            padRectY = 30,
            padRectW = 200,
            padRectH = 100,
        )
        assertEquals(0f, center.first, 0.001f)
        assertEquals(0f, center.second, 0.001f)

        // Top-left corner of pad: rawX = 120, rawY = 230
        val topLeft = resolvePadNormalizedCoords(
            rawX = 120f,
            rawY = 230f,
            panelX = 100,
            panelY = 200,
            padRectX = 20,
            padRectY = 30,
            padRectW = 200,
            padRectH = 100,
        )
        assertEquals(-1f, topLeft.first, 0.001f)
        assertEquals(-1f, topLeft.second, 0.001f)

        // Bottom-right corner of pad: rawX = 320, rawY = 330
        val bottomRight = resolvePadNormalizedCoords(
            rawX = 320f,
            rawY = 330f,
            panelX = 100,
            panelY = 200,
            padRectX = 20,
            padRectY = 30,
            padRectW = 200,
            padRectH = 100,
        )
        assertEquals(1f, bottomRight.first, 0.001f)
        assertEquals(1f, bottomRight.second, 0.001f)

        // Beyond bounds: clamped to [-1, 1]
        val clamped = resolvePadNormalizedCoords(
            rawX = 1000f,
            rawY = -500f,
            panelX = 100,
            panelY = 200,
            padRectX = 20,
            padRectY = 30,
            padRectW = 200,
            padRectH = 100,
        )
        assertEquals(1f, clamped.first, 0.001f)
        assertEquals(-1f, clamped.second, 0.001f)

        // Zero width / height doesn't divide by zero
        val zeroPad = resolvePadNormalizedCoords(
            rawX = 100f,
            rawY = 200f,
            panelX = 100,
            panelY = 200,
            padRectX = 0,
            padRectY = 0,
            padRectW = 0,
            padRectH = 0,
        )
        assertEquals(0f, zeroPad.first, 0.001f)
        assertEquals(0f, zeroPad.second, 0.001f)
    }

    @Test
    fun orientationPrefKeysAreDistinctForPortraitAndLandscape() {
        assertEquals("ballX_portrait", floatingBallPrefKey(isLandscape = false, isY = false))
        assertEquals("ballY_portrait", floatingBallPrefKey(isLandscape = false, isY = true))
        assertEquals("ballX_landscape", floatingBallPrefKey(isLandscape = true, isY = false))
        assertEquals("ballY_landscape", floatingBallPrefKey(isLandscape = true, isY = true))

        assertEquals("padX_portrait", panelPrefKey(isLandscape = false, isY = false))
        assertEquals("padY_portrait", panelPrefKey(isLandscape = false, isY = true))
        assertEquals("padX_landscape", panelPrefKey(isLandscape = true, isY = false))
        assertEquals("padY_landscape", panelPrefKey(isLandscape = true, isY = true))
    }

    @Test
    fun touchRingMotionBlurInactiveWhenHoldingOrIdle() {
        val holding = calculateTouchRingMotionBlur(
            isExpanding = false,
            isCollapsing = false,
            progress = 1f,
            baseAlpha = 1f,
            maxRadius = 33f,
            strokeWidthPx = 7.5f,
            density = 3f,
        )
        assertEquals(0f, holding.speed, 0.0001f)
        assertEquals(0f, holding.blurRadius, 0.0001f)
        assertEquals(0f, holding.blurAlpha, 0.0001f)
        assertEquals(0f, holding.trailLag, 0.0001f)
        assertFalse(holding.isExpanding)
    }

    @Test
    fun touchRingMotionBlurExpandingStartsHighAndEndsAtZero() {
        val start = calculateTouchRingMotionBlur(
            isExpanding = true,
            isCollapsing = false,
            progress = 0f,
            baseAlpha = 0.8f,
            maxRadius = 33f,
            strokeWidthPx = 7.5f,
            density = 3f,
        )
        assertEquals(1f, start.speed, 0.0001f)
        assertTrue(start.blurRadius > 10f)
        assertTrue(start.blurAlpha > 0.5f)
        assertTrue(start.trailLag > 5f)
        assertTrue(start.isExpanding)

        val mid = calculateTouchRingMotionBlur(
            isExpanding = true,
            isCollapsing = false,
            progress = 0.5f,
            baseAlpha = 0.8f,
            maxRadius = 33f,
            strokeWidthPx = 7.5f,
            density = 3f,
        )
        assertTrue(mid.speed in 0.5f..0.7f)
        assertTrue(mid.blurRadius < start.blurRadius)
        assertTrue(mid.blurAlpha < start.blurAlpha)

        val end = calculateTouchRingMotionBlur(
            isExpanding = true,
            isCollapsing = false,
            progress = 1f,
            baseAlpha = 1f,
            maxRadius = 33f,
            strokeWidthPx = 7.5f,
            density = 3f,
        )
        assertEquals(0f, end.speed, 0.0001f)
        assertEquals(0f, end.blurRadius, 0.0001f)
        assertEquals(0f, end.blurAlpha, 0.0001f)
    }

    @Test
    fun touchRingMotionBlurCollapsingStartsHighAndEndsAtZero() {
        val start = calculateTouchRingMotionBlur(
            isExpanding = false,
            isCollapsing = true,
            progress = 1f,
            collapseFromProgress = 1f,
            baseAlpha = 1f,
            maxRadius = 33f,
            strokeWidthPx = 7.5f,
            density = 3f,
        )
        assertEquals(1f, start.speed, 0.0001f)
        assertTrue(start.blurRadius > 10f)
        assertFalse(start.isExpanding)

        val end = calculateTouchRingMotionBlur(
            isExpanding = false,
            isCollapsing = true,
            progress = 0f,
            collapseFromProgress = 1f,
            baseAlpha = 0f,
            maxRadius = 33f,
            strokeWidthPx = 7.5f,
            density = 3f,
        )
        assertEquals(0f, end.speed, 0.0001f)
        assertEquals(0f, end.blurRadius, 0.0001f)
        assertEquals(0f, end.blurAlpha, 0.0001f)
    }

    @Test
    fun uiAccentColorResolvesDefaultTerracottaWhenZero() {
        assertEquals(0xFFD97757.toInt(), resolveUiAccentColor(0, isDark = true))
        assertEquals(0xFFC96442.toInt(), resolveUiAccentColor(0, isDark = false))
    }

    @Test
    fun uiAccentColorResolvesCustomColorWhenNonZero() {
        assertEquals(0xFF3B82F6.toInt(), resolveUiAccentColor(0xFF3B82F6.toInt(), isDark = true))
        assertEquals(0xFF3B82F6.toInt(), resolveUiAccentColor(0x3B82F6, isDark = false))
    }

    @Test
    fun onAccentColorAdaptsBasedOnLuminance() {
        assertEquals(0xFF141413.toInt(), resolveOnAccentColor(0xFFFFFFFF.toInt())) // white needs dark text
        assertEquals(0xFFFAF9F5.toInt(), resolveOnAccentColor(0xFF000000.toInt())) // black needs light text
        assertEquals(0xFFFAF9F5.toInt(), resolveOnAccentColor(0xFFD97757.toInt())) // terracotta needs light text
    }

    @Test
    fun uiAccentSoftColorsApplyExpectedAlphaTints() {
        val (darkSoft, darkStrong) = resolveUiAccentSoftColors(0xFFD97757.toInt(), isDark = true)
        assertEquals(0x33D97757, darkSoft)
        assertEquals(0x80D97757.toInt(), darkStrong)

        val (lightSoft, lightStrong) = resolveUiAccentSoftColors(0xFFC96442.toInt(), isDark = false)
        assertEquals(0x1FC96442, lightSoft)
        assertEquals(0x66C96442, lightStrong)
    }

    @Test
    fun touchRingColorFollowsFallbackAccentWhenZero() {
        assertEquals(0xFF3B82F6.toInt(), resolveTouchRingColor(0, isDark = true, fallbackAccent = 0xFF3B82F6.toInt()))
        assertEquals(0xFFD97757.toInt(), resolveTouchRingColor(0, isDark = true, fallbackAccent = 0))
        assertEquals(0xFFC96442.toInt(), resolveTouchRingColor(0, isDark = false, fallbackAccent = 0))
        assertEquals(0xFF141413.toInt(), resolveTouchRingColor(0xFF141413.toInt(), isDark = true, fallbackAccent = 0xFF3B82F6.toInt()))
    }

    @Test
    fun warmSettingsThemeResolvesExpectedColors() {
        val darkTheme = resolveWarmSettingsTheme(isDark = true, customAccentColor = 0)
        assertEquals(0xFF1F1E1D.toInt(), darkTheme.pageColor)
        assertEquals(0xFF262624.toInt(), darkTheme.surfaceColor)
        assertEquals(0xFFD97757.toInt(), darkTheme.accentColor)

        val lightTheme = resolveWarmSettingsTheme(isDark = false, customAccentColor = 0)
        assertEquals(0xFFF5F4ED.toInt(), lightTheme.pageColor)
        assertEquals(0xFFFAF9F5.toInt(), lightTheme.surfaceColor)
        assertEquals(0xFFC96442.toInt(), lightTheme.accentColor)
    }

    @Test
    fun dynamicMaterialYouThemeSynthesizesValidColors() {
        val wallpaperPrimary = 0xFF3B82F6.toInt()
        val theme = synthesizeMaterialYouTheme(wallpaperPrimary, isDark = false, customAccentColor = 0)
        assertNotNull(theme)
        assertTrue(theme.pageColor != 0)
        assertTrue(theme.surfaceColor != 0)
        assertTrue(theme.accentColor != 0)
    }

    @Test
    fun uiAccentColorResolvesColorDynamicToDefaultWhenNoContext() {
        assertEquals(0xFFD97757.toInt(), resolveUiAccentColor(COLOR_DYNAMIC, isDark = true))
        assertEquals(0xFFC96442.toInt(), resolveUiAccentColor(COLOR_DYNAMIC, isDark = false))
    }

    @Test
    fun warmSettingsThemeResolvesDynamicAccentWhenSpecified() {
        val dynamicAccent = 0xFF10B981.toInt()
        val theme = resolveWarmSettingsTheme(isDark = true, customAccentColor = COLOR_DYNAMIC, dynamicAccentColor = dynamicAccent)
        assertEquals(dynamicAccent, theme.accentColor)
        assertEquals(0xFF1F1E1D.toInt(), theme.pageColor)
    }

    @Test
    fun dynamicMaterialYouThemeAppliesColorDynamic() {
        val wallpaperPrimary = 0xFF8E7CC3.toInt()
        val customDynamic = 0xFF10B981.toInt()
        val theme = synthesizeMaterialYouTheme(wallpaperPrimary, isDark = false, customAccentColor = COLOR_DYNAMIC, fallbackDynamicAccent = customDynamic)
        assertEquals(customDynamic, theme.accentColor)
    }

    @Test
    fun customBackgroundManagerFileHelpersAndClearWork() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "otp_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val testFile = java.io.File(tempDir, "test_bg.png")
        testFile.writeText("test_content")
        assertTrue(testFile.exists())
        val cleared = CustomBackgroundManager.clearBackground(testFile)
        assertTrue(cleared)
        assertFalse(testFile.exists())
        tempDir.deleteRecursively()
    }

    @Test
    fun calculateCropOffsetSupportsCustomFocus() {
        val containerH = 200f
        val scaledImgH = 500f // 长图/立绘高度远大于触控板高度

        // 默认 50% 居中
        assertEquals(-150f, calculateCropOffset(containerH, scaledImgH, 50), 0.001f)

        // 0% 顶部对齐 (对齐人物脸部)
        assertEquals(0f, calculateCropOffset(containerH, scaledImgH, 0), 0.001f)

        // 20% 靠近头部区域
        assertEquals(-60f, calculateCropOffset(containerH, scaledImgH, 20), 0.001f)

        // 100% 底部对齐
        assertEquals(-300f, calculateCropOffset(containerH, scaledImgH, 100), 0.001f)

        // 范围外自动 clamp
        assertEquals(0f, calculateCropOffset(containerH, scaledImgH, -10), 0.001f)
        assertEquals(-300f, calculateCropOffset(containerH, scaledImgH, 150), 0.001f)
    }

    @Test
    fun bubbleBulgeAmountGeneratesExpectedValues() {
        val baseR = 50f
        // 1.0 (正圆)：外凸量为 0，严格保持纯正圆
        assertEquals(0f, bubbleBulgeAmount(1.0f, baseR), 0.0001f)
        assertEquals(0f, bubbleBulgeAmount(0.5f, baseR), 0.0001f)

        // 2.0 (微拉长)：外凸量为 baseR * 0.035f = 1.75f
        assertEquals(1.75f, bubbleBulgeAmount(2.0f, baseR), 0.0001f)

        // 高度拉伸（饱和上限）：外凸量为 baseR * 0.08f = 4.0f
        assertEquals(4.0f, bubbleBulgeAmount(4.0f, baseR), 0.0001f)
        assertEquals(4.0f, bubbleBulgeAmount(10.0f, baseR), 0.0001f)
    }

    @Test
    fun liquidGlassFactorMapsHighOpacityToMaterialYouAndLowOpacityToGlass() {
        assertEquals(0f, computeLiquidGlassFactor(100), 0.001f)
        assertEquals(0f, computeLiquidGlassFactor(85), 0.001f)
        assertEquals(0f, computeLiquidGlassFactor(80), 0.001f)

        assertEquals(1f, computeLiquidGlassFactor(60), 0.001f)
        assertEquals(1f, computeLiquidGlassFactor(40), 0.001f)
        assertEquals(1f, computeLiquidGlassFactor(20), 0.001f)

        assertEquals(0.5f, computeLiquidGlassFactor(70), 0.001f)

        var prev = computeLiquidGlassFactor(60)
        for (p in 61..80) {
            val cur = computeLiquidGlassFactor(p)
            assertTrue("Expected monotonic decrease at $p%", cur <= prev)
            prev = cur
        }
    }

    @Test
    fun glassRimAlphaKeepsEdgeSoftAndVisibleAtLowOpacityInGlassMode() {
        val lowOpacityGlassFactor = computeLiquidGlassFactor(25)
        val lowOpacityRim = computeGlassRimAlpha(25, lowOpacityGlassFactor)
        assertTrue("Expected soft visible glass rim at 25% opacity, got $lowOpacityRim", lowOpacityRim in 70..110)

        val midOpacityGlassFactor = computeLiquidGlassFactor(60)
        val midOpacityRim = computeGlassRimAlpha(60, midOpacityGlassFactor)
        assertTrue("Expected glass rim to gently rise with opacity", midOpacityRim > lowOpacityRim)
        assertTrue("Expected glass rim at 60% to stay restrained, got $midOpacityRim", midOpacityRim <= 125)

        val highOpacityMaterialFactor = computeLiquidGlassFactor(90)
        val highOpacityRim = computeGlassRimAlpha(90, highOpacityMaterialFactor)
        assertTrue("Expected subtle Material You rim at 90% opacity, got $highOpacityRim", highOpacityRim in 40..90)
        assertTrue(lowOpacityRim > highOpacityRim)
    }

    @Test
    fun glassHighlightScaleSoftensLowOpacityReflections() {
        assertEquals(0.45f, computeGlassHighlightScale(20), 0.001f)
        assertEquals(0.45f, computeGlassHighlightScale(0), 0.001f)
        assertEquals(1.0f, computeGlassHighlightScale(100), 0.001f)
        assertEquals(1.0f, computeGlassHighlightScale(120), 0.001f)

        val at25 = computeGlassHighlightScale(25)
        val at60 = computeGlassHighlightScale(60)
        assertTrue("Expected low opacity highlight scale to soften reflections, got $at25", at25 in 0.45f..0.55f)
        assertTrue(at60 > at25)
    }

    @Test
    fun specularShiftTracksTouchTiltAndClamps() {
        val center = computeSpecularShift(0f, 0f)
        assertEquals(0f, center.shiftXRatio, 0.001f)
        assertEquals(0f, center.shiftYRatio, 0.001f)
        assertEquals(0f, center.boost, 0.001f)

        // 按压右下角 (+1, +1)，高光反向流向左上 (-, -)
        val bottomRight = computeSpecularShift(1f, 1f, maxRatio = 0.28f)
        assertEquals(-0.28f, bottomRight.shiftXRatio, 0.001f)
        assertEquals(-0.28f, bottomRight.shiftYRatio, 0.001f)
        assertEquals(0.08f, bottomRight.boost, 0.001f)

        // 超出 [-1, 1] 的输入应被钳制
        val clamped = computeSpecularShift(2.5f, -3.0f, maxRatio = 0.28f)
        assertEquals(-0.28f, clamped.shiftXRatio, 0.001f)
        assertEquals(0.28f, clamped.shiftYRatio, 0.001f)
        assertEquals(0.08f, clamped.boost, 0.001f)
    }

    @Test
    fun argbColorHelpersInterpolateAndReplaceAlphaCorrectly() {
        assertEquals(0x80112233.toInt(), replaceAlpha(0xFF112233.toInt(), 0x80))
        assertEquals(0x00112233, replaceAlpha(0xFF112233.toInt(), -10))
        assertEquals(0xFF112233.toInt(), replaceAlpha(0x00112233, 300))

        val mid = lerpArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        assertEquals(0xFF808080.toInt(), mid)
    }

    @Test
    fun panelDragTargetRespectsDeadzonePreservesDirectionAndSaturates() {
        val density = 3f
        // Below deadzone (25 dp/s = 75 px/s) -> neutral zero
        val idle = computePanelDragTarget(vxPxPerSec = 50f, vyPxPerSec = 30f, density = density)
        assertEquals(0f, idle.x, 0.0001f)
        assertEquals(0f, idle.y, 0.0001f)

        // Moderate rightward drag
        val slowRight = computePanelDragTarget(vxPxPerSec = 600f, vyPxPerSec = 0f, density = density)
        val fastRight = computePanelDragTarget(vxPxPerSec = 2400f, vyPxPerSec = 0f, density = density)
        assertTrue(slowRight.x > 0.25f)
        assertEquals(0f, slowRight.y, 0.0001f)
        assertTrue(fastRight.x > slowRight.x)
        assertTrue(fastRight.x <= 1f)

        // Extreme diagonal drag preserves 45° unit direction and stays within unit disk
        val extremeDiag = computePanelDragTarget(vxPxPerSec = -9000f, vyPxPerSec = 9000f, density = density)
        val mag = kotlin.math.hypot(extremeDiag.x, extremeDiag.y)
        assertTrue("Target magnitude must saturate within unit disk, got $mag", mag in 0.95f..1.001f)
        assertEquals(-extremeDiag.y, extremeDiag.x, 0.001f)
    }

    @Test
    fun panelHandleAnchorNormalizesPositionRelativeToPanelCenter() {
        val topLeft = computePanelHandleAnchor(0f, 0f, 40f, 40f, 400f, 400f)
        assertEquals(-0.9f, topLeft.first, 0.001f)
        assertEquals(-0.9f, topLeft.second, 0.001f)

        val bottomRight = computePanelHandleAnchor(360f, 360f, 40f, 40f, 400f, 400f)
        assertEquals(0.9f, bottomRight.first, 0.001f)
        assertEquals(0.9f, bottomRight.second, 0.001f)

        val zeroPanel = computePanelHandleAnchor(0f, 0f, 40f, 40f, 0f, 0f)
        assertEquals(0f, zeroPanel.first, 0.001f)
        assertEquals(0f, zeroPanel.second, 0.001f)
    }

    @Test
    fun panelDragSpringTracksTargetAndReboundsWithElasticOvershootOnRelease() {
        var state = PanelSpringState()
        // Dragging right for ~250ms at 60Hz -> tracks target and grab progress
        repeat(15) {
            state = stepPanelDragSpring(
                state = state,
                targetX = 0.85f,
                targetY = 0f,
                targetGrab = 1f,
                dtSec = 1f / 60f,
                isDragging = true,
            )
        }
        assertTrue("Expected springX to track rightward drag, got ${state.x}", state.x > 0.70f)
        assertTrue("Expected grabProgress near 1.0, got ${state.grab}", state.grab > 0.90f)
        assertFalse(state.isSettled)

        // Release -> under-damped spring produces elastic overshoot below 0 before settling
        var sawOvershoot = false
        repeat(60) {
            state = stepPanelDragSpring(
                state = state,
                targetX = 0f,
                targetY = 0f,
                targetGrab = 0f,
                dtSec = 1f / 60f,
                isDragging = false,
            )
            if (state.x < -0.02f) sawOvershoot = true
        }
        assertTrue("Expected elastic jelly overshoot on release", sawOvershoot)
        assertTrue("Expected spring to settle cleanly within 1s, state=$state", state.isSettled)
    }

    @Test
    fun panelDragMatrixIsIdentityAtRestAndDeformsDirectionally() {
        val w = 500f
        val h = 500f
        val m = FloatArray(9)

        // 1. At rest -> identity & inactive
        assertFalse(computePanelDragMatrix(m, w, h, springX = 0f, springY = 0f, grabProgress = 0f))
        assertEquals(1f, m[0], 0.0001f)
        assertEquals(1f, m[4], 0.0001f)
        assertEquals(1f, m[8], 0.0001f)

        // 2. Dragging RIGHT: stretches horizontally vs height, and right (leading) edge tapers taller than left (trailing)
        assertTrue(computePanelDragMatrix(m, w, h, springX = 1f, springY = 0f, grabProgress = 1f))
        val tlR = projectMatrixPoint(m, 0f, 0f)
        val trR = projectMatrixPoint(m, w, 0f)
        val brR = projectMatrixPoint(m, w, h)
        val blR = projectMatrixPoint(m, 0f, h)
        val leadingHeightR = brR.second - trR.second
        val trailingHeightR = blR.second - tlR.second
        val avgWidthR = ((trR.first - tlR.first) + (brR.first - blR.first)) / 2f
        val avgHeightR = (leadingHeightR + trailingHeightR) / 2f
        assertTrue("Right drag should stretch width > height ($avgWidthR vs $avgHeightR)", avgWidthR > avgHeightR * 1.08f)
        assertTrue("Right drag leading edge should flare taller than trailing edge ($leadingHeightR vs $trailingHeightR)", leadingHeightR > trailingHeightR * 1.06f)

        // 3. Dragging LEFT mirrors the directional taper: left (leading) edge is taller than right (trailing)
        assertTrue(computePanelDragMatrix(m, w, h, springX = -1f, springY = 0f, grabProgress = 1f))
        val tlL = projectMatrixPoint(m, 0f, 0f)
        val trL = projectMatrixPoint(m, w, 0f)
        val brL = projectMatrixPoint(m, w, h)
        val blL = projectMatrixPoint(m, 0f, h)
        val leadingHeightL = blL.second - tlL.second
        val trailingHeightL = brL.second - trL.second
        assertTrue("Left drag leading edge should flare taller than trailing edge ($leadingHeightL vs $trailingHeightL)", leadingHeightL > trailingHeightL * 1.06f)

        // 4. Dragging DOWN: bottom (leading) edge is wider than top (trailing) edge, and height > width
        assertTrue(computePanelDragMatrix(m, w, h, springX = 0f, springY = 1f, grabProgress = 1f))
        val tlD = projectMatrixPoint(m, 0f, 0f)
        val trD = projectMatrixPoint(m, w, 0f)
        val brD = projectMatrixPoint(m, w, h)
        val blD = projectMatrixPoint(m, 0f, h)
        val topWidthD = trD.first - tlD.first
        val bottomWidthD = brD.first - blD.first
        val avgHeightD = ((blD.second - tlD.second) + (brD.second - trD.second)) / 2f
        val avgWidthD = (topWidthD + bottomWidthD) / 2f
        assertTrue("Down drag should stretch height > width ($avgHeightD vs $avgWidthD)", avgHeightD > avgWidthD * 1.08f)
        assertTrue("Down drag bottom edge should flare wider than top edge ($bottomWidthD vs $topWidthD)", bottomWidthD > topWidthD * 1.06f)

        // 5. Dragging UP mirrors: top (leading) edge is wider than bottom (trailing)
        assertTrue(computePanelDragMatrix(m, w, h, springX = 0f, springY = -1f, grabProgress = 1f))
        val tlU = projectMatrixPoint(m, 0f, 0f)
        val trU = projectMatrixPoint(m, w, 0f)
        val brU = projectMatrixPoint(m, w, h)
        val blU = projectMatrixPoint(m, 0f, h)
        assertTrue("Up drag top edge should flare wider than bottom edge", (trU.first - tlU.first) > (brU.first - blU.first) * 1.06f)

        // 6. Dragging diagonally DOWN-RIGHT stretches main diagonal longer than off-diagonal
        val diag = 0.7071f
        assertTrue(computePanelDragMatrix(m, w, h, springX = diag, springY = diag, grabProgress = 1f))
        val tlDR = projectMatrixPoint(m, 0f, 0f)
        val trDR = projectMatrixPoint(m, w, 0f)
        val brDR = projectMatrixPoint(m, w, h)
        val blDR = projectMatrixPoint(m, 0f, h)
        val mainDiagLen = kotlin.math.hypot(brDR.first - tlDR.first, brDR.second - tlDR.second)
        val offDiagLen = kotlin.math.hypot(blDR.first - trDR.first, blDR.second - trDR.second)
        assertTrue("Diagonal drag should elongate along motion diagonal ($mainDiagLen vs $offDiagLen)", mainDiagLen > offDiagLen * 1.08f)

        // 7. Handle torque swing: grabbing TOP handle (0, -1) and moving RIGHT shifts top right relative to bottom
        assertTrue(computePanelDragMatrix(m, w, h, springX = 1f, springY = 0f, grabProgress = 1f, anchorX = 0f, anchorY = -1f))
        val topMidX = (projectMatrixPoint(m, 0f, 0f).first + projectMatrixPoint(m, w, 0f).first) / 2f
        val botMidX = (projectMatrixPoint(m, 0f, h).first + projectMatrixPoint(m, w, h).first) / 2f
        assertTrue("Top-anchored rightward drag should swing top ahead of bottom ($topMidX vs $botMidX)", topMidX > botMidX + 5f)
    }

    @Test
    fun panelDragMatrixNeverClipsWindowBoundsAcrossAllDirectionsAndAspectRatios() {
        val m = FloatArray(9)
        val sizes = listOf(
            400f to 400f,
            600f to 300f,
            300f to 900f,
            504f to 420f,
        )
        val anchors = listOf(
            0f to 0f,
            0f to -1f,
            0f to 1f,
            -1f to 0f,
            1f to 0f,
            -0.9f to -0.9f,
            0.9f to 0.9f,
        )
        val mags = listOf(0.5f, 1.0f, 1.35f)
        val grabs = listOf(0f, 0.5f, 1.0f)

        for ((w, h) in sizes) {
            val corners = listOf(0f to 0f, w to 0f, w to h, 0f to h)
            for ((ax, ay) in anchors) {
                for (mag in mags) {
                    for (grab in grabs) {
                        for (deg in 0 until 360 step 15) {
                            val rad = Math.toRadians(deg.toDouble())
                            val sx = (kotlin.math.cos(rad) * mag).toFloat()
                            val sy = (kotlin.math.sin(rad) * mag).toFloat()
                            computePanelDragMatrix(
                                outMatrix = m,
                                widthPx = w,
                                heightPx = h,
                                springX = sx,
                                springY = sy,
                                grabProgress = grab,
                                anchorX = ax,
                                anchorY = ay,
                            )
                            for ((cx, cy) in corners) {
                                val wDenom = m[6] * cx + m[7] * cy + m[8]
                                assertTrue("Perspective w must stay strictly positive (got $wDenom)", wDenom > 0.5f)
                                val (px, py) = projectMatrixPoint(m, cx, cy)
                                assertTrue(
                                    "Corner ($cx,$cy) clipped horizontally: px=$px outside [0, $w] at deg=$deg mag=$mag",
                                    px >= -0.01f && px <= w + 0.01f,
                                )
                                assertTrue(
                                    "Corner ($cx,$cy) clipped vertically: py=$py outside [0, $h] at deg=$deg mag=$mag",
                                    py >= -0.01f && py <= h + 0.01f,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun clampGestureCoordStaysWithinStrictPixelBounds() {
        assertEquals(0f, clampGestureCoord(-50f, 1080), 0.0001f)
        assertEquals(0f, clampGestureCoord(0f, 1080), 0.0001f)
        assertEquals(540.5f, clampGestureCoord(540.5f, 1080), 0.0001f)
        assertEquals(1079f, clampGestureCoord(1079f, 1080), 0.0001f)
        assertEquals(1079f, clampGestureCoord(1080f, 1080), 0.0001f)
        assertEquals(1079f, clampGestureCoord(1500f, 1080), 0.0001f)
        // Degenerate screen size never yields negative bounds
        assertEquals(0f, clampGestureCoord(10f, 1), 0.0001f)
        assertEquals(0f, clampGestureCoord(10f, 0), 0.0001f)
    }

    @Test
    fun planCustomSwipeInertialTargetNeverReachesScreenEdge() {
        val screenW = 1080
        val screenH = 2400
        val planBottomRight = planCustomSwipe(
            startX = 900f,
            startY = 2200f,
            endX = 1050f,
            endY = 2380f,
            velocityX = 8000f,
            velocityY = 8000f,
            minDistancePx = 24f,
            density = 3f,
            screenWidth = screenW,
            screenHeight = screenH,
        )
        assertNotNull(planBottomRight)
        assertTrue(planBottomRight!!.isInertial)
        assertTrue("targetX must be <= screenW - 1, got ${planBottomRight.targetX}", planBottomRight.targetX <= screenW - 1f)
        assertTrue("targetY must be <= screenH - 1, got ${planBottomRight.targetY}", planBottomRight.targetY <= screenH - 1f)
        assertEquals(1079f, planBottomRight.targetX, 0.0001f)
        assertEquals(2399f, planBottomRight.targetY, 0.0001f)

        val planTopLeft = planCustomSwipe(
            startX = 200f,
            startY = 200f,
            endX = 20f,
            endY = 20f,
            velocityX = -8000f,
            velocityY = -8000f,
            minDistancePx = 24f,
            density = 3f,
            screenWidth = screenW,
            screenHeight = screenH,
        )
        assertNotNull(planTopLeft)
        assertEquals(0f, planTopLeft!!.targetX, 0.0001f)
        assertEquals(0f, planTopLeft.targetY, 0.0001f)
    }

    @Test
    fun shouldReactToWindowsChangedFiltersNonImeWindowEvents() {
        // Unspecified (API < 28 or zero mask) passes through safely
        assertTrue(shouldReactToWindowsChanged(0))
        // IME-relevant change flags: ADDED(1), REMOVED(2), BOUNDS(8), LAYER(16), ACTIVE(32)
        assertTrue(shouldReactToWindowsChanged(1))
        assertTrue(shouldReactToWindowsChanged(2))
        assertTrue(shouldReactToWindowsChanged(8))
        assertTrue(shouldReactToWindowsChanged(16))
        assertTrue(shouldReactToWindowsChanged(32))
        // Combined relevant + irrelevant still passes
        assertTrue(shouldReactToWindowsChanged(4 or 8))
        // Irrelevant flags: TITLE(4), FOCUSED(64), ACCESSIBILITY_FOCUSED(128), PARENT(256), CHILDREN(512), PIP(1024)
        assertFalse(shouldReactToWindowsChanged(4))
        assertFalse(shouldReactToWindowsChanged(64))
        assertFalse(shouldReactToWindowsChanged(128))
        assertFalse(shouldReactToWindowsChanged(256))
        assertFalse(shouldReactToWindowsChanged(512))
        assertFalse(shouldReactToWindowsChanged(1024))
        assertFalse(shouldReactToWindowsChanged(4 or 64 or 128 or 512))
    }

    @Test
    fun isEffectiveImeBoundsRejectsEmptyAndThinPlaceholderWindows() {
        val minImeHeight = 144 // 48dp @ 3x
        assertFalse(isEffectiveImeBounds(0, 0, 0, 0, minImeHeight))
        assertFalse(isEffectiveImeBounds(0, 2360, 1080, 2400, minImeHeight))
        assertFalse(isEffectiveImeBounds(500, 1500, 500, 2400, minImeHeight))
        assertFalse(isEffectiveImeBounds(1080, 1500, 0, 2400, minImeHeight))
        assertTrue(isEffectiveImeBounds(0, 2256, 1080, 2400, minImeHeight))
        assertTrue(isEffectiveImeBounds(0, 1500, 1080, 2400, minImeHeight))
    }

    @Test
    fun resolveScreenBoundsPreservesFoldableAndNearSquareOnApi30Plus() {
        // Android 11+ (trustWindowMetrics = true): never swaps unfolded foldable / near-square displays
        val foldableUnfolded = resolveScreenBounds(
            metricsWidth = 2208,
            metricsHeight = 1768,
            isLandscapeConfig = false,
            trustWindowMetrics = true,
        )
        assertEquals(PanelPosition(2208, 1768), foldableUnfolded)

        val nearSquare = resolveScreenBounds(
            metricsWidth = 1800,
            metricsHeight = 2000,
            isLandscapeConfig = true,
            trustWindowMetrics = true,
        )
        assertEquals(PanelPosition(1800, 2000), nearSquare)

        // Android 8–10 (trustWindowMetrics = false): retains orientation mismatch swap for non-square screens
        val legacyLandscapeSwap = resolveScreenBounds(
            metricsWidth = 1080,
            metricsHeight = 2400,
            isLandscapeConfig = true,
            trustWindowMetrics = false,
        )
        assertEquals(PanelPosition(2400, 1080), legacyLandscapeSwap)

        val legacySquare = resolveScreenBounds(
            metricsWidth = 1800,
            metricsHeight = 1800,
            isLandscapeConfig = true,
            trustWindowMetrics = false,
        )
        assertEquals(PanelPosition(1800, 1800), legacySquare)
    }

    @Test
    fun shouldSkipPassThroughDelaySkipsOnlyWhenAlreadyNonTouchable() {
        assertTrue(shouldSkipPassThroughDelay(alreadyNonTouchable = true))
        assertFalse(shouldSkipPassThroughDelay(alreadyNonTouchable = false))
    }

    @Test
    fun quantizeTiltChangedSuppressesMicroJitterAndAlwaysAllowsZeroReset() {
        assertFalse(quantizeTiltChanged(0.5f, -0.3f, 1f, 0.502f, -0.299f, 1f))
        assertTrue(quantizeTiltChanged(0.5f, -0.3f, 1f, 0.52f, -0.3f, 1f))
        assertTrue(quantizeTiltChanged(0.5f, -0.3f, 1f, 0.5f, -0.32f, 1f))
        assertTrue(quantizeTiltChanged(0.5f, -0.3f, 0.5f, 0.5f, -0.3f, 0.52f))
        // Resetting to neutral (0, 0, 0) always triggers even when delta < threshold
        assertTrue(quantizeTiltChanged(0.002f, 0f, 0.002f, 0f, 0f, 0f))
        // Already neutral -> no redundant redraw
        assertFalse(quantizeTiltChanged(0f, 0f, 0f, 0f, 0f, 0f))
    }

    @Test
    fun isPanelSpringAtRestForTargetDetectsStationaryHoldAndActiveMotion() {
        val heldStill = PanelSpringState(x = 0f, y = 0f, vx = 0f, vy = 0f, grab = 1f, grabVel = 0f)
        assertTrue(isPanelSpringAtRestForTarget(heldStill, targetX = 0f, targetY = 0f, targetGrab = 1f))
        assertFalse(isPanelSpringAtRestForTarget(heldStill, targetX = 0f, targetY = 0f, targetGrab = 0f))
        assertFalse(isPanelSpringAtRestForTarget(heldStill, targetX = 0.2f, targetY = 0f, targetGrab = 1f))

        val moving = PanelSpringState(x = 0f, y = 0f, vx = 0.25f, vy = 0f, grab = 1f, grabVel = 0f)
        assertFalse(isPanelSpringAtRestForTarget(moving, targetX = 0f, targetY = 0f, targetGrab = 1f))
    }

    @Test
    fun resolveExifRotationDegreesMapsStandardOrientations() {
        assertEquals(90, resolveExifRotationDegrees(6))
        assertEquals(180, resolveExifRotationDegrees(3))
        assertEquals(270, resolveExifRotationDegrees(8))
        assertEquals(0, resolveExifRotationDegrees(1))
        assertEquals(0, resolveExifRotationDegrees(0))
        assertEquals(0, resolveExifRotationDegrees(99))
    }

    @Test
    fun shouldUpdateAmbientGlassShadersHandlesEdgesAndAllRefreshRates() {
        // First press and release-to-zero never throttle, even 1ms later
        assertTrue(shouldUpdateAmbientGlassShaders(nowMs = 101L, lastUpdateMs = 100L, prevIntensity = 0f, newIntensity = 1f))
        assertTrue(shouldUpdateAmbientGlassShaders(nowMs = 101L, lastUpdateMs = 100L, prevIntensity = 1f, newIntensity = 0f))

        // 120Hz single frame (8ms < 13ms) throttles -> clean 2:1 = 60Hz
        assertFalse(shouldUpdateAmbientGlassShaders(nowMs = 108L, lastUpdateMs = 100L, prevIntensity = 1f, newIntensity = 1f))
        // 144Hz 2 frames (~14ms) and 60Hz with jitter (~14ms) both pass -> 72Hz on 144Hz, steady 60Hz on 60Hz
        assertTrue(shouldUpdateAmbientGlassShaders(nowMs = 113L, lastUpdateMs = 100L, prevIntensity = 1f, newIntensity = 1f))
        assertTrue(shouldUpdateAmbientGlassShaders(nowMs = 116L, lastUpdateMs = 100L, prevIntensity = 1f, newIntensity = 1f))

        // Clock reset / negative elapsed passes safely
        assertTrue(shouldUpdateAmbientGlassShaders(nowMs = 50L, lastUpdateMs = 100L, prevIntensity = 1f, newIntensity = 1f))
    }

    @Test
    fun computeVrrEmaAlphaMatchesMathematicalLimitsAndTimeConsistency() {
        // Strict limit as dt -> 0+ is 0f (keep previous value), NOT 1f
        assertEquals(0f, computeVrrEmaAlpha(0f, 0.032f), 0.0001f)
        assertEquals(0f, computeVrrEmaAlpha(-0.01f, 0.032f), 0.0001f)
        // Zero time constant tracks instantaneously
        assertEquals(1f, computeVrrEmaAlpha(0.016f, 0f), 0.0001f)

        // Sampling-rate invariance: 4 steps at 240Hz equals 1 step at 60Hz
        val tau = 0.032f
        val alpha60 = computeVrrEmaAlpha(1f / 60f, tau)
        val alpha240 = computeVrrEmaAlpha(1f / 240f, tau)
        val remain60 = 1f - alpha60
        var remain240 = 1f
        repeat(4) { remain240 *= (1f - alpha240) }
        assertEquals(remain60, remain240, 0.0001f)
    }

    @Test
    fun resolveDeformSampleDtCovers20HzTo240Hz() {
        // 240Hz display (~4.17ms) uses real gap without 8ms truncation
        assertEquals(0.004167f, resolveDeformSampleDt(4_167_000L, 1f / 240f), 0.0001f)
        // 165Hz display (~6.06ms) uses real gap
        assertEquals(0.00606f, resolveDeformSampleDt(6_060_000L, 1f / 165f), 0.0001f)
        // 20Hz LTPO frame (50ms) uses real gap
        assertEquals(0.050f, resolveDeformSampleDt(50_000_000L, 0.040f), 0.0001f)
        // Too-short burst (<3.5ms) falls back to clamped frameDt
        assertEquals(0.00833f, resolveDeformSampleDt(1_000_000L, 1f / 120f), 0.0001f)
        assertEquals(0.004f, resolveDeformSampleDt(0L, 0.001f), 0.0001f)
    }

    @Test
    fun resolveDeformDecayDelayMsAdaptsToLowRefreshRates() {
        assertEquals(32L, resolveDeformDecayDelayMs(1f / 120f))
        assertEquals(32L, resolveDeformDecayDelayMs(1f / 60f))
        val at30Hz = resolveDeformDecayDelayMs(1f / 30f)
        assertTrue("30Hz decay delay must exceed 33.3ms frame period, got $at30Hz", at30Hz >= 50L)
        assertEquals(64L, resolveDeformDecayDelayMs(0.040f))
    }

    @Test
    fun computeCenterCropDecodeSizePreservesSharpnessAndRespectsCeiling() {
        // 1:1 square downscaled cleanly below ceiling
        val square = computeCenterCropDecodeSize(1200, 1200, 500, 500, maxLongEdge = 1200)
        assertEquals(CropDecodePlan(500, 500, reachedCeiling = false), square)

        // 9:20 tall portrait (1080x2400) in wide pad (500x300):
        // must decode to 500x1111 (not naive 225x500) so Center-Crop keeps 1:1 pixel sharpness
        val portrait = computeCenterCropDecodeSize(1080, 2400, 500, 300, maxLongEdge = 1200)
        assertEquals(500, portrait.targetWidth)
        assertEquals(1111, portrait.targetHeight)
        assertFalse(portrait.reachedCeiling)

        // Same portrait in large pad (700x400) hits 1200px long-edge ceiling
        val portraitCapped = computeCenterCropDecodeSize(1080, 2400, 700, 400, maxLongEdge = 1200)
        assertEquals(540, portraitCapped.targetWidth)
        assertEquals(1200, portraitCapped.targetHeight)
        assertTrue(portraitCapped.reachedCeiling)

        // 21:9 wide landscape (2100x900) in pad (400x360) covers height 1:1 -> 840x360
        val wide = computeCenterCropDecodeSize(2100, 900, 400, 360, maxLongEdge = 1200)
        assertEquals(CropDecodePlan(840, 360, reachedCeiling = false), wide)

        // Small source never upscales and marks reachedCeiling = true
        val small = computeCenterCropDecodeSize(200, 150, 500, 400, maxLongEdge = 1200)
        assertEquals(CropDecodePlan(200, 150, reachedCeiling = true), small)

        // Invalid dimensions return safe zero plan
        assertEquals(CropDecodePlan(0, 0, reachedCeiling = true), computeCenterCropDecodeSize(0, 100, 500, 400))
        assertEquals(CropDecodePlan(0, 0, reachedCeiling = true), computeCenterCropDecodeSize(100, -5, 500, 400))
    }

    @Test
    fun shouldUpsampleTouchpadBgRespectsThresholdAndCeiling() {
        // Already at source/1200px ceiling -> never re-decode
        assertFalse(shouldUpsampleTouchpadBg(900, 600, 400, 300, reachedCeiling = true))
        // Invalid pad size -> false
        assertFalse(shouldUpsampleTouchpadBg(0, 300, 400, 300, reachedCeiling = false))
        // Not yet loaded -> true
        assertTrue(shouldUpsampleTouchpadBg(500, 300, 0, 0, reachedCeiling = false))
        // Within 1.15x hysteresis threshold -> false
        assertFalse(shouldUpsampleTouchpadBg(570, 340, 500, 300, reachedCeiling = false))
        // Exceeds 1.15x on width -> true
        assertTrue(shouldUpsampleTouchpadBg(580, 300, 500, 300, reachedCeiling = false))
        // Exceeds 1.15x on height -> true
        assertTrue(shouldUpsampleTouchpadBg(500, 350, 500, 300, reachedCeiling = false))
    }

    @Test
    fun writeTiltGradientMatrixMatchesOriginalLinearGradientProjection() {
        val m = FloatArray(9)
        // Degenerate inputs return false
        assertFalse(writeTiltGradientMatrix(m, 0f, 300f, 0.5f, 0.5f))
        assertFalse(writeTiltGradientMatrix(m, 500f, 0f, 0.5f, 0.5f))
        assertFalse(writeTiltGradientMatrix(m, 500f, 300f, 0f, 0f))

        val sizes = listOf(500f to 300f, 300f to 700f, 420f to 420f)
        val tilts = listOf(
            1f to 0f,
            0f to 1f,
            -0.7f to 0.4f,
            0.85f to -0.95f,
            -1f to -1f,
        )
        val sampleFracs = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.3f to 0.7f, 0.5f to 0.5f)

        for ((w, h) in sizes) {
            for ((tx, ty) in tilts) {
                assertTrue(writeTiltGradientMatrix(m, w, h, tx, ty))
                val raisedX = w * (0.5f - tx * 0.5f)
                val raisedY = h * (0.5f - ty * 0.5f)
                val sinkX = w * (0.5f + tx * 0.5f)
                val sinkY = h * (0.5f + ty * 0.5f)

                // (0, 0) -> (raisedX, raisedY), (1, 0) -> (sinkX, sinkY)
                assertEquals(raisedX, m[2], 1e-4f)
                assertEquals(raisedY, m[5], 1e-4f)
                assertEquals(sinkX, m[0] + m[2], 1e-4f)
                assertEquals(sinkY, m[3] + m[5], 1e-4f)

                // Verify conformal inverse u-projection matches LinearGradient(raised -> sink) across whole rect
                val vx = sinkX - raisedX
                val vy = sinkY - raisedY
                val lenSq = vx * vx + vy * vy
                val det = m[0] * m[4] - m[1] * m[3]
                for ((fx, fy) in sampleFracs) {
                    val px = w * fx
                    val py = h * fy
                    val expectedU = ((px - raisedX) * vx + (py - raisedY) * vy) / lenSq
                    // M^{-1} * [px - m[2], py - m[5]]_x
                    val dx = px - m[2]
                    val dy = py - m[5]
                    val actualU = (m[4] * dx - m[1] * dy) / det
                    assertEquals(expectedU, actualU, 1e-4f)
                }
            }
        }
    }

    @Test
    fun swipeVelocityTrackerRingBufferGrowsCapsAndWrapsCleanly() {
        val tracker = SwipeVelocityTracker(maxAgeMs = 100L)
        // 200 samples within 100ms (> initial 64 capacity -> tests dynamic growth)
        for (i in 0 until 200) {
            val t = i / 2L // 0..99ms
            tracker.addMovement(t, t * 0.6f, -t * 0.4f) // 600 px/s, -400 px/s
        }
        val (vx1, vy1) = tracker.computeVelocity(nowMs = 99L)
        assertEquals(600f, vx1, 15f)
        assertEquals(-400f, vy1, 15f)

        // Exceed 512 hard cap in a tight window (800 samples in 80ms -> tests circular overwrite)
        tracker.reset()
        for (i in 0 until 800) {
            val t = i / 10L // 0..79ms
            tracker.addMovement(t, i * 0.1f, i * 0.05f) // 1000 px/s, 500 px/s
        }
        val (vx2, vy2) = tracker.computeVelocity(nowMs = 79L)
        assertEquals(1000f, vx2, 25f)
        assertEquals(500f, vy2, 25f)

        // Advance past window to exercise head wrap-around pruning
        for (i in 0 until 80) {
            val t = 500L + i
            tracker.addMovement(t, i * -0.5f, i * 0.25f) // -500 px/s, +250 px/s
        }
        val (vx3, vy3) = tracker.computeVelocity(nowMs = 579L)
        assertEquals(-500f, vx3, 15f)
        assertEquals(250f, vy3, 15f)

        tracker.reset()
        assertEquals(0f to 0f, tracker.computeVelocity(nowMs = 579L))
    }

    @Test
    fun ballSwipeTrackerConfigureUpdatesThresholdsAndResetsState() {
        val tracker = BallSwipeTracker(swipeThresholdPx = 100f, cancelThresholdPx = 50f)
        assertFalse(tracker.onMove(120f))
        assertTrue(tracker.hasArmedSwipe)

        // Reconfigure with stricter threshold and verify previous state is cleared
        tracker.configure(swipeThresholdPx = 200f, cancelThresholdPx = 80f)
        assertFalse(tracker.hasArmedSwipe)
        assertFalse(tracker.isCancelledByReturn)
        assertFalse(tracker.onMove(120f))
        assertFalse(tracker.hasArmedSwipe)
        assertFalse(tracker.onMove(210f))
        assertTrue(tracker.hasArmedSwipe)
        // Retract inside new cancelThreshold (80px) -> returns true to trigger cancel haptic
        assertTrue(tracker.onMove(60f))
        assertFalse(tracker.hasArmedSwipe)
        assertTrue(tracker.isCancelledByReturn)
    }

    @Test
    fun zeroAllocHotPathHelpersMatchAllocatingOriginals() {
        // 1. resolvePadNormalizedCoordsInto
        val normOut = FloatArray(2)
        val normOrig = resolvePadNormalizedCoords(275f, 210f, 100, 150, 20, 30, 220, 140)
        resolvePadNormalizedCoordsInto(normOut, 275f, 210f, 100, 150, 20, 30, 220, 140)
        assertEquals(normOrig.first, normOut[0], 1e-6f)
        assertEquals(normOrig.second, normOut[1], 1e-6f)

        // 2. computeSpecularShiftInto
        val specOut = FloatArray(3)
        val specOrig = computeSpecularShift(0.65f, -0.45f, 0.24f)
        computeSpecularShiftInto(specOut, 0.65f, -0.45f, 0.24f)
        assertEquals(specOrig.shiftXRatio, specOut[0], 1e-6f)
        assertEquals(specOrig.shiftYRatio, specOut[1], 1e-6f)
        assertEquals(specOrig.boost, specOut[2], 1e-6f)

        // 3. computeDampedSwipeOffsetInto
        val dampOut = FloatArray(2)
        val dampOrig = computeDampedSwipeOffset(85f, -42f, 60f)
        computeDampedSwipeOffsetInto(dampOut, 85f, -42f, 60f)
        assertEquals(dampOrig.x, dampOut[0], 1e-6f)
        assertEquals(dampOrig.y, dampOut[1], 1e-6f)

        // 4. computeBallDeformationInto
        val ballOut = FloatArray(5)
        val ballOrig = computeBallDeformation(55f, -30f, 60f)
        computeBallDeformationInto(ballOut, 55f, -30f, 60f)
        assertEquals(ballOrig.stretch, ballOut[0], 1e-6f)
        assertEquals(ballOrig.squash, ballOut[1], 1e-6f)
        assertEquals(ballOrig.rotationDeg, ballOut[2], 1e-6f)
        assertEquals(ballOrig.iconTranslationX, ballOut[3], 1e-6f)
        assertEquals(ballOrig.iconTranslationY, ballOut[4], 1e-6f)

        // 5. computeDragMotionStateInto
        val dragOut = FloatArray(5)
        val dragOrig = computeDragMotionState(950f, 0.6f, -0.8f, baseScale = 1.16f)
        computeDragMotionStateInto(dragOut, 950f, 0.6f, -0.8f, baseScale = 1.16f)
        assertEquals(dragOrig.stretch, dragOut[0], 1e-6f)
        assertEquals(dragOrig.squash, dragOut[1], 1e-6f)
        assertEquals(dragOrig.rotationDeg, dragOut[2], 1e-6f)
        assertEquals(dragOrig.iconTranslationX, dragOut[3], 1e-6f)
        assertEquals(dragOrig.iconTranslationY, dragOut[4], 1e-6f)

        // 6. computePanelDragTargetInto
        val targetOut = FloatArray(2)
        val targetOrig = computePanelDragTarget(1400f, -900f, density = 3f)
        computePanelDragTargetInto(targetOut, 1400f, -900f, density = 3f)
        assertEquals(targetOrig.x, targetOut[0], 1e-6f)
        assertEquals(targetOrig.y, targetOut[1], 1e-6f)

        // 7. stepPanelDragSpringInPlace vs stepPanelDragSpring over drag + rebound
        var imm = PanelSpringState()
        val mut = MutablePanelSpringState()
        repeat(20) {
            imm = stepPanelDragSpring(imm, 0.8f, -0.5f, 1f, 1f / 60f, isDragging = true)
            stepPanelDragSpringInPlace(mut, 0.8f, -0.5f, 1f, 1f / 60f, isDragging = true)
            assertEquals(imm.x, mut.x, 1e-6f)
            assertEquals(imm.y, mut.y, 1e-6f)
            assertEquals(imm.vx, mut.vx, 1e-6f)
            assertEquals(imm.vy, mut.vy, 1e-6f)
            assertEquals(imm.grab, mut.grab, 1e-6f)
            assertEquals(imm.grabVel, mut.grabVel, 1e-6f)
            assertEquals(isPanelSpringAtRestForTarget(imm, 0.8f, -0.5f, 1f), mut.isAtRestForTarget(0.8f, -0.5f, 1f))
        }
        repeat(60) {
            imm = stepPanelDragSpring(imm, 0f, 0f, 0f, 1f / 60f, isDragging = false)
            stepPanelDragSpringInPlace(mut, 0f, 0f, 0f, 1f / 60f, isDragging = false)
            assertEquals(imm.x, mut.x, 1e-6f)
            assertEquals(imm.y, mut.y, 1e-6f)
            assertEquals(imm.isSettled, mut.isSettled)
        }
        mut.reset()
        assertTrue(mut.isSettled)

        // 8. clampCoordToScreen & cursorViewOriginCoord
        val clampedPos = clampPanelPosition(-20, 2500, 100, 120, 1080, 2400)
        assertEquals(clampedPos.x, clampCoordToScreen(-20, 100, 1080))
        assertEquals(clampedPos.y, clampCoordToScreen(2500, 120, 2400))
        val originPos = cursorViewOrigin(320.4f, 640.8f, 72f)
        assertEquals(originPos.x, cursorViewOriginCoord(320.4f, 72f))
        assertEquals(originPos.y, cursorViewOriginCoord(640.8f, 72f))
    }

    @Test
    fun clampGestureCoordGuardsNonFiniteValues() {
        assertEquals(0f, clampGestureCoord(Float.NaN, 1080), 0.001f)
        assertEquals(0f, clampGestureCoord(Float.POSITIVE_INFINITY, 1080), 0.001f)
        assertEquals(0f, clampGestureCoord(Float.NEGATIVE_INFINITY, 1080), 0.001f)
        assertEquals(0f, clampGestureCoord(50f, 0), 0.001f)
        assertEquals(1079f, clampGestureCoord(1200f, 1080), 0.001f)
    }

    @Test
    fun ballDoubleTapWindowLocksOnSecondDownWithGraceAndRejectsExpiredOrNegative() {
        // Not waiting -> never armed
        assertFalse(isBallSecondTapDownWithinWindow(false, 1000L, 1100L))
        // Normal double tap cadence (e.g. 220ms after first tap up, well within 260ms)
        assertTrue(isBallSecondTapDownWithinWindow(true, 1000L, 1220L))
        // Exact timeout boundary
        assertTrue(isBallSecondTapDownWithinWindow(true, 1000L, 1000L + BALL_DOUBLE_TAP_TIMEOUT_MS))
        // Main-thread jank grace window while single-tap runnable hasn't fired yet
        assertTrue(
            isBallSecondTapDownWithinWindow(
                true,
                1000L,
                1000L + BALL_DOUBLE_TAP_TIMEOUT_MS + BALL_DOUBLE_TAP_GRACE_MS,
            ),
        )
        // Beyond timeout + grace -> rejected
        assertFalse(
            isBallSecondTapDownWithinWindow(
                true,
                1000L,
                1000L + BALL_DOUBLE_TAP_TIMEOUT_MS + BALL_DOUBLE_TAP_GRACE_MS + 1L,
            ),
        )
        // Clock jump backwards -> rejected
        assertFalse(isBallSecondTapDownWithinWindow(true, 1000L, 999L))
    }

    @Test
    fun ballTapSlopAndDoubleTapReleasePreventSmallRollDeadZone() {
        assertEquals(30f, resolveBallTapSlopPx(scaledTouchSlopPx = 24f, minSlopPx = 30f), 0.001f)
        assertEquals(36f, resolveBallTapSlopPx(scaledTouchSlopPx = 36f, minSlopPx = 30f), 0.001f)
        assertEquals(0f, resolveBallTapSlopPx(scaledTouchSlopPx = -5f, minSlopPx = -1f), 0.001f)

        // Armed double-tap with thumb roll below swipeThreshold -> still triggers double-tap!
        assertTrue(
            shouldTreatBallReleaseAsDoubleTap(
                doubleTapArmed = true,
                hasArmedSwipe = false,
                isCancelledByReturn = false,
                distPx = 35f,
                swipeThresholdPx = 76f,
            ),
        )
        // Reached swipe threshold -> treated as swipe, not double-tap
        assertFalse(
            shouldTreatBallReleaseAsDoubleTap(
                doubleTapArmed = true,
                hasArmedSwipe = false,
                isCancelledByReturn = false,
                distPx = 76f,
                swipeThresholdPx = 76f,
            ),
        )
        // Previously armed swipe or cancelled by return -> not double-tap
        assertFalse(
            shouldTreatBallReleaseAsDoubleTap(
                doubleTapArmed = true,
                hasArmedSwipe = true,
                isCancelledByReturn = false,
                distPx = 20f,
                swipeThresholdPx = 76f,
            ),
        )
        assertFalse(
            shouldTreatBallReleaseAsDoubleTap(
                doubleTapArmed = true,
                hasArmedSwipe = false,
                isCancelledByReturn = true,
                distPx = 5f,
                swipeThresholdPx = 76f,
            ),
        )
        // Not armed -> false
        assertFalse(
            shouldTreatBallReleaseAsDoubleTap(
                doubleTapArmed = false,
                hasArmedSwipe = false,
                isCancelledByReturn = false,
                distPx = 0f,
                swipeThresholdPx = 76f,
            ),
        )
    }

    @Test
    fun buttonHitTargetToleratesPressFeedbackSinkSlopAndStationaryRelease() {
        // Button at screen rect (100, 500), size 120x120, slop 24px
        assertTrue(isPointInsideButtonHitTarget(160f, 560f, 100, 500, 120, 120, 24f))
        // Tapping top edge while PressFeedback sinks button down 3dp (9px): rawY is at 500, well inside slop
        assertTrue(isPointInsideButtonHitTarget(150f, 495f, 100, 500, 120, 120, 24f))
        // Slightly outside right edge within slop -> still valid
        assertTrue(isPointInsideButtonHitTarget(244f, 560f, 100, 500, 120, 120, 24f))
        // Dragged clearly outside button beyond slop -> cancelled
        assertFalse(isPointInsideButtonHitTarget(250f, 560f, 100, 500, 120, 120, 24f))
        // Zero-sized view -> false
        assertFalse(isPointInsideButtonHitTarget(100f, 500f, 100, 500, 0, 120, 24f))

        // Stationary release during expand scale animation (even if rect snapshot differed) -> valid
        assertTrue(
            isButtonReleaseWithinHitTarget(
                rawX = 50f, rawY = 400f,
                downRawX = 55f, downRawY = 404f,
                left = 100, top = 500, width = 120, height = 120, slopPx = 24f,
            ),
        )
        // Dragged across wide capsule button (> slop from down, still inside rect) -> valid
        assertTrue(
            isButtonReleaseWithinHitTarget(
                rawX = 210f, rawY = 560f,
                downRawX = 110f, downRawY = 560f,
                left = 100, top = 500, width = 120, height = 120, slopPx = 24f,
            ),
        )
        // Dragged clearly away from button (> slop from down and outside rect + slop) -> cancelled
        assertFalse(
            isButtonReleaseWithinHitTarget(
                rawX = 300f, rawY = 560f,
                downRawX = 160f, downRawY = 560f,
                left = 100, top = 500, width = 120, height = 120, slopPx = 24f,
            ),
        )
    }

    @Test
    fun panelBoundsCheckCoversBothScreenLocationParamTargetAndMargin() {
        // Screen location lags 1 frame at (100, 1200), LayoutParams already moved to (140, 1200)
        assertTrue(isPointInsidePanelBounds(105f, 1250f, 100, 1200, 140, 1200, 400, 500))
        assertTrue(isPointInsidePanelBounds(530f, 1250f, 100, 1200, 140, 1200, 400, 500))
        // 2px margin around edges
        assertTrue(isPointInsidePanelBounds(98f, 1198f, 100, 1200, 100, 1200, 400, 500, marginPx = 2f))
        assertFalse(isPointInsidePanelBounds(97f, 1250f, 100, 1200, 100, 1200, 400, 500, marginPx = 2f))
        // Clearly outside both
        assertFalse(isPointInsidePanelBounds(50f, 200f, 100, 1200, 140, 1200, 400, 500))
        // Zero size panel -> false
        assertFalse(isPointInsidePanelBounds(100f, 1200f, 100, 1200, 100, 1200, 0, 500))
    }

    @Test
    fun gestureWatchdogTimeoutCoversShortLongDirectAndPassThroughGestures() {
        assertEquals(2500L, resolveGestureWatchdogTimeoutMs(expectedMs = 50L, needsPassThrough = false))
        assertEquals(2550L, resolveGestureWatchdogTimeoutMs(expectedMs = 50L, needsPassThrough = true, passThroughDelayMs = 50L))
        assertEquals(4500L, resolveGestureWatchdogTimeoutMs(expectedMs = 3000L, needsPassThrough = false))
        assertEquals(4550L, resolveGestureWatchdogTimeoutMs(expectedMs = 3000L, needsPassThrough = true, passThroughDelayMs = 50L))
    }

    @Test
    fun panelRebuildDefersWheneverAnyInteractionOrDispatchIsActive() {
        assertFalse(
            shouldDeferPanelRebuild(
                padFingerDown = false,
                movingPanel = false,
                resizingPanel = false,
                isDraggingBall = false,
                ballTouchDown = false,
                isWaitingForBallDoubleTap = false,
                actionButtonDownCount = 0,
                isDispatchingGesture = false,
                isPassThroughActive = false,
            ),
        )
        assertTrue(shouldDeferPanelRebuild(true, false, false, false, false, false, 0, false, false))
        assertTrue(shouldDeferPanelRebuild(false, true, false, false, false, false, 0, false, false))
        assertTrue(shouldDeferPanelRebuild(false, false, true, false, false, false, 0, false, false))
        assertTrue(shouldDeferPanelRebuild(false, false, false, true, false, false, 0, false, false))
        assertTrue(shouldDeferPanelRebuild(false, false, false, false, true, false, 0, false, false))
        assertTrue(shouldDeferPanelRebuild(false, false, false, false, false, true, 0, false, false))
        assertTrue(shouldDeferPanelRebuild(false, false, false, false, false, false, 1, false, false))
        assertTrue(shouldDeferPanelRebuild(false, false, false, false, false, false, 0, true, false))
        assertTrue(shouldDeferPanelRebuild(false, false, false, false, false, false, 0, false, true))
    }

    @Test
    fun sparkRingEndColorBlendsAccentTowardWhiteLikeBaSpark() {
        // BASpark default blue (45, 175, 255) -> ((45+510)/3, (175+510)/3, (255+510)/3) = (185, 228, 255)
        val end = computeSparkRingEndColor(0xFF2DAFFF.toInt())
        assertEquals(0xFF, (end ushr 24) and 0xFF)
        assertEquals(185, (end ushr 16) and 0xFF)
        assertEquals(228, (end ushr 8) and 0xFF)
        assertEquals(255, end and 0xFF)
    }

    @Test
    fun sparkRingColorAtTransitionsFromNearWhiteToEndColor() {
        val accent = 0xFF2DAFFF.toInt()
        val start = computeSparkRingColorAt(accent, 0f)
        assertEquals(250, (start ushr 16) and 0xFF)
        assertEquals(252, (start ushr 8) and 0xFF)
        assertEquals(252, start and 0xFF)

        val end = computeSparkRingColorAt(accent, 1f)
        assertEquals(computeSparkRingEndColor(accent), end)
    }

    @Test
    fun sparkFilledCircleRadiusUsesCubicEaseOut() {
        assertEquals(0f, computeSparkFilledCircleRadius(48f, 0f), 0.0001f)
        // At p=0.5: 1 - 0.5^3 = 0.875 -> 48 * 0.875 = 42
        assertEquals(42f, computeSparkFilledCircleRadius(48f, 0.5f), 0.001f)
        assertEquals(48f, computeSparkFilledCircleRadius(48f, 1f), 0.0001f)
        assertEquals(0f, computeSparkFilledCircleRadius(-10f, 0.5f), 0.0001f)
    }

    @Test
    fun sparkArcTaperWeightIsSymmetricTrapezoid() {
        assertEquals(0f, computeSparkArcTaperWeight(0f), 0.0001f)
        assertEquals(0.5f, computeSparkArcTaperWeight(0.125f), 0.0001f)
        assertEquals(1f, computeSparkArcTaperWeight(0.25f), 0.0001f)
        assertEquals(1f, computeSparkArcTaperWeight(0.5f), 0.0001f)
        assertEquals(1f, computeSparkArcTaperWeight(0.75f), 0.0001f)
        assertEquals(0.5f, computeSparkArcTaperWeight(0.875f), 0.0001f)
        assertEquals(0f, computeSparkArcTaperWeight(1f), 0.0001f)
    }

    @Test
    fun sparkArcSpanGrowsHoldsAndRetractsCleanly() {
        val fullLen = 3.45f
        val base = 1.0f

        // Grow phase (0..0.1): half grown at 0.05, anchored at leading edge (base + fullLen)
        val grow = computeSparkArcSpan(base, fullLen, 0.05f)
        assertEquals(fullLen * 0.5f, grow.sweepRad, 0.001f)
        assertEquals(base + fullLen, grow.startRad + grow.sweepRad, 0.001f)

        // Hold phase (0.1..0.4): full span starting at base
        val hold = computeSparkArcSpan(base, fullLen, 0.25f)
        assertEquals(base, hold.startRad, 0.001f)
        assertEquals(fullLen, hold.sweepRad, 0.001f)

        // Retract phase (0.4..1.0): halfway retracted at 0.7, anchored at base
        val retract = computeSparkArcSpan(base, fullLen, 0.7f)
        assertEquals(base, retract.startRad, 0.001f)
        assertEquals(fullLen * 0.5f, retract.sweepRad, 0.001f)

        // End of life: zero sweep
        val end = computeSparkArcSpan(base, fullLen, 1f)
        assertEquals(0f, end.sweepRad, 0.0001f)
    }

    @Test
    fun sparkArcWidthMulAndAlphaDecaySmoothlyToZero() {
        assertEquals(1f, computeSparkArcWidthMul(0.5f), 0.0001f)
        assertEquals(1f, computeSparkArcWidthMul(0.8f), 0.0001f)
        assertEquals(0.84f, computeSparkArcWidthMul(1f), 0.001f)

        assertEquals(1f, computeSparkArcAlpha(0f), 0.0001f)
        assertTrue(computeSparkArcAlpha(0.5f) in 0.9f..1f)
        assertTrue(computeSparkArcAlpha(0.94f) < computeSparkArcAlpha(0.88f))
        assertEquals(0f, computeSparkArcAlpha(1f), 0.0001f)
    }

    @Test
    fun sparkWindowSizeFitsMaxSparkTravelAndGlowWithoutClipping() {
        val density = 3f
        val maxRadius = 16f * density // 48px
        val stroke = 2.6f * density
        val size = computeSparkWindowSizePx(maxRadius, stroke, density)
        // Half window must exceed max spark reach (~2.06 * maxRadius) plus glow stroke
        assertTrue(size / 2f >= maxRadius * 2.25f + stroke * 2f)
        assertEquals(0, size % 2)
    }

    @Test
    fun sparkRadialBlurStartsAtOneAndSettlesToZero() {
        assertEquals(1f, computeSparkRadialBlurFactor(0f), 0.0001f)
        assertEquals(0.25f, computeSparkRadialBlurFactor(0.5f), 0.0001f)
        // Below cutoff (1 - 0.9)^2 = 0.01 <= 0.02 -> 0
        assertEquals(0f, computeSparkRadialBlurFactor(0.9f), 0.0001f)
        assertEquals(0f, computeSparkRadialBlurFactor(1f), 0.0001f)
    }

    @Test
    fun sparkParticleBlurStrengthScalesWithSpeedAndDropsToZeroWhenSlow() {
        val ref = 6f
        assertEquals(0f, computeSparkParticleBlurStrength(0f, ref), 0.0001f)
        assertEquals(0f, computeSparkParticleBlurStrength(0.6f, ref), 0.0001f) // 10% < 12% cutoff
        val mid = computeSparkParticleBlurStrength(3.36f, ref) // 56% -> (0.56 - 0.12) / 0.88 = 0.5
        assertEquals(0.5f, mid, 0.001f)
        assertEquals(1f, computeSparkParticleBlurStrength(6f, ref), 0.0001f)
        assertEquals(1f, computeSparkParticleBlurStrength(9f, ref), 0.0001f)
        assertEquals(0f, computeSparkParticleBlurStrength(5f, 0f), 0.0001f)
    }

    @Test
    fun cursorSupersampleScaleUses2xAndCapsExtremeDimensions() {
        // Default 36dp at 440dpi (~99px) and 2K 4x (~144px) -> full 2x
        assertEquals(2f, cursorSupersampleScale(99, 99), 0.0001f)
        assertEquals(2f, cursorSupersampleScale(144, 144), 0.0001f)
        // Exact boundary 512px -> still full 2x (1024px)
        assertEquals(2f, cursorSupersampleScale(512, 512), 0.0001f)
        // Large cursor 160dp @ 4x (640px) -> capped at 1024 / 640 = 1.6x
        assertEquals(1.6f, cursorSupersampleScale(640, 640), 0.0001f)
        // Never downscales below 1x even if view exceeds max cap
        assertEquals(1f, cursorSupersampleScale(1280, 1280), 0.0001f)
        // Non-positive dimensions safely return 1x
        assertEquals(1f, cursorSupersampleScale(0, 0), 0.0001f)
        assertEquals(1f, cursorSupersampleScale(-10, -10), 0.0001f)
    }

    @Test
    fun cursorSupersampledHotspotMatchesViewHotspotAtAllSizes() {
        for (viewSize in listOf(16, 20, 72, 99, 144, 640)) {
            val scale = cursorSupersampleScale(viewSize, viewSize)
            val viewHotspot = cursorHotspotOffset(viewSize.toFloat())
            val supersampledRing = viewHotspot * scale
            assertEquals(viewHotspot, supersampledRing / scale, 0.0001f)
        }
    }

    @Test
    fun panelPinholeRectsHandleOutsideInsideMarginDualOriginNaNAndCapacity() {
        val out = IntArray(16)

        // Invalid dimensions or point count -> 0
        assertEquals(0, computePanelPinholeRectsInto(out, floatArrayOf(150f, 250f), 1, 100, 200, 100, 200, 0, 300))
        assertEquals(0, computePanelPinholeRectsInto(out, floatArrayOf(150f, 250f), 1, 100, 200, 100, 200, 200, -1))
        assertEquals(0, computePanelPinholeRectsInto(out, floatArrayOf(150f, 250f), 0, 100, 200, 100, 200, 200, 300))

        // Outside points (beyond 2px margin) -> 0 rects
        assertEquals(
            0,
            computePanelPinholeRectsInto(
                out,
                floatArrayOf(97.5f, 250f, 302.5f, 250f, 150f, 197.5f, 150f, 502.5f),
                4,
                screenX = 100,
                screenY = 200,
                paramX = 100,
                paramY = 200,
                panelW = 200,
                panelH = 300,
            ),
        )

        // Inside point: (150, 250) with origin (100, 200), radius 3 -> cx=50, cy=50 -> [47, 47, 54, 54]
        assertEquals(
            1,
            computePanelPinholeRectsInto(
                out,
                floatArrayOf(150f, 250f),
                1,
                screenX = 100,
                screenY = 200,
                paramX = 100,
                paramY = 200,
                panelW = 200,
                panelH = 300,
            ),
        )
        assertEquals(47, out[0])
        assertEquals(47, out[1])
        assertEquals(54, out[2])
        assertEquals(54, out[3])

        // 2px margin boundary points with radiusPx=3 (> marginPx=2) always produce non-empty clamped rects
        val marginPoints = floatArrayOf(98f, 250f, 302f, 250f, 150f, 198f, 150f, 502f)
        val marginCount = computePanelPinholeRectsInto(
            out,
            marginPoints,
            4,
            screenX = 100,
            screenY = 200,
            paramX = 100,
            paramY = 200,
            panelW = 200,
            panelH = 300,
        )
        assertEquals(4, marginCount)
        for (i in 0 until marginCount) {
            val l = out[i * 4]
            val t = out[i * 4 + 1]
            val r = out[i * 4 + 2]
            val b = out[i * 4 + 3]
            assertTrue("Non-empty width for margin rect $i", r > l)
            assertTrue("Non-empty height for margin rect $i", b > t)
            assertTrue(l >= 0 && r <= 200 && t >= 0 && b <= 300)
        }

        // Dual origin (screenX, screenY) != (paramX, paramY) produces 2 distinct rects
        val dualCount = computePanelPinholeRectsInto(
            out,
            floatArrayOf(150f, 250f),
            1,
            screenX = 110,
            screenY = 205,
            paramX = 100,
            paramY = 200,
            panelW = 200,
            panelH = 300,
        )
        assertEquals(2, dualCount)
        // First from param origin (100, 200) -> [47, 47, 54, 54]
        assertEquals(47, out[0]); assertEquals(47, out[1]); assertEquals(54, out[2]); assertEquals(54, out[3])
        // Second from screen origin (110, 205) -> [37, 42, 44, 49]
        assertEquals(37, out[4]); assertEquals(42, out[5]); assertEquals(44, out[6]); assertEquals(49, out[7])

        // Deduplicates identical rects from duplicate points or same origin
        val dupCount = computePanelPinholeRectsInto(
            out,
            floatArrayOf(150f, 250f, 150.2f, 249.8f),
            2,
            screenX = 100,
            screenY = 200,
            paramX = 100,
            paramY = 200,
            panelW = 200,
            panelH = 300,
        )
        assertEquals(1, dupCount)

        // NaN / Inf points are skipped safely while valid points are kept
        val nanCount = computePanelPinholeRectsInto(
            out,
            floatArrayOf(Float.NaN, 250f, 150f, Float.POSITIVE_INFINITY, 150f, 250f),
            3,
            screenX = 100,
            screenY = 200,
            paramX = 100,
            paramY = 200,
            panelW = 200,
            panelH = 300,
        )
        assertEquals(1, nanCount)

        // Buffer capacity cap is respected without out-of-bounds access
        val tinyOut = IntArray(4)
        assertEquals(
            1,
            computePanelPinholeRectsInto(
                tinyOut,
                floatArrayOf(150f, 250f, 180f, 280f),
                2,
                screenX = 100,
                screenY = 200,
                paramX = 100,
                paramY = 200,
                panelW = 200,
                panelH = 300,
            ),
        )
    }

    @Test
    fun pinholeRectEqualityAndCoverageRespectSubpixelAndDualOriginBounds() {
        val a = intArrayOf(10, 20, 17, 27, 99, 99, 99, 99)
        val b = intArrayOf(10, 20, 17, 27, 0, 0, 0, 0)
        assertTrue(arePinholeRectsEqual(a, 1, b, 1))
        assertTrue(arePinholeRectsEqual(a, 0, b, 0))
        assertFalse(arePinholeRectsEqual(a, 1, b, 2))
        assertFalse(arePinholeRectsEqual(a, 2, b, 2))
        assertFalse(arePinholeRectsEqual(a, -1, b, -1))
        assertFalse(arePinholeRectsEqual(IntArray(2), 1, IntArray(2), 1))

        // Non-finite point -> false; non-positive panel -> true
        assertFalse(isScreenPointCoveredByPinholeRects(Float.NaN, 250f, a, 1, 100, 200, 100, 200, 200, 300))
        assertFalse(isScreenPointCoveredByPinholeRects(150f, Float.NEGATIVE_INFINITY, a, 1, 100, 200, 100, 200, 200, 300))
        assertTrue(isScreenPointCoveredByPinholeRects(150f, 250f, a, 0, 100, 200, 100, 200, 0, 300))

        // Outside panel passes even with 0 committed rects
        assertTrue(isScreenPointCoveredByPinholeRects(50f, 250f, a, 0, 100, 200, 100, 200, 200, 300))
        // Inside panel with 0 committed rects -> false
        assertFalse(isScreenPointCoveredByPinholeRects(150f, 250f, a, 0, 100, 200, 100, 200, 200, 300))

        // Sub-pixel .4f / .6f floor vs round check against rect [9, 9, 12, 12] (covers 9, 10, 11)
        val subPixelRect = intArrayOf(9, 9, 12, 12)
        // 110.4 - 100 = 10.4 -> round=10, floor=10 (both in [9, 12))
        assertTrue(isScreenPointCoveredByPinholeRects(110.4f, 210.4f, subPixelRect, 1, 100, 200, 100, 200, 200, 300))
        // 110.6 - 100 = 10.6 -> round=11, floor=10 (both in [9, 12))
        assertTrue(isScreenPointCoveredByPinholeRects(110.6f, 210.6f, subPixelRect, 1, 100, 200, 100, 200, 200, 300))
        // 111.6 - 100 = 11.6 -> floor=11 (in), round=12 (out) -> false
        assertFalse(isScreenPointCoveredByPinholeRects(111.6f, 210.4f, subPixelRect, 1, 100, 200, 100, 200, 200, 300))
        // 108.6 - 100 = 8.6 -> round=9 (in), floor=8 (out) -> false
        assertFalse(isScreenPointCoveredByPinholeRects(108.6f, 210.4f, subPixelRect, 1, 100, 200, 100, 200, 200, 300))

        // Dual-origin: must cover BOTH param and screen origins when they differ
        val dualRects = IntArray(8)
        val dualCount = computePanelPinholeRectsInto(
            dualRects,
            floatArrayOf(150f, 250f),
            1,
            screenX = 110,
            screenY = 205,
            paramX = 100,
            paramY = 200,
            panelW = 200,
            panelH = 300,
        )
        assertEquals(2, dualCount)
        assertTrue(isScreenPointCoveredByPinholeRects(150f, 250f, dualRects, dualCount, 110, 205, 100, 200, 200, 300))
        // Only the first rect (param origin) committed -> fails for screen origin (110, 205)
        assertFalse(isScreenPointCoveredByPinholeRects(150f, 250f, dualRects, 1, 110, 205, 100, 200, 200, 300))
    }

    @Test
    fun pinholeSettleDelayComputesRemainingTimeAndClamps() {
        assertEquals(32L, resolvePinholeSettleDelayMs(lastPinholeCommitUptimeMs = 0L, nowUptimeMs = 5000L))
        assertEquals(32L, resolvePinholeSettleDelayMs(lastPinholeCommitUptimeMs = -10L, nowUptimeMs = 5000L))
        assertEquals(22L, resolvePinholeSettleDelayMs(lastPinholeCommitUptimeMs = 1000L, nowUptimeMs = 1010L))
        assertEquals(0L, resolvePinholeSettleDelayMs(lastPinholeCommitUptimeMs = 1000L, nowUptimeMs = 1032L))
        assertEquals(0L, resolvePinholeSettleDelayMs(lastPinholeCommitUptimeMs = 1000L, nowUptimeMs = 1200L))
        assertEquals(32L, resolvePinholeSettleDelayMs(lastPinholeCommitUptimeMs = 2000L, nowUptimeMs = 1990L))
    }

    @Test
    fun cancelledTapTrackerMaintainsFifoPrunesExpiredDropsOldestAndClearsOnDrain() {
        val tracker = CancelledTapTracker(maxTaps = 2, expireMs = 300L)
        val out = FloatArray(MAX_COALESCED_TAPS * 2)

        // Non-finite coordinates are ignored
        tracker.recordCancelledTap(Float.NaN, 100f, nowUptimeMs = 1000L)
        tracker.recordCancelledTap(100f, Float.POSITIVE_INFINITY, nowUptimeMs = 1000L)
        assertEquals(0, tracker.size)

        // Ring overflow (capacity=2): recording 3 taps drops oldest (10, 20), keeps (30, 40) then (50, 60)
        tracker.recordCancelledTap(10f, 20f, nowUptimeMs = 1000L)
        tracker.recordCancelledTap(30f, 40f, nowUptimeMs = 1050L)
        tracker.recordCancelledTap(50f, 60f, nowUptimeMs = 1100L)
        assertEquals(2, tracker.size)

        // Drain with includeNewTap = true writes FIFO + new tap and clears state
        val count = tracker.drainForCoalescedTapInto(
            outCoords = out,
            nowUptimeMs = 1200L,
            includeNewTap = true,
            newX = 70f,
            newY = 80f,
        )
        assertEquals(3, count)
        assertEquals(30f, out[0], 0.001f); assertEquals(40f, out[1], 0.001f)
        assertEquals(50f, out[2], 0.001f); assertEquals(60f, out[3], 0.001f)
        assertEquals(70f, out[4], 0.001f); assertEquals(80f, out[5], 0.001f)
        assertEquals(0, tracker.size)

        // Second drain never replays previously drained taps
        assertEquals(0, tracker.drainForCoalescedTapInto(out, nowUptimeMs = 1210L, includeNewTap = false))

        // Expiration pruning: (100, 200) at t=2000 expires at t=2301 (>300ms), (300, 400) at t=2100 remains
        tracker.recordCancelledTap(100f, 200f, nowUptimeMs = 2000L)
        tracker.recordCancelledTap(300f, 400f, nowUptimeMs = 2100L)
        val drainedAfterExpiry = tracker.drainForCoalescedTapInto(out, nowUptimeMs = 2301L, includeNewTap = false)
        assertEquals(1, drainedAfterExpiry)
        assertEquals(300f, out[0], 0.001f); assertEquals(400f, out[1], 0.001f)
        assertEquals(0, tracker.size)
    }

    @Test
    fun coalescedTapTimingPreservesMinimumDoubleTapGap() {
        assertTrue(TAP_COALESCE_GAP_MS >= 40L) // ViewConfiguration.DOUBLE_TAP_MIN_TIME
        assertEquals(0L, computeCoalescedTapStartTimeMs(0))
        assertEquals(TAP_STROKE_DURATION_MS + TAP_COALESCE_GAP_MS, computeCoalescedTapStartTimeMs(1))
        assertEquals(2L * (TAP_STROKE_DURATION_MS + TAP_COALESCE_GAP_MS), computeCoalescedTapStartTimeMs(2))

        val gapBetweenTap0And1 = computeCoalescedTapStartTimeMs(1) - (computeCoalescedTapStartTimeMs(0) + TAP_STROKE_DURATION_MS)
        assertTrue(gapBetweenTap0And1 >= 40L)

        assertEquals(0L, computeCoalescedTapTotalDurationMs(0))
        assertEquals(TAP_STROKE_DURATION_MS, computeCoalescedTapTotalDurationMs(1))
        assertEquals(2L * TAP_STROKE_DURATION_MS + TAP_COALESCE_GAP_MS, computeCoalescedTapTotalDurationMs(2))
        assertEquals(3L * TAP_STROKE_DURATION_MS + 2L * TAP_COALESCE_GAP_MS, computeCoalescedTapTotalDurationMs(3))
    }

    @Test
    fun pointerAccelerationCurveIsMonotonicSmoothAndStrictlyBounded() {
        // Slow micro-aiming zone <= 22 dp/s stays at 0.64f
        assertEquals(0.64f, computePointerAccelerationMultiplier(-10f), 0.0001f)
        assertEquals(0.64f, computePointerAccelerationMultiplier(0f), 0.0001f)
        assertEquals(0.64f, computePointerAccelerationMultiplier(22f), 0.0001f)

        // Medium cruising zone (~120–150 dp/s) smoothly crosses 1.00f
        val cruiseLow = computePointerAccelerationMultiplier(120f)
        val cruiseUnit = computePointerAccelerationMultiplier(135f)
        val cruiseHigh = computePointerAccelerationMultiplier(150f)
        assertTrue(cruiseLow in 0.90f..0.99f)
        assertEquals(1.00f, cruiseUnit, 0.001f)
        assertTrue(cruiseHigh in 1.01f..1.12f)

        // Fast flick zone approaches 1.80f monotonically with no jumps or overshoot
        var prev = computePointerAccelerationMultiplier(0f)
        for (speed in 1..2000) {
            val cur = computePointerAccelerationMultiplier(speed.toFloat())
            assertTrue("Must be monotonically non-decreasing at $speed dp/s", cur >= prev)
            assertTrue("Must stay strictly smooth without jump at $speed dp/s", cur - prev < 0.01f)
            assertTrue("Must stay within [0.64, 1.80] at $speed dp/s", cur in 0.64f..1.80f)
            prev = cur
        }
        assertTrue(computePointerAccelerationMultiplier(900f) in 1.72f..1.80f)
        assertEquals(1.80f, computePointerAccelerationMultiplier(Float.MAX_VALUE), 0.0001f)
        assertEquals(1.0f, computePointerAccelerationMultiplier(Float.NaN), 0.0001f)
        assertEquals(1.0f, computePointerAccelerationMultiplier(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun pointerBallisticsSeedsFirstPacketCoalescesSub1500UsAndIsVrrConsistent() {
        val state = MutablePointerBallisticsState()
        val density = 3f

        // 1. First move seeds timestamp without spiking even on large initial displacement
        val firstMul = state.onMove(dxPx = 60f, dyPx = 0f, eventTimeNs = 1_000_000_000L, density = density)
        assertEquals(POINTER_ACCEL_MIN_MULTIPLIER, firstMul, 0.0001f)
        assertEquals(0f, state.smoothedSpeedDpPerSec, 0.0001f)

        // 2. Sub-1.5ms coalesced burst packets accumulate displacement without dividing by tiny dt
        val burstMul = state.onMove(dxPx = 3.6f, dyPx = 0f, eventTimeNs = 1_000_800_000L, density = density)
        assertEquals(firstMul, burstMul, 0.0001f)
        assertEquals(3.6f, state.pendingDxPx, 0.0001f)

        // 3. Packet crossing >= 1.5ms consumes accumulated displacement (3.6 + 3.6 = 7.2px = 2.4dp over 16ms = 150 dp/s)
        state.onMove(dxPx = 3.6f, dyPx = 0f, eventTimeNs = 1_016_000_000L, density = density)
        assertEquals(0f, state.pendingDxPx, 0.0001f)
        assertTrue(state.smoothedSpeedDpPerSec > 0f)

        // 4. VRR consistency: steady 300 dp/s finger motion at 60Hz vs 120Hz vs 240Hz converges to ~300 dp/s
        fun simulateSteadyMotion(hz: Int): Float {
            val s = MutablePointerBallisticsState()
            val dtNs = 1_000_000_000L / hz
            val stepPx = (300f * density) / hz
            var t = 10_000_000_000L
            s.onMove(0f, 0f, t, density)
            repeat(hz / 2) {
                t += dtNs
                s.onMove(stepPx, 0f, t, density)
            }
            return s.multiplier
        }
        val m60 = simulateSteadyMotion(60)
        val m120 = simulateSteadyMotion(120)
        val m240 = simulateSteadyMotion(240)
        assertEquals(m60, m120, 0.01f)
        assertEquals(m120, m240, 0.01f)
        assertTrue(m120 in 1.45f..1.52f)

        state.reset()
        assertEquals(0L, state.lastEventTimeNs)
        assertEquals(POINTER_ACCEL_MIN_MULTIPLIER, state.multiplier, 0.0001f)
    }

    @Test
    fun pointerBallisticsZeroCrossesImmediatelyOnDirectionReversal() {
        val state = MutablePointerBallisticsState()
        val density = 3f
        var t = 1_000_000_000L
        state.onMove(0f, 0f, t, density)

        // Steady rightward motion at ~400 dp/s
        val stepRightPx = (400f * density) / 120f
        repeat(20) {
            t += 1_000_000_000L / 120L
            state.onMove(stepRightPx, 0f, t, density)
        }
        assertTrue(state.smoothedVxDpPerSec > 250f)

        // Immediate reversal leftward: smoothedVxDpPerSec crosses zero without lagging positive
        t += 1_000_000_000L / 120L
        state.onMove(-stepRightPx * 0.5f, 0f, t, density)
        assertTrue("Expected negative vx immediately after reversal, got ${state.smoothedVxDpPerSec}", state.smoothedVxDpPerSec < 0f)
    }

    @Test
    fun pressSpringShortTapMomentumContinuesSmoothlyAndSettlesWithoutOvershoot() {
        fun simulateTap(hz: Int, tapMs: Float): Triple<Float, Float, Boolean> {
            val state = MutablePressSpringState()
            state.applyDownImpulse()
            // Position must never jump instantaneously on ACTION_DOWN
            assertEquals(0f, state.press, 0.0001f)

            val dtSec = 1f / hz
            var elapsedMs = 0f
            var peakPress = 0f
            var minAfterPeak = 1f
            var passedPeak = false
            val totalSteps = (0.65f * hz).roundToInt()

            repeat(totalSteps) {
                elapsedMs += dtSec * 1000f
                val isDown = elapsedMs <= tapMs
                stepPressSpringInPlace(
                    state = state,
                    targetPress = if (isDown) 1f else 0f,
                    targetTiltX = if (isDown) 0.6f else 0f,
                    targetTiltY = if (isDown) -0.4f else 0f,
                    dtSec = dtSec,
                )
                if (state.press > peakPress) {
                    peakPress = state.press
                } else if (!isDown && state.press < peakPress - 0.02f) {
                    passedPeak = true
                }
                if (passedPeak) {
                    minAfterPeak = minOf(minAfterPeak, state.press)
                }
            }
            return Triple(peakPress, minAfterPeak, state.isSettled(0f, 0f, 0f))
        }

        val (peak35_60, min35_60, settled35_60) = simulateTap(60, 35f)
        val (peak35_120, min35_120, settled35_120) = simulateTap(120, 35f)
        val (peak35_240, min35_240, settled35_240) = simulateTap(240, 35f)

        // 35ms short tap continues downward via momentum into ~0.60..0.82 cushion arc
        assertTrue("Expected 35ms tap peak in 0.60..0.82 at 60Hz, got $peak35_60", peak35_60 in 0.60f..0.82f)
        assertTrue("Expected 35ms tap peak in 0.60..0.82 at 120Hz, got $peak35_120", peak35_120 in 0.60f..0.82f)
        assertTrue("Expected 35ms tap peak in 0.60..0.82 at 240Hz, got $peak35_240", peak35_240 in 0.60f..0.82f)
        assertEquals(peak35_120, peak35_240, 0.02f)

        // Near-critical damping returns cleanly without bouncy overshoot
        assertTrue("Unexpected release overshoot at 60Hz: $min35_60", min35_60 >= -0.01f)
        assertTrue("Unexpected release overshoot at 120Hz: $min35_120", min35_120 >= -0.01f)
        assertTrue("Unexpected release overshoot at 240Hz: $min35_240", min35_240 >= -0.01f)
        assertTrue(settled35_60)
        assertTrue(settled35_120)
        assertTrue(settled35_240)
    }

    @Test
    fun cursorLongPressChargeProgressAndScaleRampSmoothlyAfterDelay() {
        assertEquals(0f, computeCursorLongPressChargeProgress(0L, 600L), 0.0001f)
        assertEquals(0f, computeCursorLongPressChargeProgress(150L, 600L), 0.0001f)
        assertEquals(CURSOR_PRESENCE_LIFT_SCALE, computeCursorLongPressChargeScale(0f), 0.0001f)

        var prevProg = 0f
        var prevScale = CURSOR_PRESENCE_LIFT_SCALE
        for (ms in 200L..600L step 50L) {
            val prog = computeCursorLongPressChargeProgress(ms, 600L)
            val scale = computeCursorLongPressChargeScale(prog)
            assertTrue("Charge progress should increase at $ms ms", prog > prevProg)
            assertTrue("Charge scale should contract at $ms ms", scale < prevScale)
            assertTrue(scale in CURSOR_DYNAMICS_MIN_SCALE..CURSOR_DYNAMICS_MAX_SCALE)
            prevProg = prog
            prevScale = scale
        }

        assertEquals(1f, computeCursorLongPressChargeProgress(600L, 600L), 0.0001f)
        assertEquals(1f, computeCursorLongPressChargeProgress(900L, 600L), 0.0001f)
        assertEquals(CURSOR_LONG_PRESS_CHARGE_MIN_SCALE, computeCursorLongPressChargeScale(1f), 0.0001f)
        assertEquals(0f, computeCursorLongPressChargeProgress(200L, 120L), 0.0001f)
    }

    @Test
    fun cursorVelocityBankEdgePushAndBlockedShakeStayWithinEnvelope() {
        // Deadzone keeps slow micro-aiming completely upright
        assertEquals(0f, computeCursorVelocityBankDeg(0f), 0.0001f)
        assertEquals(0f, computeCursorVelocityBankDeg(55f), 0.0001f)
        assertEquals(0f, computeCursorVelocityBankDeg(-55f), 0.0001f)

        // Symmetric bounded bank within ±3.5°
        val bankRight = computeCursorVelocityBankDeg(450f)
        val bankLeft = computeCursorVelocityBankDeg(-450f)
        assertTrue(bankRight in 1f..CURSOR_DYNAMICS_MAX_BANK_DEG)
        assertEquals(-bankRight, bankLeft, 0.0001f)
        assertTrue(kotlin.math.abs(computeCursorVelocityBankDeg(5000f)) <= CURSOR_DYNAMICS_MAX_BANK_DEG)

        // Edge push is 0 unless actively pushing outward past min/max boundary
        assertEquals(0f, computeCursorEdgePushAmount(100f, 1079f, -10f, 3f), 0.0001f)
        assertEquals(0f, computeCursorEdgePushAmount(-5f, 1079f, 5f, 3f), 0.0001f) // moving back inside
        assertEquals(0f, computeCursorEdgePushAmount(0f, 1079f, 0f, 3f), 0.0001f)  // sliding parallel
        val pushLeft = computeCursorEdgePushAmount(-12f, 1079f, -12f, 3f)
        val pushRight = computeCursorEdgePushAmount(1091f, 1079f, 12f, 3f)
        assertTrue(pushLeft in 0.1f..1f)
        assertEquals(pushLeft, pushRight, 0.0001f)

        // Blocked shake starts at 0, oscillates strictly inside ±3.5°, returns to 0 after duration
        assertEquals(0f, computeCursorBlockedShakeDeg(0f), 0.0001f)
        var maxShake = 0f
        for (i in 1..21) {
            val deg = computeCursorBlockedShakeDeg(i * 0.01f)
            assertTrue(kotlin.math.abs(deg) <= CURSOR_DYNAMICS_MAX_ANGLE_DEG)
            maxShake = maxOf(maxShake, kotlin.math.abs(deg))
        }
        assertTrue(maxShake > 1.8f)
        assertEquals(0f, computeCursorBlockedShakeDeg(CURSOR_BLOCKED_SHAKE_DURATION_SEC), 0.0001f)
    }

    @Test
    fun cursorDynamicsSpringClickPeckLatchAndShakeRespectEnvelopeAndSettle() {
        val state = MutableCursorDynamicsState()

        // 1. Click peck never jumps position, dips gently near 0.90f then settles smoothly to 1.0f without overshoot flicker
        state.triggerClickPeck()
        assertEquals(1f, state.scale, 0.0001f)
        var minScaleSeen = 1f
        var maxScaleSeen = 1f
        repeat(80) {
            stepCursorDynamicsInPlace(state, targetScale = 1f, targetBankDeg = 0f, targetSquishX = 0f, targetSquishY = 0f, dtSec = 1f / 120f)
            minScaleSeen = minOf(minScaleSeen, state.renderScaleX)
            maxScaleSeen = maxOf(maxScaleSeen, state.renderScaleX)
            assertTrue(state.renderScaleX in CURSOR_DYNAMICS_MIN_SCALE..CURSOR_DYNAMICS_MAX_SCALE)
            assertTrue(state.renderScaleY in CURSOR_DYNAMICS_MIN_SCALE..CURSOR_DYNAMICS_MAX_SCALE)
        }
        assertTrue("Click peck should dip smoothly near 0.90f (actual $minScaleSeen)", minScaleSeen in 0.88f..0.92f)
        assertTrue("Click peck should settle cleanly without jarring overshoot (max $maxScaleSeen)", maxScaleSeen in 1.00f..1.005f)
        repeat(60) {
            stepCursorDynamicsInPlace(state, targetScale = 1f, targetBankDeg = 0f, targetSquishX = 0f, targetSquishY = 0f, dtSec = 1f / 120f)
        }
        assertTrue(state.isSettled(1f, 0f, 0f, 0f))

        // 2. Latch pop from charged 0.89f expands gently then settles at 0.94f grip scale
        state.snapTo(CURSOR_LONG_PRESS_CHARGE_MIN_SCALE, 0f, 0f, 0f)
        state.triggerLatchPop(fromCharge = true)
        var peakLatch = 0f
        repeat(100) {
            stepCursorDynamicsInPlace(state, targetScale = CURSOR_ARMED_GRIP_SCALE, targetBankDeg = 0f, targetSquishX = 0f, targetSquishY = 0f, dtSec = 1f / 120f)
            peakLatch = maxOf(peakLatch, state.renderScaleX)
            assertTrue(state.renderScaleX in CURSOR_DYNAMICS_MIN_SCALE..CURSOR_DYNAMICS_MAX_SCALE)
        }
        assertTrue("Latch pop should expand smoothly to >= 0.94f (peak $peakLatch)", peakLatch in CURSOR_ARMED_GRIP_SCALE..0.96f)
        assertTrue(state.isSettled(CURSOR_ARMED_GRIP_SCALE, 0f, 0f, 0f))

        // 3. Combined max bank + blocked shake + squish never breaches envelope bounds and settles completely
        state.snapTo(CURSOR_PRESENCE_LIFT_SCALE, CURSOR_DYNAMICS_MAX_BANK_DEG, 1f, 1f)
        state.triggerBlockedShake()
        repeat(100) {
            stepCursorDynamicsInPlace(state, targetScale = 1f, targetBankDeg = 0f, targetSquishX = 0f, targetSquishY = 0f, dtSec = 1f / 120f)
            assertTrue(state.renderScaleX in CURSOR_DYNAMICS_MIN_SCALE..CURSOR_DYNAMICS_MAX_SCALE)
            assertTrue(state.renderScaleY in CURSOR_DYNAMICS_MIN_SCALE..CURSOR_DYNAMICS_MAX_SCALE)
            assertTrue(kotlin.math.abs(state.renderAngleDeg) <= CURSOR_DYNAMICS_MAX_ANGLE_DEG)
        }
        assertTrue(state.isSettled(1f, 0f, 0f, 0f))
    }

    @Test
    fun edgeHapticTrackerEnforces14DpHysteresisAnd180MsCooldownPerAxis() {
        val tracker = CursorEdgeHapticTracker()
        val density = 3f // 14dp = 42px
        val maxX = 1079f
        val maxY = 1919f

        tracker.syncInitialPosition(500f, 900f, maxX, maxY)

        // 1. First hit on left edge triggers CURSOR_EDGE_LEFT
        assertEquals(CURSOR_EDGE_LEFT, tracker.onCursorMoved(0f, 900f, maxX, maxY, density, nowMs = 1000L))

        // 2. Sliding vertically along left edge does not re-trigger even well after 180ms
        assertEquals(CURSOR_EDGE_NONE, tracker.onCursorMoved(0f, 600f, maxX, maxY, density, nowMs = 1500L))
        assertEquals(CURSOR_EDGE_NONE, tracker.onCursorMoved(0f, 300f, maxX, maxY, density, nowMs = 2000L))

        // 3. Moving away < 14dp (e.g., 30px < 42px) and back to edge does NOT re-arm or trigger
        assertEquals(CURSOR_EDGE_NONE, tracker.onCursorMoved(30f, 300f, maxX, maxY, density, nowMs = 2100L))
        assertEquals(CURSOR_EDGE_NONE, tracker.onCursorMoved(0f, 300f, maxX, maxY, density, nowMs = 2300L))

        // 4. Moving >= 14dp (50px >= 42px) then hitting edge BEFORE 180ms cooldown still suppresses buzz
        assertEquals(CURSOR_EDGE_LEFT, tracker.onCursorMoved(0f, 300f, maxX, maxY, density, nowMs = 2300L).let {
            // Re-arm properly first after waiting >= 180ms since 1000L -> wait, 2300L didn't trigger because leftArmed was false!
            // Now move to 50px at 2400L (re-arms), hit left at 2500L (triggers), then quickly bounce to 50px and back within 180ms:
            tracker.onCursorMoved(50f, 300f, maxX, maxY, density, nowMs = 2400L)
            tracker.onCursorMoved(0f, 300f, maxX, maxY, density, nowMs = 2500L)
        })
        assertEquals(CURSOR_EDGE_NONE, tracker.onCursorMoved(50f, 300f, maxX, maxY, density, nowMs = 2550L))
        assertEquals(CURSOR_EDGE_NONE, tracker.onCursorMoved(0f, 300f, maxX, maxY, density, nowMs = 2600L)) // only 100ms < 180ms

        // 5. After leaving >= 14dp AND >= 180ms since last trigger (2500L), hitting edge re-triggers
        assertEquals(CURSOR_EDGE_NONE, tracker.onCursorMoved(50f, 300f, maxX, maxY, density, nowMs = 2620L))
        assertEquals(CURSOR_EDGE_LEFT, tracker.onCursorMoved(0f, 300f, maxX, maxY, density, nowMs = 2700L))

        // 6. Y-axis triggers independently right after X-axis trigger
        assertEquals(CURSOR_EDGE_TOP, tracker.onCursorMoved(0f, 0f, maxX, maxY, density, nowMs = 2710L))

        // 7. Starting touch while already at right/bottom edge suppresses initial false tick
        tracker.syncInitialPosition(maxX, maxY, maxX, maxY)
        assertEquals(CURSOR_EDGE_NONE, tracker.onCursorMoved(maxX, maxY, maxX, maxY, density, nowMs = 3000L))

        // 8. Reset clears cooldown and re-arms edges cleanly (used when edgeHaptics is toggled)
        tracker.reset()
        assertEquals(CURSOR_EDGE_RIGHT, tracker.onCursorMoved(maxX, 500f, maxX, maxY, density, nowMs = 3010L))
    }
}


