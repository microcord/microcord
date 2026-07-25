package com.discord.widgets.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetIncomingShareTest {
    private val candidates = listOf(
        WidgetIncomingShare.Candidate(1, "general", "Guild"),
        WidgetIncomingShare.Candidate(2, "Alice"),
    )

    @Test
    fun filtersTitleOrGuildCaseInsensitively() {
        assertEquals(listOf(1L), WidgetIncomingShare.filterCandidates(candidates, "guild").map { it.channelId })
        assertEquals(listOf(2L), WidgetIncomingShare.filterCandidates(candidates, "ALI").map { it.channelId })
        assertEquals(candidates, WidgetIncomingShare.filterCandidates(candidates, " "))
    }

    @Test
    fun directShareIdPreselectsOnlyAnAvailableCandidate() {
        assertEquals(2L, WidgetIncomingShare.preselectedCandidate(candidates, 2)?.channelId)
        assertNull(WidgetIncomingShare.preselectedCandidate(candidates, 99))
        assertNull(WidgetIncomingShare.preselectedCandidate(candidates, null))
    }
}
