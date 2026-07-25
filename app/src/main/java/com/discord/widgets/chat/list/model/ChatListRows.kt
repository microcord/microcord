package com.discord.widgets.chat.list.model

import com.discord.api.channel.Channel
import com.discord.models.message.Message
import com.discord.stores.StoreMessagesLoader
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.stores.StoreMessageUploads
import java.util.Calendar

object ChatListRows {
    private const val CONCATENATION_WINDOW_MS = 7 * 60 * 1000L
    private const val MAX_CONCATENATED_MESSAGES = 5

    data class State(
        val channel: Channel,
        val messages: List<Message>,
        val loaded: StoreMessagesLoader.ChannelLoadedState,
        val detached: Boolean = false,
        val unreadMessageId: Long? = null,
        val uploads: Map<String, StoreMessageUploads.MessageUploadState> = emptyMap(),
    )

    fun build(state: State): List<ChatListEntry> {
        val messages = state.messages.sortedBy(Message::id)
        val rows = ArrayList<ChatListEntry>()
        if (state.detached || !state.loaded.isInitialMessagesLoaded || state.loaded.isLoadingMessages) {
            rows += ChatListEntry.Loading(state.channel.id)
        }
        if ((state.loaded.isOldestMessagesLoaded || messages.isEmpty()) && state.loaded.isInitialMessagesLoaded && !state.detached) {
            rows += ChatListEntry.Start(state.channel)
        }

        var previous: Message? = null
        var groupSize = 0
        var unreadAdded = false
        messages.forEach { message ->
            val timestamp = message.timestamp?.dateTimeMillis ?: snowflakeTimestamp(message.id)
            if (previous == null || !sameDay(timestamp, timestamp(previous!!))) {
                rows += ChatListEntry.DayDivider(message.id, timestamp)
            }
            if (!unreadAdded && state.unreadMessageId != null && message.id > state.unreadMessageId) {
                rows += ChatListEntry.UnreadDivider(state.channel.id, message.id)
                unreadAdded = true
            }

            val text = safeText(message)
            val concatenate = canConcatenate(previous, message, groupSize)
            rows += when {
                message.isFailed() -> ChatListEntry.Failed(message, text)
                isSystem(message) -> ChatListEntry.System(message, text)
                concatenate -> ChatListEntry.Minimal(message, text)
                else -> ChatListEntry.Header(message, text)
            }
            groupSize = if (concatenate) groupSize + 1 else 1
            message.attachments.orEmpty().forEachIndexed { index, attachment ->
                rows += ChatListEntry.Attachment(message, attachment, index)
            }
            val reactions = message.reactions.orEmpty()
            if (reactions.isNotEmpty()) rows += ChatListEntry.Reactions(message, reactions)
            message.nonce?.let { nonce ->
                state.uploads[nonce]?.takeUnless { it == StoreMessageUploads.MessageUploadState.None }?.let {
                    rows += ChatListEntry.UploadProgress(message, it)
                }
            }
            previous = message
        }
        rows += ChatListEntry.Spacer(state.channel.id)
        return rows.asReversed()
    }

    private fun canConcatenate(previous: Message?, message: Message, groupSize: Int): Boolean {
        if (previous == null || groupSize >= MAX_CONCATENATED_MESSAGES) return false
        if (previous.isFailed() || isSystem(previous) || message.isFailed() || isSystem(message)) return false
        val previousTime = timestamp(previous)
        val currentTime = timestamp(message)
        return previous.author?.id != null && previous.author?.id == message.author?.id &&
            currentTime >= previousTime && currentTime - previousTime <= CONCATENATION_WINDOW_MS
    }

    private fun safeText(message: Message): String {
        val parts = ArrayList<String>()
        message.referencedMessage?.let { reply ->
            parts += "[Reply to ${reply.author?.username ?: "message"}: ${reply.content?.takeIf(String::isNotBlank) ?: "content unavailable"}]"
        }
        message.content?.takeIf(String::isNotBlank)?.let(parts::add)
        message.embeds.orEmpty().forEach { embed ->
            parts += "[Embed: ${embed.title ?: embed.description ?: "rich content"}]"
        }
        message.stickers.orEmpty().forEach { parts += "[Sticker: ${it.name}]" }
        message.stickerItems.orEmpty().forEach { parts += "[Sticker: ${it.name}]" }
        if (!message.components.isNullOrEmpty()) parts += "[Interactive content]"
        if (message.call != null) parts += "[Call]"
        if (message.activity != null && parts.isEmpty()) parts += "[Activity]"
        if (parts.isEmpty() && message.hasAttachments()) parts += "Attachment"
        if (parts.isEmpty()) parts += if (isSystem(message)) "System message" else "Message content unavailable"
        return parts.joinToString("\n")
    }

    private fun isSystem(message: Message) = message.isSystemMessage() && !message.isLocal()

    private fun timestamp(message: Message) =
        message.timestamp?.dateTimeMillis ?: snowflakeTimestamp(message.id)

    private fun snowflakeTimestamp(id: Long) = if (id > 4_194_304L) (id ushr 22) + 1_420_070_400_000L else 0L

    private fun sameDay(first: Long, second: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = first }
        val b = Calendar.getInstance().apply { timeInMillis = second }
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}
