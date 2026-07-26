package com.discord.utilities.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageUtilsTest {

    @Test
    fun `compareMessages orders ids oldest first`() {
        assertTrue(MessageUtils.compareMessages(1L, 2L) < 0)
        assertTrue(MessageUtils.compareMessages(2L, 1L) > 0)
        assertEquals(0, MessageUtils.compareMessages(2L, 2L))
    }

    @Test
    fun `a null id sorts as the oldest possible message`() {
        assertTrue(MessageUtils.compareMessages(null, 1L) < 0)
        assertTrue(MessageUtils.compareMessages(1L, null) > 0)
        assertEquals(0, MessageUtils.compareMessages(null, null))
    }

    @Test
    fun `real snowflakes far apart do not overflow the comparison`() {
        val oldest = 0L
        val newest = Long.MAX_VALUE / 2
        assertTrue(MessageUtils.compareMessages(oldest, newest) < 0)
        assertTrue(MessageUtils.compareMessages(newest, oldest) > 0)
    }

    @Test
    fun `isNewer is true when the first id is the older one`() {
        // The reference's name is inverted: it answers "does 1 come before 2".
        assertTrue(MessageUtils.isNewer(1L, 2L))
        assertFalse(MessageUtils.isNewer(2L, 1L))
        assertFalse(MessageUtils.isNewer(2L, 2L))
    }

    @Test
    fun `the comparator sorts a list oldest first`() {
        val ids = listOf(30L, 10L, 20L)
        assertEquals(listOf(10L, 20L, 30L), ids.sortedWith(MessageUtils.SORT_BY_IDS_COMPARATOR))
    }
}
