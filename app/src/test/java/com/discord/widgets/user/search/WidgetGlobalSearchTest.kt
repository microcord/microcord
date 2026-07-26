package com.discord.widgets.user.search

import com.discord.widgets.user.search.WidgetGlobalSearchModel.Companion.toFuzzyMatchedResult
import com.discord.widgets.user.search.WidgetGlobalSearchModel.Companion.toMatchedResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetGlobalSearchTest {

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
}
