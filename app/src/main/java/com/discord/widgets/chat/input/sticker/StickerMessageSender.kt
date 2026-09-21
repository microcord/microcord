package com.discord.widgets.chat.input.sticker

import com.discord.api.channel.Channel
import com.discord.api.message.MessageReference
import com.discord.api.sticker.Sticker
import com.discord.api.user.User
import com.discord.models.message.Message
import com.discord.stores.StorePendingReplies
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.messagesend.MessageSendCoordinator
import com.discord.utilities.messagesend.MessageSendLocation
import com.discord.utilities.user.UserUtils.synthesizeApiUser
import com.discord.widgets.chat.input.WidgetChatInput

/** Sends sticker-only messages through the same reply-aware coordinator as text and GIF sends. */
class StickerMessageSender(
    private val storeUserProvider: () -> StoreUser = StoreStream::getUsers,
    private val storePendingRepliesProvider: () -> StorePendingReplies = StoreStream::getPendingReplies,
    private val coordinatorProvider: () -> MessageSendCoordinator? =
        { WidgetChatInput.coordinatorProvider?.invoke() },
) {
    fun send(channel: Channel, sticker: Sticker): Boolean {
        val storePendingReplies = storePendingRepliesProvider()
        val pendingReply = storePendingReplies.getPendingReply(channel.id)
        val accepted = submit(channel, sticker, pendingReply, MessageSendLocation.CHAT_INPUT)
        if (accepted && pendingReply != null) storePendingReplies.onDeletePendingReply(channel.id)
        return accepted
    }

    fun sendReply(channel: Channel, sticker: Sticker, originalMessage: Message): Boolean = submit(
        channel = channel,
        sticker = sticker,
        pendingReply = StorePendingReplies.PendingReply(
            messageReference = MessageReference(
                channel.guildId.takeUnless { it == 0L },
                channel.id,
                originalMessage.id,
            ),
            originalMessage = originalMessage,
        ),
        location = MessageSendLocation.STICKER_REPLY,
    )

    private fun submit(
        channel: Channel,
        sticker: Sticker,
        pendingReply: StorePendingReplies.PendingReply?,
        location: String,
    ): Boolean {
        val coordinator = coordinatorProvider() ?: return false
        return coordinator.send(
            stickerSendRequest(
                channel = channel,
                sticker = sticker,
                author = storeUserProvider().getMe().synthesizeApiUser(),
                pendingReply = pendingReply,
                location = location,
            ),
        ) is MessageSendCoordinator.Submission.Accepted
    }
}

internal fun stickerSendRequest(
    channel: Channel,
    sticker: Sticker,
    author: User,
    pendingReply: StorePendingReplies.PendingReply?,
    location: String = MessageSendLocation.CHAT_INPUT,
) = MessageSendCoordinator.Send(
    channelId = channel.id,
    guildId = channel.guildId.takeUnless { it == 0L },
    content = "",
    author = author,
    stickers = listOf(sticker),
    reply = pendingReply,
    location = location,
)
