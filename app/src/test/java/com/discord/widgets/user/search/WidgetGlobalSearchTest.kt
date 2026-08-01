package com.discord.widgets.user.search

import com.discord.R
import com.discord.widgets.user.search.WidgetGlobalSearchModel.Companion.toFuzzyMatchedResult
import com.discord.widgets.user.search.WidgetGlobalSearchModel.Companion.toMatchedResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetGlobalSearchTest {

    @Test
    fun filterPickerMatchesReferenceOptionsAndPreservesQuery() {
        assertEquals(
            listOf(
                R.string.users to '@',
                R.string.text_channels to '#',
                R.string.voice_channels to '!',
                R.string.servers to '*',
            ),
            WidgetGlobalSearch.FILTER_OPTIONS,
        )
        assertEquals("#general", WidgetGlobalSearch.applySearchPrefix("@general", '#'))
        assertEquals("*discord", WidgetGlobalSearch.applySearchPrefix("discord", '*'))
        assertEquals("!", WidgetGlobalSearch.applySearchPrefix("", '!'))
    }

    @Test
    fun literalMatchingIsCaseInsensitiveAndReportsHighlightRange() {
        assertEquals(
            WidgetGlobalSearchModel.MatchedResult("General", 2, 3),
            "General".toMatchedResult("ner"),
        )
        assertEquals(
            WidgetGlobalSearchModel.MatchedResult("General", 0, 3),
            "General".toMatchedResult("GEN"),
        )
        assertNull("General".toMatchedResult("voice"))
    }

    /** A fuzzy hit reports -1 so the scorer can bury it under every literal hit. */
    @Test
    fun fuzzyMatchingReportsNoHighlight() {
        val fuzzy = "voice-chat".toFuzzyMatchedResult("vc")
        assertNotNull(fuzzy)
        assertEquals(-1, fuzzy!!.firstMatchIndex)
        assertNull("voice-chat".toFuzzyMatchedResult("zzz"))
    }

    @Test
    fun guildMentionCountSumsChannelCounts() {
        assertEquals(
            5,
            WidgetGlobalSearchGuildsModel.mentionCount(
                channelIds = listOf(10L, 20L, 30L),
                mentionCounts = mapOf(10L to 2, 20L to 3, 99L to 100),
            ),
        )
        assertEquals(0, WidgetGlobalSearchGuildsModel.mentionCount(null, mapOf(10L to 2)))
    }
}
