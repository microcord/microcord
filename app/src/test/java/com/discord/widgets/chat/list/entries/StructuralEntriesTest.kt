package com.discord.widgets.chat.list.entries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StructuralEntriesTest {
    @Test
    fun markerEntriesKeepReferenceIdentitySemantics() {
        listOf(
            LoadingEntry() to LoadingEntry(),
            DividerEntry() to DividerEntry(),
            MentionFooterEntry() to MentionFooterEntry(),
            SearchIndexingEntry() to SearchIndexingEntry(),
            SearchEmptyEntry() to SearchEmptyEntry(),
            SearchErrorEntry() to SearchErrorEntry(),
        ).forEach { (first, second) ->
            assertNotEquals(first, second)
            assertEquals(first.type.toString(), first.key)
        }
    }

    @Test
    fun spacerRetainsValueSemanticsAndReferenceKey() {
        assertEquals(SpacerEntry(42), SpacerEntry(42))
        assertEquals("742", SpacerEntry(42).key)
    }
}
