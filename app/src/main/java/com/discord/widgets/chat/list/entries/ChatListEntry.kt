package com.discord.widgets.chat.list.entries

import com.discord.api.channel.Channel
import com.discord.api.message.attachment.MessageAttachment
import com.discord.models.message.Message
import com.discord.stores.StoreMessageUploads
import com.discord.api.message.reaction.MessageReaction

sealed class ChatListEntry(key: String) {
    val stableId: Long = key.fold(-3750763034362895579L) { hash, char ->
        (hash xor char.code.toLong()) * 1099511628211L
    }
    open val messageId: Long? = null

    data class Loading(val channelId: Long) : ChatListEntry("loading:$channelId")
    data class Start(val channel: Channel) : ChatListEntry("start:${channel.id}")
    data class Spacer(val channelId: Long) : ChatListEntry("spacer:$channelId")
    data class DayDivider(override val messageId: Long, val timestamp: Long) :
        ChatListEntry("day:$messageId")
    data class UnreadDivider(val channelId: Long, override val messageId: Long) :
        ChatListEntry("unread:$channelId")

    sealed class MessageRow(messageValue: Message, open val text: String, key: String) :
        ChatListEntry(key) {
        abstract val message: Message
        override val messageId: Long = messageValue.id
    }

    data class Header(override val message: Message, override val text: String) :
        MessageRow(message, text, "header:${message.id}")
    data class Minimal(override val message: Message, override val text: String) :
        MessageRow(message, text, "minimal:${message.id}")
    data class System(override val message: Message, override val text: String) :
        MessageRow(message, text, "system:${message.id}")
    data class Failed(override val message: Message, override val text: String) :
        MessageRow(message, text, "failed:${message.id}")
    data class Attachment(
        override val message: Message,
        val attachment: MessageAttachment,
        val index: Int,
    ) : MessageRow(message, attachment.filename, "attachment:${message.id}:$index")
    data class Reactions(override val message: Message, val reactions: List<MessageReaction>) :
        MessageRow(message, reactions.joinToString { it.emoji.name ?: it.emoji.id.orEmpty() }, "reactions:${message.id}")
    data class UploadProgress(override val message: Message, val state: StoreMessageUploads.MessageUploadState) :
        MessageRow(message, message.content.orEmpty(), "upload:${message.id}")
}
