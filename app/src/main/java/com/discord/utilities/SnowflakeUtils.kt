package com.discord.utilities

/**
 * Conversions between Discord snowflake ids and wall-clock time.
 *
 * A snowflake packs its creation time into the top 42 bits as milliseconds since [DISCORD_EPOCH],
 * which makes ids directly comparable as timestamps and lets the client synthesise an id for "the
 * message that would have been sent at time T" — which is how read-state acks and history
 * pagination boundaries are expressed.
 *
 * The low 22 bits are worker/process/sequence and are discarded by every function here.
 */
object SnowflakeUtils {

    /** 2015-01-01T00:00:00Z, the zero point for every Discord snowflake. */
    const val DISCORD_EPOCH: Long = 1_420_070_400_000L

    /** Bits of worker/process/sequence data below the timestamp. */
    const val SNOWFLAKE_TIMESTAMP_SHIFT: Int = 22

    /** Unix millis at which [snowflakeId] was minted. */
    @JvmStatic
    fun toTimestamp(snowflakeId: Long): Long =
        (snowflakeId ushr SNOWFLAKE_TIMESTAMP_SHIFT) + DISCORD_EPOCH

    /** The lowest snowflake that could have been minted at [timestamp]. */
    @JvmStatic
    fun fromTimestamp(timestamp: Long): Long =
        (timestamp - DISCORD_EPOCH) shl SNOWFLAKE_TIMESTAMP_SHIFT

    /**
     * Intended as "the lowest snowflake belonging to the millisecond before [snowflakeId]", i.e. an
     * exclusive "everything strictly older than this message" bound.
     *
     * **It subtracts [DISCORD_EPOCH] from a value that is already relative to the epoch**, so the
     * result is a large negative number rather than a usable id. That is not a transcription
     * mistake — `javap -c` of the reference shows exactly `lushr 22; lconst_1 lsub;
     * ldc 1420070400000 lsub; lshl 22`. Reproduced for parity; `StoreReadStates` open-codes the
     * same expression.
     *
     * A caller that wants the intended value wants `fromTimestamp(toTimestamp(id) - 1)`.
     */
    @JvmStatic
    fun atPreviousMillisecond(snowflakeId: Long): Long =
        ((snowflakeId ushr SNOWFLAKE_TIMESTAMP_SHIFT) - 1L - DISCORD_EPOCH) shl SNOWFLAKE_TIMESTAMP_SHIFT

    /** Millis since [DISCORD_EPOCH] — the raw timestamp field, not a Unix time. */
    val Long.timestampPart: Long
        get() = this ushr SNOWFLAKE_TIMESTAMP_SHIFT

    /**
     * Parses a string as a snowflake, rejecting values that cannot be one.
     *
     * The floor is [DISCORD_EPOCH] itself: any id below it decodes to a timestamp before Discord
     * existed, so short numeric strings typed by a user are rejected rather than silently accepted
     * as ids from 2015.
     */
    fun String.toSnowflake(): Long? = toLongOrNull()?.takeIf { it > DISCORD_EPOCH }
}
