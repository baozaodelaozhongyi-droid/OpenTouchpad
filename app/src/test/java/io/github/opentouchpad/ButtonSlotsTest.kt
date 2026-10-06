package io.github.opentouchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonSlotsTest {
    private fun encode(list: List<PadAction>) = list.joinToString(",") { it.id }

    // ── 4 / 8 / 12 / 16 / 18 自选键位模式测试 ──

    @Test
    fun defaultLayoutsHaveExpectedKeyCountsAndMovePanel() {
        assertEquals(4, DEFAULT_BUTTONS_4.size)
        assertEquals(8, DEFAULT_BUTTONS_8.size)
        assertEquals(12, DEFAULT_BUTTONS_12.size)
        assertEquals(16, DEFAULT_BUTTONS_16.size)
        assertEquals(18, DEFAULT_BUTTONS_18.size)

        for (count in listOf(4, 8, 12, 16, 18)) {
            val defaults = defaultButtonsFor(count)
            assertEquals(count, defaults.size)
            assertTrue("Mode $count must include MOVE_PANEL", defaults.contains(PadAction.MOVE_PANEL))
            val expectedMoveSlot = if (count == 18) 16 else 0
            assertEquals(PadAction.MOVE_PANEL, defaults[expectedMoveSlot])
        }
    }

    @Test
    fun buttonLayoutModeEnumCounts() {
        assertEquals(4, ButtonLayoutMode.FOUR.count)
        assertEquals(8, ButtonLayoutMode.EIGHT.count)
        assertEquals(12, ButtonLayoutMode.TWELVE.count)
        assertEquals(16, ButtonLayoutMode.SIXTEEN.count)
        assertEquals(18, ButtonLayoutMode.EIGHTEEN.count)

        assertEquals(1, ButtonLayoutMode.FOUR.edgeCount)
        assertEquals(2, ButtonLayoutMode.EIGHT.edgeCount)
        assertEquals(3, ButtonLayoutMode.TWELVE.edgeCount)
        assertEquals(4, ButtonLayoutMode.SIXTEEN.edgeCount)
        assertEquals(4, ButtonLayoutMode.EIGHTEEN.edgeCount)

        assertEquals(ButtonLayoutMode.FOUR, ButtonLayoutMode.fromCount(4))
        assertEquals(ButtonLayoutMode.EIGHT, ButtonLayoutMode.fromCount(8))
        assertEquals(ButtonLayoutMode.TWELVE, ButtonLayoutMode.fromCount(12))
        assertEquals(ButtonLayoutMode.SIXTEEN, ButtonLayoutMode.fromCount(16))
        assertEquals(ButtonLayoutMode.EIGHTEEN, ButtonLayoutMode.fromCount(18))
        assertEquals(ButtonLayoutMode.SIXTEEN, ButtonLayoutMode.fromCount(999))
    }

    @Test
    fun slotCellsCoverTheOuterRingOfTheGridExactlyOnce() {
        assertEquals(18, BUTTON_SLOT_CELLS.size)
        assertEquals(18, BUTTON_SLOT_CELLS.toSet().size)
        val ring = (0..5).flatMap { c -> (0..4).map { r -> c to r } }
            .filter { (c, r) -> c == 0 || c == 5 || r == 0 || r == 4 }
        assertEquals(ring.toSet(), BUTTON_SLOT_CELLS.toSet())
    }

    @Test
    fun slotCellsMatchComputeControlLayoutForEighteen() {
        for ((density, gap) in listOf(1f to 0, 1f to 3, 3f to 9)) {
            val width = (168 * density).toInt()
            val layout = computeControlLayout(width, controlGridHeight(width, density, gap, 18), density, gap, 18)
            assertEquals(18, layout.slots.size)
            val step = layout.button + gap
            val origin = layout.slots[12] // 左上角 = 网格 (0, 0)
            BUTTON_SLOT_CELLS.forEachIndexed { i, (col, row) ->
                assertEquals("slot $i x", origin.x + col * step, layout.slots[i].x)
                assertEquals("slot $i y", origin.y + row * step, layout.slots[i].y)
            }
            // 触控板占中间 4 × 3
            assertEquals(origin.x + step, layout.pad.x)
            assertEquals(origin.y + step, layout.pad.y)
        }
    }

    @Test
    fun decodeButtonSlotsReturnsCorrectSizeForEveryMode() {
        for (count in listOf(4, 8, 12, 16, 18)) {
            val defaults = defaultButtonsFor(count)
            assertEquals(defaults, decodeButtonSlots(null, count))
            assertEquals(defaults, decodeButtonSlots("", count))

            val encoded = encode(defaults)
            assertEquals(defaults, decodeButtonSlots(encoded, count))
        }
    }

    @Test
    fun legacyEighteenLayoutPadsCorrectly() {
        val legacy12 = DEFAULT_BUTTONS_18.take(12).joinToString(",") { it.id }
        val decoded12 = decodeButtonSlots(legacy12, 18)
        assertEquals(18, decoded12.size)
        assertEquals(DEFAULT_BUTTONS_18, decoded12)

        val legacy16 = DEFAULT_BUTTONS_18.take(16).joinToString(",") { it.id }
        val decoded16 = decodeButtonSlots(legacy16, 18)
        assertEquals(18, decoded16.size)
        assertEquals(DEFAULT_BUTTONS_18, decoded16)
    }

    @Test
    fun invalidButtonIdKeepsSlotWithoutShifting() {
        for (count in listOf(4, 8, 12, 16, 18)) {
            val decoded = decodeButtonSlots("movepanel,invalid_action,back", count)
            assertEquals(count, decoded.size)
            val expectedMoveSlot = if (count == 18) 16 else 0
            assertEquals(PadAction.MOVE_PANEL, decoded[expectedMoveSlot])
            assertEquals(PadAction.NONE, decoded[1])
            assertEquals(PadAction.BACK, decoded[2])
        }
    }

    @Test
    fun storedLayoutWithoutMoveKeyRestoresIt() {
        for (count in listOf(4, 8, 12, 16, 18)) {
            val noMove = List(count) { PadAction.BACK }
            val decoded = decodeButtonSlots(encode(noMove), count)
            assertEquals(count, decoded.size)
            val expectedMoveSlot = if (count == 18) 16 else 0
            assertEquals(PadAction.MOVE_PANEL, decoded[expectedMoveSlot])

            val ensured = ensureMoveKey(List(count) { PadAction.NONE }, count)
            assertEquals(count, ensured.size)
            assertEquals(PadAction.MOVE_PANEL, ensured[expectedMoveSlot])
        }
    }

    // ── 移动键保护 ──

    @Test
    fun lastMoveKeyCannotBeReplacedInAnyMode() {
        for (count in listOf(4, 8, 12, 16, 18)) {
            val current = defaultButtonsFor(count)
            val moveSlot = if (count == 18) 16 else 0
            assertEquals(1, current.count { it == PadAction.MOVE_PANEL })

            for (other in listOf(PadAction.BACK, PadAction.HOME, PadAction.NONE)) {
                assertNull("Mode $count: replacing sole MOVE_PANEL with $other should be rejected",
                    replaceSlot(current, moveSlot, other, count))
            }
            // 换成它自己不算移除
            assertEquals(current, replaceSlot(current, moveSlot, PadAction.MOVE_PANEL, count))
        }
    }

    @Test
    fun moveKeyCanBeReplacedOnceAnotherExists() {
        for (count in listOf(4, 8, 12, 16, 18)) {
            val current = defaultButtonsFor(count)
            val originalMoveSlot = if (count == 18) 16 else 0
            val targetNewSlot = if (count == 18) 0 else 1

            val twoMoves = replaceSlot(current, targetNewSlot, PadAction.MOVE_PANEL, count)!!
            assertEquals(2, twoMoves.count { it == PadAction.MOVE_PANEL })

            val oneMove = replaceSlot(twoMoves, originalMoveSlot, PadAction.HOME, count)!!
            assertEquals(PadAction.HOME, oneMove[originalMoveSlot])
            assertEquals(PadAction.MOVE_PANEL, oneMove[targetNewSlot])
            assertEquals(1, oneMove.count { it == PadAction.MOVE_PANEL })

            assertNull(replaceSlot(oneMove, targetNewSlot, PadAction.BACK, count))
        }
    }

    @Test
    fun replaceSlotRejectsOutOfRangeAndPreservesCount() {
        for (count in listOf(4, 8, 12, 16, 18)) {
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
