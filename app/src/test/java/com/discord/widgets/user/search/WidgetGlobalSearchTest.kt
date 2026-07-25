package com.discord.widgets.user.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetGlobalSearchTest {
    @Test fun matchingIsCaseInsensitiveAndReportsHighlightRange() {
        assertEquals(WidgetGlobalSearchModel.MatchedResult("General", 2, 3), match("General", "ner"))
        assertNull(match("General", "voice"))
    }
}
