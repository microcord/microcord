package com.discord.widgets.chat.input.gifpicker

import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreMessages
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser

/**
 * Sends a picked GIF into the currently open channel as its Tenor link.
 *
 * The reference routes this through `com.discord.widgets.chat.MessageManager`, which does not exist
 * in this reconstruction; the send pipeline it would have called is `StoreMessages.sendMessage`,
 * which this calls directly. Same three inputs (channel, author, content), same optimistic-message
 * behaviour.
 *
 * A send into a thread draft is dropped rather than misdirected to the parent channel: there is no
 * channel id to send to until the thread exists.
 */
class GifMessageSender(
    private val storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val storeMessages: StoreMessages = StoreStream.getMessages(),
) {

    /** @return `true` when the send was submitted. */
    fun send(content: String): Boolean {
        val channel = storeChannelsSelected.getSelectedChannel() ?: return false
        storeMessages.sendMessage(
            channelId = channel.id,
            author = storeUser.getMe(),
            content = content,
            guildId = channel.guildId.takeIf { it != 0L },
        ).subscribe({ }, { })
        return true
    }
}
