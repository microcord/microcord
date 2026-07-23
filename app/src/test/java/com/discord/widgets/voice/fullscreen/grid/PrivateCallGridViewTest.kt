package com.discord.widgets.voice.fullscreen.grid

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateCallGridViewTest {
    @Test
    fun preservesReferenceGridLimitsAndOverflowSlot() {
        assertEquals(3, PrivateCallGridView.columnCount(landscape = false))
        assertEquals(6, PrivateCallGridView.columnCount(landscape = true))
        assertEquals(9, PrivateCallGridView.maxShownParticipants(landscape = false))
        assertEquals(6, PrivateCallGridView.maxShownParticipants(landscape = true))
        assertEquals(8, PrivateCallGridView.visibleParticipantCount(total = 10, landscape = false))
        assertEquals(5, PrivateCallGridView.visibleParticipantCount(total = 7, landscape = true))
    }
}
