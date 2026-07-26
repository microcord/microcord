package com.discord.models.domain

import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils

/**
 * A data-harvest (account data export) request. Discord only accepts one every 30 days, which is
 * what [canRequest] enforces client-side.
 */
data class Harvest(
    val id: String,
    val userId: Long,
    val createdAt: String,
    val completedAt: String?,
) {
    fun nextAvailableRequestInMillis(): Long = createdAtInMillis() + REQUEST_COOLDOWN_MILLIS

    fun canRequest(nowMillis: Long = ClockFactory.get().currentTimeMillis()): Boolean =
        nowMillis > nextAvailableRequestInMillis()

    private fun createdAtInMillis(): Long = TimeUtils.parseUTCDate(createdAt)

    private companion object {
        /** 30 days. */
        const val REQUEST_COOLDOWN_MILLIS = 2_592_000_000L
    }
}
