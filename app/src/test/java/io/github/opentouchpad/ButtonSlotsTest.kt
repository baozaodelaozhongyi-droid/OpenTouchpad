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
    fun completelyInvalidStoredLayoutFallsBackToDefaults() {
        assertEquals(PadAction.DEFAULT, decodeButtonSlots("removed-action,also-removed"))
    }
}
