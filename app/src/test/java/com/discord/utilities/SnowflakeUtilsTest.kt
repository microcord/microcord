package com.discord.utilities

import com.discord.utilities.SnowflakeUtils.DISCORD_EPOCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnowflakeUtilsTest {

    @Test
    fun `fromTimestamp and toTimestamp round trip`() {
        val millis = 1_650_000_000_000L
        assertEquals(millis, SnowflakeUtils.toTimestamp(SnowflakeUtils.fromTimestamp(millis)))
    }

    @Test
    fun `the epoch itself is snowflake zero`() {
        assertEquals(0L, SnowflakeUtils.fromTimestamp(DISCORD_EPOCH))
        assertEquals(DISCORD_EPOCH, SnowflakeUtils.toTimestamp(0L))
    }

    @Test
    fun `low bits are discarded, so any id in a millisecond decodes to that millisecond`() {
        val base = SnowflakeUtils.fromTimestamp(DISCORD_EPOCH + 1234)
        assertEquals(DISCORD_EPOCH + 1234, SnowflakeUtils.toTimestamp(base + 4_194_303L))
    }

    /**
     * Pins a reference bug, verified at bytecode level (`javap -c`: `lushr 22; lconst_1 lsub;
     * ldc 1420070400000 lsub; lshl 22`). `atPreviousMillisecond` subtracts the epoch from a value
     * that is already epoch-relative, so it does *not* produce "one millisecond earlier".
     */
    @Test
    fun `atPreviousMillisecond subtracts the epoch twice, as the reference does`() {
        val id = SnowflakeUtils.fromTimestamp(DISCORD_EPOCH + 5_000)
        assertEquals((5_000L - 1L - DISCORD_EPOCH) shl 22, SnowflakeUtils.atPreviousMillisecond(id))

        // What the name promises, for comparison — this is what a caller actually wants.
        assertEquals(
            DISCORD_EPOCH + 4_999,
            SnowflakeUtils.toTimestamp(
                SnowflakeUtils.fromTimestamp(SnowflakeUtils.toTimestamp(id) - 1),
            ),
        )
    }

    @Test
    fun `timestampPart is millis since the Discord epoch, not a Unix time`() {
        with(SnowflakeUtils) {
            assertEquals(7_000L, SnowflakeUtils.fromTimestamp(DISCORD_EPOCH + 7_000).timestampPart)
        }
    }

    @Test
    fun `toSnowflake rejects numbers too small to be ids`() = with(SnowflakeUtils) {
        assertNull("12345".toSnowflake())
        // The epoch itself is excluded: the bound is strictly greater.
        assertNull(DISCORD_EPOCH.toString().toSnowflake())
        assertNull("not a number".toSnowflake())
        assertEquals(853_397_531_222_016_010L, "853397531222016010".toSnowflake())
    }
}
