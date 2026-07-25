package com.discord.utilities.messagesend

class NonceGenerator(private val now: () -> Long = System::currentTimeMillis) {
    fun nonce(): Long = computeNonce(now())

    companion object {
        private const val DISCORD_EPOCH = 1_420_070_400_000L
        private var previousNonce = 0L

        @Synchronized
        fun computeNonce(nowMillis: Long = System.currentTimeMillis()): Long {
            val candidate = (nowMillis + 1_471_228_928L - DISCORD_EPOCH) shl 22
            return maxOf(candidate, previousNonce + 1).also { previousNonce = it }
        }
    }
}
