package com.discord.models.domain

import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory

/**
 * Produces the client-side nonces attached to outgoing messages.
 *
 * The value is snowflake-shaped so the server can echo it back and the client can match an
 * optimistic row to the persisted message. Successive calls are strictly increasing even when the
 * clock does not advance between them.
 */
class NonceGenerator {

    fun nonce(): Long = computeNonce()

    companion object {
        private var previousNonce: Long = 0

        @JvmStatic
        @JvmOverloads
        @Synchronized
        fun computeNonce(clock: Clock = ClockFactory.get()): Long {
            var nonce = SnowflakeUtils.fromTimestamp(clock.currentTimeMillis() + MILLIS_OFFSET)
            if (nonce <= previousNonce) nonce = previousNonce + 1
            previousNonce = nonce
            return nonce
        }

        /** Keeps generated nonces clear of real snowflakes so they can never collide. */
        private const val MILLIS_OFFSET = 1_471_228_928L
    }
}
