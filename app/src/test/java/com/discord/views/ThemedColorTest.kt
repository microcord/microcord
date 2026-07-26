package com.discord.views

import android.util.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemedColorTest {
    @Test
    fun `uses the raw value for a literal colour attribute`() {
        val resolved = ThemedColor.resolve(
            type = TypedValue.TYPE_INT_COLOR_ARGB8,
            data = 0xFF5865F2.toInt(),
            resourceId = 0,
            fallback = 0,
        ) { error("resource lookup must not be used for a literal colour") }

        assertEquals(0xFF5865F2.toInt(), resolved)
    }

    @Test
    fun `looks up the resource for a colour-state-list attribute`() {
        // A colour-state-list theme attribute reports a string-pool index in `data`,
        // which is meaningless as a colour and must never be used as one.
        val resolved = ThemedColor.resolve(
            type = TypedValue.TYPE_STRING,
            data = 17,
            resourceId = 0x7F060042,
            fallback = 0,
        ) { resourceId ->
            assertEquals(0x7F060042, resourceId)
            0xFF2D7D46.toInt()
        }

        assertEquals(0xFF2D7D46.toInt(), resolved)
    }

    @Test
    fun `looks up the resource for a reference-backed colour attribute`() {
        val resolved = ThemedColor.resolve(
            type = TypedValue.TYPE_REFERENCE,
            data = 0x7F060042,
            resourceId = 0x7F060042,
            fallback = 0,
        ) { 0xFFED4245.toInt() }

        assertEquals(0xFFED4245.toInt(), resolved)
    }

    @Test
    fun `falls back when the attribute resolves to nothing usable`() {
        val resolved = ThemedColor.resolve(
            type = TypedValue.TYPE_NULL,
            data = 0,
            resourceId = 0,
            fallback = 0xFF000000.toInt(),
        ) { error("resource lookup must not be used without a resource id") }

        assertEquals(0xFF000000.toInt(), resolved)
    }
}
