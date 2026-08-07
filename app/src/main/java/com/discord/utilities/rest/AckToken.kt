package com.discord.utilities.rest

/**
 * The rolling token the ack endpoint hands back on every write.
 *
 * One value for the whole session rather than one per channel, matching the reference. The update
 * is conditional on the token that was actually sent still being current, so a response that
 * overtakes a newer ack cannot resurrect a stale token.
 */
object AckToken {

    @Volatile
    private var token: String? = null

    fun get(): String? = token

    fun update(sent: String?, received: String?) {
        synchronized(this) {
            if (token == sent) token = received
        }
    }

    fun clear() {
        synchronized(this) { token = null }
    }
}
