package io.github.opentouchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonSlotsTest {
    private fun encode(list: List<PadAction>) = list.joinToString(",") { it.id }

    // ── 18 个位置的布局 ──

    @Test
    fun defaultLayoutHasEighteenSlotsWithMoveAndResizeInTheirOldPlaces() {
        assertEquals(18, BUTTON_SLOT_COUNT)
        assertEquals(BUTTON_SLOT_COUNT, PadAction.DEFAULT.size)
        assertEquals(PadAction.MOVE_PANEL, PadAction.DEFAULT[MOVE_GRIP_SLOT])
        assertEquals(PadAction.RESIZE_PANEL, PadAction.DEFAULT[RESIZE_GRIP_SLOT])
        // v0.5.3 及以前：移动键在左列最上面，缩放键在右列最下面
        assertEquals(0 to 1, BUTTON_SLOT_CELLS[MOVE_GRIP_SLOT])
        assertEquals(5 to 3, BUTTON_SLOT_CELLS[RESIZE_GRIP_SLOT])
        assertEquals(1, PadAction.DEFAULT.count { it == PadAction.MOVE_PANEL })
    }

    @Test
    fun slotCellsCoverTheOuterRingOfTheGridExactlyOnce() {
        assertEquals(BUTTON_SLOT_COUNT, BUTTON_SLOT_CELLS.size)
        assertEquals(BUTTON_SLOT_COUNT, BUTTON_SLOT_CELLS.toSet().size)
        val ring = (0..5).flatMap { c -> (0..4).map { r -> c to r } }
            .filter { (c, r) -> c == 0 || c == 5 || r == 0 || r == 4 }
        assertEquals(ring.toSet(), BUTTON_SLOT_CELLS.toSet())
    }

    @Test
    fun slotCellsMatchComputeControlLayout() {
        for ((density, gap) in listOf(1f to 0, 1f to 3, 3f to 9)) {
            val width = (168 * density).toInt()
            val layout = computeControlLayout(width, controlGridHeight(width, density, gap), density, gap)
            assertEquals(BUTTON_SLOT_COUNT, layout.slots.size)
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

    // ── 老配置自动补齐 ──

    @Test
    fun legacyTwelveSlotLayoutGetsDefaultCornersAndGripKeys() {
        val decoded = decodeButtonSlots(encode(PadAction.DEFAULT.take(12)))
        assertEquals(BUTTON_SLOT_COUNT, decoded.size)
        assertEquals(PadAction.DEFAULT, decoded)
    }

    @Test
    fun legacySixteenSlotLayoutGetsMoveAndResizeKeysAndKeepsCustomButtons() {
        val custom = PadAction.DEFAULT.take(16).toMutableList().also {
            it[0] = PadAction.HOME
            it[15] = PadAction.NONE
        }
        val decoded = decodeButtonSlots(encode(custom))
        assertEquals(BUTTON_SLOT_COUNT, decoded.size)
        assertEquals(custom, decoded.take(16))
        assertEquals(PadAction.MOVE_PANEL, decoded[MOVE_GRIP_SLOT])
        assertEquals(PadAction.RESIZE_PANEL, decoded[RESIZE_GRIP_SLOT])
    }

    @Test
    fun invalidButtonIdKeepsItsSlotInsteadOfShiftingFollowingButtons() {
        val decoded = decodeButtonSlots("click,removed-action,home")
        assertEquals(BUTTON_SLOT_COUNT, decoded.size)
        assertEquals(listOf(PadAction.CLICK, PadAction.NONE, PadAction.HOME), decoded.take(3))
        assertEquals(PadAction.MOVE_PANEL, decoded[MOVE_GRIP_SLOT])
    }

    @Test
    fun completelyInvalidStoredLayoutFallsBackToDefaults() {
        assertEquals(PadAction.DEFAULT, decodeButtonSlots("removed-action,also-removed"))
        assertEquals(PadAction.DEFAULT, decodeButtonSlots(null))
    }

    @Test
    fun eighteenSlotLayoutRoundTrips() {
        val custom = PadAction.DEFAULT.toMutableList().also {
            it[MOVE_GRIP_SLOT] = PadAction.HOME
            it[0] = PadAction.MOVE_PANEL
            it[RESIZE_GRIP_SLOT] = PadAction.NONE
        }
        assertEquals(custom, decodeButtonSlots(encode(custom)))
    }

    @Test
    fun storedLayoutWithoutAnyMoveKeyGetsItBack() {
        val stored = PadAction.DEFAULT.toMutableList().also { it[MOVE_GRIP_SLOT] = PadAction.HOME }
        assertEquals(PadAction.MOVE_PANEL, decodeButtonSlots(encode(stored))[MOVE_GRIP_SLOT])
        assertEquals(PadAction.MOVE_PANEL, ensureMoveKey(List(BUTTON_SLOT_COUNT) { PadAction.NONE })[MOVE_GRIP_SLOT])
        assertEquals(PadAction.DEFAULT, ensureMoveKey(PadAction.DEFAULT))
    }

    // ── 移动键保护 ──

    @Test
    fun lastMoveKeyCannotBeReplaced() {
        for (other in PadAction.ALL.filter { it != PadAction.MOVE_PANEL }) {
            assertNull("$other", replaceSlot(PadAction.DEFAULT, MOVE_GRIP_SLOT, other))
        }
        // 换成它自己不算移除
        assertEquals(PadAction.DEFAULT, replaceSlot(PadAction.DEFAULT, MOVE_GRIP_SLOT, PadAction.MOVE_PANEL))
    }

    @Test
    fun moveKeyCanBeReplacedOnceAnotherExists() {
        val two = replaceSlot(PadAction.DEFAULT, 0, PadAction.MOVE_PANEL)!!
        val one = replaceSlot(two, MOVE_GRIP_SLOT, PadAction.HOME)!!
        assertEquals(PadAction.HOME, one[MOVE_GRIP_SLOT])
        assertEquals(1, one.count { it == PadAction.MOVE_PANEL })
        // 现在 0 号是最后一个移动键
        assertNull(replaceSlot(one, 0, PadAction.CLICK))
    }

    @Test
    fun resizeKeyIsNotProtected() {
        val noResize = replaceSlot(PadAction.DEFAULT, RESIZE_GRIP_SLOT, PadAction.BACK)!!
        assertFalse(PadAction.RESIZE_PANEL in noResize)
        assertEquals(PadAction.BACK, noResize[RESIZE_GRIP_SLOT])
    }

    @Test
    fun moveAndResizeCanBeAssignedToAnyPosition() {
        for (slot in 0 until BUTTON_SLOT_COUNT) {
            assertEquals(PadAction.MOVE_PANEL, replaceSlot(PadAction.DEFAULT, slot, PadAction.MOVE_PANEL)!![slot])
            if (slot != MOVE_GRIP_SLOT) {
                assertEquals(PadAction.RESIZE_PANEL, replaceSlot(PadAction.DEFAULT, slot, PadAction.RESIZE_PANEL)!![slot])
            }
        }
        assertTrue(PadAction.MOVE_PANEL in PadAction.ALL)
        assertTrue(PadAction.RESIZE_PANEL in PadAction.ALL)
    }

    @Test
    fun replaceSlotRejectsOutOfRangeAndAlwaysReturnsEighteen() {
        assertNull(replaceSlot(PadAction.DEFAULT, -1, PadAction.BACK))
        assertNull(replaceSlot(PadAction.DEFAULT, BUTTON_SLOT_COUNT, PadAction.BACK))
        assertEquals(BUTTON_SLOT_COUNT, replaceSlot(PadAction.DEFAULT, 0, PadAction.BACK)!!.size)
        // 短列表也会补齐到 18 个
        val short = listOf(PadAction.MOVE_PANEL)
        assertEquals(BUTTON_SLOT_COUNT, replaceSlot(short, 5, PadAction.HOME)!!.size)
    }

    @Test
    fun everyActionHasItsOwnLineIcon() {
        val icons = PadAction.ALL.map { it.iconRes }
        assertTrue(icons.all { it != 0 })
        assertEquals(icons.size, icons.toSet().size)
    }
}
