package io.github.opentouchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonSlotsTest {
    private fun encode(list: List<PadAction>) = list.joinToString(",") { it.id }

    // ── 4 / 8 / 12 / 16 自选键位模式测试 ──

    @Test
    fun defaultLayoutsHaveExpectedKeyCountsAndMovePanel() {
        assertEquals(4, DEFAULT_BUTTONS_4.size)
        assertEquals(8, DEFAULT_BUTTONS_8.size)
        assertEquals(12, DEFAULT_BUTTONS_12.size)
        assertEquals(16, DEFAULT_BUTTONS_16.size)

        for (count in listOf(4, 8, 12, 16)) {
            val defaults = defaultButtonsFor(count)
            assertEquals(count, defaults.size)
            assertTrue("Mode $count must include MOVE_PANEL", defaults.contains(PadAction.MOVE_PANEL))
            assertEquals(PadAction.MOVE_PANEL, defaults[0])
        }
    }

    @Test
    fun buttonLayoutModeEnumCounts() {
        assertEquals(4, ButtonLayoutMode.FOUR.count)
        assertEquals(8, ButtonLayoutMode.EIGHT.count)
        assertEquals(12, ButtonLayoutMode.TWELVE.count)
        assertEquals(16, ButtonLayoutMode.SIXTEEN.count)

        assertEquals(1, ButtonLayoutMode.FOUR.edgeCount)
        assertEquals(2, ButtonLayoutMode.EIGHT.edgeCount)
        assertEquals(3, ButtonLayoutMode.TWELVE.edgeCount)
        assertEquals(4, ButtonLayoutMode.SIXTEEN.edgeCount)

        assertEquals(ButtonLayoutMode.FOUR, ButtonLayoutMode.fromCount(4))
        assertEquals(ButtonLayoutMode.EIGHT, ButtonLayoutMode.fromCount(8))
        assertEquals(ButtonLayoutMode.TWELVE, ButtonLayoutMode.fromCount(12))
        assertEquals(ButtonLayoutMode.SIXTEEN, ButtonLayoutMode.fromCount(16))
        assertEquals(ButtonLayoutMode.SIXTEEN, ButtonLayoutMode.fromCount(999))
    }

    @Test
    fun decodeButtonSlotsReturnsCorrectSizeForEveryMode() {
        for (count in listOf(4, 8, 12, 16)) {
            val defaults = defaultButtonsFor(count)
            assertEquals(defaults, decodeButtonSlots(null, count))
            assertEquals(defaults, decodeButtonSlots("", count))

            val encoded = encode(defaults)
            assertEquals(defaults, decodeButtonSlots(encoded, count))
        }
    }

    @Test
    fun invalidButtonIdKeepsSlotWithoutShifting() {
        for (count in listOf(4, 8, 12, 16)) {
            val decoded = decodeButtonSlots("movepanel,invalid_action,back", count)
            assertEquals(count, decoded.size)
            assertEquals(PadAction.MOVE_PANEL, decoded[0])
            assertEquals(PadAction.NONE, decoded[1])
            assertEquals(PadAction.BACK, decoded[2])
        }
    }

    @Test
    fun storedLayoutWithoutMoveKeyRestoresItAtFirstSlot() {
        for (count in listOf(4, 8, 12, 16)) {
            val noMove = List(count) { PadAction.BACK }
            val decoded = decodeButtonSlots(encode(noMove), count)
            assertEquals(count, decoded.size)
            assertEquals(PadAction.MOVE_PANEL, decoded[0])

            val ensured = ensureMoveKey(List(count) { PadAction.NONE }, count)
            assertEquals(count, ensured.size)
            assertEquals(PadAction.MOVE_PANEL, ensured[0])
        }
    }

    // ── 移动键保护 ──

    @Test
    fun lastMoveKeyCannotBeReplacedInAnyMode() {
        for (count in listOf(4, 8, 12, 16)) {
            val current = defaultButtonsFor(count)
            // 默认情况下只有 slot 0 是 MOVE_PANEL
            assertEquals(1, current.count { it == PadAction.MOVE_PANEL })

            for (other in listOf(PadAction.BACK, PadAction.HOME, PadAction.NONE)) {
                assertNull("Mode $count: replacing sole MOVE_PANEL with $other should be rejected",
                    replaceSlot(current, 0, other, count))
            }
            // 换成它自己不算移除
            assertEquals(current, replaceSlot(current, 0, PadAction.MOVE_PANEL, count))
        }
    }

    @Test
    fun moveKeyCanBeReplacedOnceAnotherExists() {
        for (count in listOf(4, 8, 12, 16)) {
            val current = defaultButtonsFor(count)
            // 先把 slot 1 改成 MOVE_PANEL
            val twoMoves = replaceSlot(current, 1, PadAction.MOVE_PANEL, count)!!
            assertEquals(2, twoMoves.count { it == PadAction.MOVE_PANEL })

            // 现在可以安全地把 slot 0 改成别的按键
            val oneMove = replaceSlot(twoMoves, 0, PadAction.HOME, count)!!
            assertEquals(PadAction.HOME, oneMove[0])
            assertEquals(PadAction.MOVE_PANEL, oneMove[1])
            assertEquals(1, oneMove.count { it == PadAction.MOVE_PANEL })

            // 此时 slot 1 成为唯一的移动键，不能再被替换
            assertNull(replaceSlot(oneMove, 1, PadAction.BACK, count))
        }
    }

    @Test
    fun replaceSlotRejectsOutOfRangeAndPreservesCount() {
        for (count in listOf(4, 8, 12, 16)) {
            val current = defaultButtonsFor(count)
            assertNull(replaceSlot(current, -1, PadAction.BACK, count))
            assertNull(replaceSlot(current, count, PadAction.BACK, count))
            assertNull(replaceSlot(current, count + 5, PadAction.BACK, count))

            val updated = replaceSlot(current, count - 1, PadAction.SCREENSHOT, count)!!
            assertEquals(count, updated.size)
            assertEquals(PadAction.SCREENSHOT, updated[count - 1])
        }
    }

    @Test
    fun everyActionHasItsOwnLineIcon() {
        val icons = PadAction.ALL.map { it.iconRes }
        assertTrue(icons.all { it != 0 })
        assertEquals(icons.size, icons.toSet().size)
    }

    @Test
    fun ballActionsExcludeTouchpadSpecificAndPanelManipulations() {
        val touchpadOnly = listOf(
            PadAction.CLICK,
            PadAction.LONG_PRESS,
            PadAction.DRAG_LOCK,
            PadAction.SCROLL_UP,
            PadAction.SCROLL_DOWN,
            PadAction.SCROLL_LEFT,
            PadAction.SCROLL_RIGHT,
            PadAction.SWIPE_UP,
            PadAction.SWIPE_DOWN,
            PadAction.SWIPE_LEFT,
            PadAction.SWIPE_RIGHT,
            PadAction.MOVE_PANEL,
            PadAction.RESIZE_PANEL,
            PadAction.MOVE_BALL,
        )
        for (action in touchpadOnly) {
            assertFalse("Action $action should not be in BALL_ACTIONS", PadAction.BALL_ACTIONS.contains(action))
        }
        assertTrue(PadAction.BALL_ACTIONS.contains(PadAction.BACK))
        assertTrue(PadAction.BALL_ACTIONS.contains(PadAction.HOME))
        assertTrue(PadAction.BALL_ACTIONS.contains(PadAction.RECENTS))
        assertTrue(PadAction.BALL_ACTIONS.contains(PadAction.MINIMIZE))
        assertTrue(PadAction.BALL_ACTIONS.contains(PadAction.NOTIFICATIONS))
        assertTrue(PadAction.BALL_ACTIONS.contains(PadAction.NONE))
    }

    @Test
    fun ballLongPressActionsIncludeMoveBallAtTopAndExcludeTouchpadActions() {
        assertEquals(PadAction.MOVE_BALL, PadAction.BALL_LONG_PRESS_ACTIONS.first())
        assertFalse(PadAction.BALL_LONG_PRESS_ACTIONS.contains(PadAction.CLICK))
        assertFalse(PadAction.BALL_LONG_PRESS_ACTIONS.contains(PadAction.MOVE_PANEL))
        assertFalse(PadAction.BALL_LONG_PRESS_ACTIONS.contains(PadAction.RESIZE_PANEL))
        assertTrue(PadAction.BALL_LONG_PRESS_ACTIONS.contains(PadAction.BACK))
    }

    @Test
    fun touchpadActionsExcludeMoveBall() {
        assertFalse(PadAction.TOUCHPAD_ACTIONS.contains(PadAction.MOVE_BALL))
        assertTrue(PadAction.TOUCHPAD_ACTIONS.contains(PadAction.CLICK))
        assertTrue(PadAction.TOUCHPAD_ACTIONS.contains(PadAction.MOVE_PANEL))
        assertTrue(PadAction.TOUCHPAD_ACTIONS.contains(PadAction.RESIZE_PANEL))
        assertTrue(PadAction.TOUCHPAD_ACTIONS.contains(PadAction.BACK))
    }
}
