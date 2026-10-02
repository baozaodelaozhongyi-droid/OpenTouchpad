package io.github.opentouchpad

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonSlotsTest {
    @Test
    fun invalidButtonIdKeepsItsSlotInsteadOfShiftingFollowingButtons() {
        assertEquals(
            listOf(PadAction.CLICK, PadAction.NONE, PadAction.HOME),
            decodeButtonSlots("click,removed-action,home"),
        )
    }

    @Test
    fun legacyTwelveSlotLayoutGetsDefaultCornerButtons() {
        val legacy = PadAction.DEFAULT.take(12).joinToString(",") { it.id }
        val decoded = decodeButtonSlots(legacy)
        assertEquals(BUTTON_SLOT_COUNT, decoded.size)
        assertEquals(PadAction.DEFAULT.drop(12), decoded.drop(12))
    }

    @Test
    fun completelyInvalidStoredLayoutFallsBackToDefaults() {
        assertEquals(PadAction.DEFAULT, decodeButtonSlots("removed-action,also-removed"))
    }
}
