package com.discord.utilities.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeColorParserTest {
    @Test
    fun acceptsEveryFormThemesUse() {
        assertEquals(-14188314, ThemeColorParser.parse("-14188314"))
        assertEquals(0xFF5865F2.toInt(), ThemeColorParser.parse("4283983346"))
        assertEquals(-1, ThemeColorParser.parse("4294967295"))
        assertEquals(0, ThemeColorParser.parse("0"))
        assertEquals(0xFF5865F2.toInt(), ThemeColorParser.parse("#5865f2"))
        assertEquals(0x805865F2.toInt(), ThemeColorParser.parse("#805865F2"))
        assertEquals(0xFFAABBCC.toInt(), ThemeColorParser.parse("#abc"))
        assertEquals(0x11AABBCC, ThemeColorParser.parse("#1abc"))
        assertEquals(0x805865F2.toInt(), ThemeColorParser.parse(" 0x805865F2 "))
    }

    @Test
    fun rejectsWhatIsNotAColor() {
        listOf(
            "", "#", "#12", "#12345", "#1234567", "#ggg", "#-12", "4294967296", "-2147483649", "1.5",
            "system_accent1_500", "red",
        ).forEach { assertNull(it, ThemeColorParser.parse(it)) }
    }

    @Test
    fun hexRoundTripsWithAlpha() {
        assertEquals("#1AFAA61A", ThemeColorParser.toHex(0x1AFAA61A))
        assertEquals("#00000000", ThemeColorParser.toHex(0))
        assertEquals(-14188314, ThemeColorParser.parse(ThemeColorParser.toHex(-14188314)))
    }
}
