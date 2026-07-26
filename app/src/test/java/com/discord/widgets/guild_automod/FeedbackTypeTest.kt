package com.discord.widgets.guild_automod

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackTypeTest {

    /**
     * [FeedbackType.value] is an analytics wire value and really is the uppercase constant name.
     * Declaration order is the reference's (`BUG` first), taken from the synthetic `$VALUES` array
     * rather than the alphabetised decompiler output.
     */
    @Test
    fun `feedback types keep their reference order and wire values`() {
        assertEquals(
            listOf("BUG" to "BUG", "ALLOWED" to "ALLOWED"),
            FeedbackType.values().map { it.name to it.value },
        )
    }
}
