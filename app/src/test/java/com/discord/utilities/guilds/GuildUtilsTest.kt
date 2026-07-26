package com.discord.utilities.guilds

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `computeShortName` is three regex passes, not a tokeniser, and its edge behaviour is visible in
 * the UI — these pin what it actually does rather than what it looks like it does.
 */
class GuildUtilsTest {

    @Test
    fun `initials come from the first letter of each word`() {
        assertEquals("DA", computeShortName("Discord Android"))
    }

    @Test
    fun `case is preserved, not upper-cased`() {
        assertEquals("Tqbfjotld", computeShortName("The quick brown fox jumps over the lazy dog"))
        assertEquals("hw", computeShortName("  hello   world  "))
    }

    @Test
    fun `the possessive is dropped so it does not double the owner initial`() {
        assertEquals("MS", computeShortName("Mantik's Server"))
        assertEquals("WH", computeShortName("Wumpus's Hangout"))
    }

    @Test
    fun `the possessive pattern requires a trailing space, so a final one survives`() {
        assertEquals("W's", computeShortName("Wumpus's"))
    }

    @Test
    fun `a single word yields a single letter`() {
        assertEquals("D", computeShortName("Discord"))
    }

    @Test
    fun `only whitespace is stripped, so other separators survive between initials`() {
        assertEquals("M—G", computeShortName("My — Guild"))
    }

    @Test
    fun `a name with no word characters is returned as-is, not emptied`() {
        // Nothing matches `(\w)(\w*)`, so the punctuation passes straight through.
        assertEquals("!!!", computeShortName("!!!"))
        assertEquals("", computeShortName(""))
    }

    @Test
    fun `digits count as word characters`() {
        assertEquals("2C", computeShortName("2 Cool"))
    }
}
