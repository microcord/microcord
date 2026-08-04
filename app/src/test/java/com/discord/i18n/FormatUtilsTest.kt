package com.discord.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {
    @Test
    fun `substitutes a named placeholder`() {
        assertEquals("42 Members", FormatUtils.format("{members} Members", "members" to 42))
    }

    @Test
    fun `substitutes every occurrence of the same placeholder`() {
        assertEquals(
            "3 of 3",
            FormatUtils.format("{count} of {count}", "count" to 3),
        )
    }

    @Test
    fun `substitutes several distinct placeholders`() {
        assertEquals(
            "720p at 60fps",
            FormatUtils.format(
                "{resolution} at {fps}fps",
                "resolution" to "720p",
                "fps" to 60,
            ),
        )
    }

    @Test
    fun `leaves an unknown placeholder untouched`() {
        assertEquals(
            "5 of {total}",
            FormatUtils.format("{count} of {total}", "count" to 5),
        )
    }

    @Test
    fun `leaves a token whose argument is null untouched`() {
        assertEquals("{members} Members", FormatUtils.format("{members} Members", "members" to null))
    }

    @Test
    fun `leaves the template untouched when no arguments are supplied`() {
        assertEquals("{members} Members", FormatUtils.format("{members} Members", emptyMap()))
    }

    @Test
    fun `ignores brace groups containing whitespace`() {
        assertEquals("a {not a token} b", FormatUtils.format("a {not a token} b", "x" to 1))
    }

    @Test
    fun `removes hook markers around a substituted value`() {
        assertEquals(
            "Watch Nelly's stream",
            FormatUtils.format("Watch !!{streamerName}!!'s stream", "streamerName" to "Nelly"),
        )
    }

    @Test
    fun `removes hook markers when no arguments are supplied`() {
        assertEquals("Nelly", FormatUtils.format("!!Nelly!!", emptyMap()))
    }

    @Test
    fun `unwraps a hook component to its label`() {
        assertEquals(
            "by Eminem",
            FormatUtils.format("by $[!!{artists}!!](artistsHook)", "artists" to "Eminem"),
        )
    }

    @Test
    fun `drops a hook component that renders no label`() {
        assertEquals("Jump", FormatUtils.format("$[](userHook)Jump", emptyMap()))
    }

    @Test
    fun `leaves an ordinary masked link alone`() {
        assertEquals(
            "see [our status page](https://status.discord.com)",
            FormatUtils.format("see [our status page](https://status.discord.com)", emptyMap()),
        )
    }

    @Test
    fun `supports reference ordered arguments when named arguments are absent`() {
        assertEquals("3 of 5", FormatUtils.formatOrdered("{count} of {total}", arrayOf(3, 5)))
    }
}
