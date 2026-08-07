package com.discord.models.application

import com.discord.models.message.Message

/**
 * How far behind the user is in one channel.
 *
 * [marker] is the last message they acked; [count] is how many of the loaded [Message]s come after
 * it, which is what drives the "new messages" divider and the unread badge.
 */
data class Unread(
    val marker: Marker = Marker(),
    val count: Int = 0,
    /** True when [count] is a floor rather than the real number. See [isEstimated]. */
    val estimated: Boolean = false,
) {
    constructor(marker: Marker, messages: List<Message>) : this(
        marker,
        getTotalMessages(marker, messages),
        isEstimated(marker, messages),
    )

    fun createWithEmptyCount(): Unread = Unread(marker, 0)

    data class Marker(
        val channelId: Long = 0,
        val messageId: Long? = 0,
    ) {
        /** Collapses "acked exactly [lastAckedId]" to a null marker, meaning "nothing unread". */
        constructor(channelId: Long, lastAckedId: Long, latestMessageId: Long?) : this(
            channelId,
            if (latestMessageId != lastAckedId) lastAckedId else null,
        )
    }

    companion object {
        /**
         * Whether the acked message is missing from the window we hold.
         *
         * When it is, every loaded message counts as unread and the true number is however many
         * more the client has not fetched, so the count is only a lower bound.
         */
        private fun isEstimated(marker: Marker, messages: List<Message>): Boolean {
            if (messages.isEmpty() || marker.messageId == null) return false
            return messages.none { it.id == marker.messageId }
        }

        private fun getTotalMessages(marker: Marker, messages: List<Message>): Int {
            if (messages.isEmpty() || marker.messageId == null) return 0
            var count = 0
            for (message in messages.asReversed()) {
                if (message.id == marker.messageId) return count
                count++
            }
            return count
        }
    }
}
