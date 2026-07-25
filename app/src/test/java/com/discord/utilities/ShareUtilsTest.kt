package com.discord.utilities

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareUtilsTest {
    @Test
    fun composesSubjectAndTextWithoutDuplicates() {
        assertEquals("Subject\nBody", ShareUtils.composeMessage("Subject", "Body"))
        assertEquals("Body", ShareUtils.composeMessage(null, "Body"))
        assertEquals("Same", ShareUtils.composeMessage("Same", "Same"))
    }

    @Test
    fun shortcutPlanEnforcesMaxDeduplicatesAndRemovesOnlyStaleConversationIds() {
        val candidates = listOf(
            ShareUtils.ConversationShortcut(2, "two"),
            ShareUtils.ConversationShortcut(2, "duplicate"),
            ShareUtils.ConversationShortcut(3, "three"),
            ShareUtils.ConversationShortcut(4, "four"),
        )
        val plan = ShareUtils.planShortcuts(candidates, 2, listOf("1", "2", "settings"))

        assertEquals(listOf(2L, 3L), plan.shortcuts.map { it.channelId })
        assertEquals(listOf("1"), plan.staleIds)
    }
}
