package com.discord.widgets.chat.input.gifpicker

import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StorePendingReplies
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.messagesend.MessageResult
import com.discord.utilities.messagesend.MessageSendCoordinator
import com.discord.utilities.messagesend.MessageSendLocation
import com.discord.utilities.user.UserUtils.synthesizeApiUser
import com.discord.widgets.chat.input.WidgetChatInput

/**
 * Sends a picked GIF into the currently open channel as its Tenor link.
 *
 * The reference routes this through `com.discord.widgets.chat.MessageManager`, which does not exist
 * in this reconstruction; the coordinator send it would have called is used directly, exactly as
 * [WidgetChatInput]'s own submit path does — including the channel's pending reply and its
 * "ping the author" toggle, which a plain content-only send silently dropped.
 *
 * A send into a thread draft is dropped rather than misdirected to the parent channel: there is no
 * channel id to send to until the thread exists.
 */
class GifMessageSender(
    private val storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val storePendingReplies: StorePendingReplies = StoreStream.getPendingReplies(),
    private val coordinatorProvider: () -> MessageSendCoordinator? =
        { WidgetChatInput.coordinatorProvider?.invoke() },
) {

    /** @return `true` when the send was submitted. */
    fun send(content: String): Boolean {
        val channel = storeChannelsSelected.getSelectedChannel() ?: return false
        val coordinator = coordinatorProvider() ?: return false
        val pendingReply = storePendingReplies.getPendingReply(channel.id)
        val submission = coordinator.send(
            MessageSendCoordinator.Send(
                channelId = channel.id,
                guildId = channel.guildId.takeUnless { it == 0L },
                content = content,
                author = storeUser.getMe().synthesizeApiUser(),
                reply = pendingReply,
                location = MessageSendLocation.CHAT_INPUT,
            ),
        )
        if (submission is MessageSendCoordinator.Submission.Accepted) {
            // A consumed reply is gone however the send settles; keeping it would chain the
            // next message onto the same target.
            storePendingReplies.onDeletePendingReply(channel.id)
            return true
        }
        return false
    }
}
