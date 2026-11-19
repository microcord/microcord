package com.discord.widgets.chat.list.actions

import com.discord.api.channel.Channel
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.models.message.Message
import com.discord.stores.StoreMessagesHolder
import com.discord.utilities.feature.FeatureFlags
import java.nio.charset.StandardCharsets

class MessageActionCoordinator(private val callbacks: Callbacks) {
    data class Callbacks(
        val deleteRemote: (Long, Long, (Result<Unit>) -> Unit) -> Unit,
        val editRemote: (Long, Long, String, (Result<Unit>) -> Unit) -> Unit,
        /** Channel, message, emoji route, removing, burst (a super reaction), done. */
        val toggleReactionRemote: (Long, Long, String, Boolean, Boolean, (Result<Unit>) -> Unit) -> Unit,
        val setPinnedRemote: (Long, Long, Boolean, (Result<Unit>) -> Unit) -> Unit,
        val messageUpdated: (Message) -> Unit,
        val messageDeleted: (Long, Long) -> Unit,
        val createPendingReply: (Channel, Message, Boolean, Boolean) -> Unit,
        val retryLocal: (Message) -> Unit,
        val cancelLocal: (Message) -> Unit,
        /** Records an emoji as reacted with; see [toggleReaction]. */
        val reactionAdded: (String) -> Unit = {},
    )

    fun delete(message: Message, completed: (Result<Unit>) -> Unit = {}) {
        callbacks.deleteRemote(message.channelId, message.id) { result ->
            if (result.isSuccess) callbacks.messageDeleted(message.channelId, message.id)
            completed(result)
        }
    }

    fun edit(message: Message, content: String, completed: (Result<Unit>) -> Unit = {}) {
        callbacks.editRemote(message.channelId, message.id, content) { result ->
            if (result.isSuccess) callbacks.messageUpdated(message.copy(content = content))
            completed(result)
        }
    }

    /**
     * Adds or takes back the current user's reaction with [emoji]; with [burst], their super
     * reaction, which is kept apart from a normal one on the same emoji.
     */
    fun toggleReaction(
        message: Message,
        emoji: MessageReactionEmoji,
        burst: Boolean = false,
        completed: (Result<Unit>) -> Unit = {},
    ) {
        val previous = message.reactions
        val existing = previous.orEmpty().firstOrNull { sameEmoji(it.emoji, emoji) }
        val removing = existing != null && (if (burst) existing.meBurst else existing.me)
        // The store applies the gateway echo with the same rules, so the echo of this is a no-op.
        val updated = if (removing) StoreMessagesHolder.removeReaction(message, emoji, isMe = true, burst = burst)
        else StoreMessagesHolder.addReaction(message, emoji, isMe = true, burst = burst)
        callbacks.messageUpdated(updated)
        // Scored here rather than at each button: this is the one place every added reaction passes
        // through, whether it came from the picker, a tap on an existing one, or another screen.
        // A custom emoji is keyed by id and a Unicode one by its surrogates, which is exactly what
        // the reaction carries.
        if (!removing) callbacks.reactionAdded(emoji.id ?: emoji.name.orEmpty())
        callbacks.toggleReactionRemote(message.channelId, message.id, reactionRoute(emoji), removing, burst) { result ->
            if (result.isFailure) callbacks.messageUpdated(message.copy(reactions = previous))
            completed(result)
        }
    }

    fun setPinned(message: Message, pinned: Boolean, completed: (Result<Unit>) -> Unit = {}) {
        callbacks.messageUpdated(message.copy(pinned = pinned))
        callbacks.setPinnedRemote(message.channelId, message.id, pinned) { result ->
            if (result.isFailure) callbacks.messageUpdated(message)
            completed(result)
        }
    }

    fun reply(channel: Channel, message: Message, currentUserId: Long) {
        val ownOrWebhook = message.author?.id == currentUserId || message.webhookId != null
        val privateChannel = channel.type == Channel.DM || channel.type == Channel.GROUP_DM
        callbacks.createPendingReply(
            channel,
            message,
            replyStartsMentioning(ownOrWebhook, FeatureFlags.noReplyPingEnabled()),
            !ownOrWebhook && !privateChannel,
        )
    }

    fun retry(message: Message) { if (message.canResend()) callbacks.retryLocal(message) }
    fun cancel(message: Message) { if (message.isLocal()) callbacks.cancelLocal(message) }

    companion object {
        /**
         * Whether a new reply starts with the author's @ on. Your own and webhook messages never ping;
         * the "no reply auto ping" flag starts every reply with it off, and the @ON/@OFF toggle in the
         * reply bar still switches it back on for that reply.
         */
        internal fun replyStartsMentioning(ownOrWebhook: Boolean, noReplyPing: Boolean) =
            !ownOrWebhook && !noReplyPing

        fun reactionRoute(emoji: MessageReactionEmoji): String = encodePathSegment(
            if (emoji.id == null) requireNotNull(emoji.name) else "${requireNotNull(emoji.name)}:${emoji.id}",
        )

        private fun sameEmoji(left: MessageReactionEmoji, right: MessageReactionEmoji) =
            if (left.id != null || right.id != null) left.id == right.id else left.name == right.name

        private fun encodePathSegment(value: String): String = buildString {
            value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                if (unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
                    unsigned in '0'.code..'9'.code || unsigned in "-._~".map(Char::code)
                ) append(unsigned.toChar()) else append('%').append("%02X".format(unsigned))
            }
        }
    }
}
