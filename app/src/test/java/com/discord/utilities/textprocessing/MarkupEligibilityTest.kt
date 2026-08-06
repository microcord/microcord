package com.discord.utilities.textprocessing

import com.discord.utilities.SnowflakeUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupEligibilityTest {

    private val epochMillis = SnowflakeUtils.toTimestamp(1088216706570268682L)

    @Test
    fun theEpochIsTheMessageThatShippedImprovedMarkdown() {
        assertTrue(MarkupEligibility.isNewerThanImprovedMarkdownEpoch(epochMillis))
        assertFalse(MarkupEligibility.isNewerThanImprovedMarkdownEpoch(epochMillis - 1))
    }

    /** A message with no timestamp at all must not be treated as newer than the epoch. */
    @Test
    fun anUnknownTimestampCountsAsLegacy() {
        assertFalse(MarkupEligibility.isNewerThanImprovedMarkdownEpoch(0L))
    }
}
