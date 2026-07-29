package com.discord.widgets.chat.input.models

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandOptionValueTest {
    @Test
    fun primitiveValuesMatch12621Representations() {
        assertEquals("raw", RawOptionValue("raw").value)
        assertEquals("text", StringOptionValue("text").value)
        assertEquals(true, BooleanOptionValue(true).value)
        assertEquals(42L, IntegerOptionValue(42L).value)
        assertEquals(1.5, NumberOptionValue(1.5).value)
        assertEquals("123", SnowflakeOptionValue(123L).value)
        assertEquals("file.png", AttachmentOptionValue("file.png").value)
        assertEquals(OptionRange(0..2, 4..6), OptionRange(0..2, 4..6))
    }
}
