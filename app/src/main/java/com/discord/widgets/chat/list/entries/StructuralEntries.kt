package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message

/**
 * The rows that structure the list rather than carry message content: separators, spacers, the
 * loading indicator, the search-mode states and the pinned-messages empty state.
 *
 * Every one of these is a singleton row within a channel, so most of them key purely on their type
 * (there can only ever be one of each on screen at a time).
 */

/** Shown at the top while older history is still being fetched. */
class LoadingEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.LOAD_INDICATOR
    override val key: String = ChatListEntry.LOAD_INDICATOR.toString()

    override fun equals(other: Any?): Boolean = other is LoadingEntry
    override fun hashCode(): Int = ChatListEntry.LOAD_INDICATOR
}

/**
 * Bottom padding under the newest message. The list is rendered with a reversed layout manager, so
 * this is the first row and keeps the newest message clear of the chat input.
 */
data class SpacerEntry(val channelId: Long) : ChatListEntry() {
    override val type: Int = ChatListEntry.SPACER
    override val key: String = "${ChatListEntry.SPACER}$channelId"
}

/** Hairline used between pinned messages. */
class DividerEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.DIVIDER
    override val key: String = ChatListEntry.DIVIDER.toString()

    override fun equals(other: Any?): Boolean = other is DividerEntry
    override fun hashCode(): Int = ChatListEntry.DIVIDER
}

/** Date separator; [timestamp] is the start of the day the following messages belong to. */
data class TimestampEntry(val messageId: Long, val timestamp: Long) : ChatListEntry() {
    override val type: Int = ChatListEntry.TIMESTAMP
    override val key: String = "${ChatListEntry.TIMESTAMP}$messageId"
}

/** The red "new messages" line. [messageId] is the first message the viewer has not read. */
data class NewMessagesEntry(val channelId: Long, val messageId: Long) : ChatListEntry() {
    override val type: Int = ChatListEntry.NEW_MESSAGES
    override val key: String = "${ChatListEntry.NEW_MESSAGES}$channelId"
}

/** Separates a thread's starter message from the thread's own messages. */
data class ThreadStarterDividerEntry(val channelId: Long, val messageId: Long) : ChatListEntry() {
    override val type: Int = ChatListEntry.THREAD_STARTER_DIVIDER
    override val key: String = "${ChatListEntry.THREAD_STARTER_DIVIDER}$channelId"
}

/** Empty state for the pinned-messages surface. */
data class EmptyPinsEntry(val text: String) : ChatListEntry() {
    override val type: Int = ChatListEntry.EMPTY_PINS
    override val key: String = ChatListEntry.EMPTY_PINS.toString()
}

/** Context header above a pinned or search result, naming the channel it came from. */
data class MessageHeaderEntry(
    val message: Message,
    val text: String?,
    val channelName: String?,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.MESSAGE_HEADER
    override val key: String = ChatListEntry.MESSAGE_HEADER.toString()
}

/** Footer under a mention result offering to jump to the message in its channel. */
class MentionFooterEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.MENTION_FOOTER
    override val key: String = ChatListEntry.MENTION_FOOTER.toString()

    override fun equals(other: Any?): Boolean = other is MentionFooterEntry
    override fun hashCode(): Int = ChatListEntry.MENTION_FOOTER
}

/** "N results" header shown when the chat list is displaying search results. */
data class SearchResultCountEntry(val totalResults: Int) : ChatListEntry() {
    override val type: Int = ChatListEntry.SEARCH_RESULT_COUNT
    override val key: String = ChatListEntry.SEARCH_RESULT_COUNT.toString()
}

/** The guild's messages are still being indexed, so search cannot answer yet. */
class SearchIndexingEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.SEARCH_INDEXING
    override val key: String = ChatListEntry.SEARCH_INDEXING.toString()

    override fun equals(other: Any?): Boolean = other is SearchIndexingEntry
    override fun hashCode(): Int = ChatListEntry.SEARCH_INDEXING
}

/** Search ran and matched nothing. */
class SearchEmptyEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.SEARCH_EMPTY
    override val key: String = ChatListEntry.SEARCH_EMPTY.toString()

    override fun equals(other: Any?): Boolean = other is SearchEmptyEntry
    override fun hashCode(): Int = ChatListEntry.SEARCH_EMPTY
}

/** Search failed. */
class SearchErrorEntry : ChatListEntry() {
    override val type: Int = ChatListEntry.SEARCH_ERROR
    override val key: String = ChatListEntry.SEARCH_ERROR.toString()

    override fun equals(other: Any?): Boolean = other is SearchErrorEntry
    override fun hashCode(): Int = ChatListEntry.SEARCH_ERROR
}

/**
 * Collapsed run of consecutive messages from blocked users. [message] is the first message of the
 * run and supplies the identity the expand/collapse toggle is keyed on.
 */
data class BlockedMessagesEntry(
    val message: Message,
    val blockedCount: Int,
    private val isExpandedBlock: Boolean,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.BLOCKED_MESSAGES
    override val key: String = ChatListEntry.BLOCKED_MESSAGES.toString()

    override fun isInExpandedBlockedMessageChunk(): Boolean = isExpandedBlock
}

/**
 * Upload progress for a message that is still sending its attachments. Keyed on the nonce because
 * the message has no server id yet.
 */
data class UploadProgressEntry(
    val messageNonce: String,
    val channelId: Long,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.UPLOAD_STATUS
    override val key: String = "${ChatListEntry.UPLOAD_STATUS} $messageNonce"
}

/**
 * The reaction strip under a message.
 *
 * [canAddReactions] gates tapping an existing reaction (false in an archived thread);
 * [canCreateReactions] additionally gates the "+" button, which also needs `ADD_REACTIONS`.
 */
data class ReactionsEntry(
    val message: Message,
    val canAddReactions: Boolean,
    val canCreateReactions: Boolean,
    val animateEmojis: Boolean,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.REACTIONS
    override val key: String = "${ChatListEntry.REACTIONS}${message.nonce ?: message.id}"
}

/** "Only you can see this" footer under an ephemeral interaction response. */
class EphemeralMessageEntry(val message: Message) : ChatListEntry() {
    override val type: Int = ChatListEntry.EPHEMERAL_MESSAGE
    override val key: String = "${ChatListEntry.EPHEMERAL_MESSAGE} ${message.nonce ?: message.id}"

    override fun equals(other: Any?): Boolean =
        other is EphemeralMessageEntry && other.message == message

    override fun hashCode(): Int = message.hashCode()
}
