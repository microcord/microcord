package com.discord.utilities.messagesend

import com.discord.utilities.SnowflakeUtils

class NonceGenerator(private val now: () -> Long = System::currentTimeMillis) {
    fun nonce(): Long = computeNonce(now())

    companion object {
        private var previousNonce = 0L

        @Synchronized
        fun computeNonce(nowMillis: Long = System.currentTimeMillis()): Long {
            val candidate = SnowflakeUtils.fromTimestamp(nowMillis + 1_471_228_928L)
            return maxOf(candidate, previousNonce + 1).also { previousNonce = it }
        }
    }
}
