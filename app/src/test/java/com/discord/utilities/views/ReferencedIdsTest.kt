package com.discord.utilities.views

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferencedIdsTest {
    @Test
    fun resolvesEachListOnceAndFallsBackOnUnknownNames() {
        val ids = mapOf("first" to 11, "second" to 22)
        var lookups = 0
        val lookup = { name: String -> lookups++; ids[name] ?: 0 }

        assertArrayEquals(intArrayOf(11, 22), ReferencedIds.resolve("first, second", lookup))
        assertArrayEquals(intArrayOf(11, 22), ReferencedIds.resolve("first, second", lookup))
        assertEquals(2, lookups)

        assertNull(ReferencedIds.resolve("first,missing", lookup))
        assertNull(ReferencedIds.resolve("", lookup))
    }
}
