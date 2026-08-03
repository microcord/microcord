package com.discord.widgets.chat.list.entries

import com.discord.api.channel.Channel
import com.discord.api.role.GuildRole
import com.discord.api.sticker.BaseSticker
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreMessageReplies
import com.discord.stores.StoreMessageState

/**
 * The body of a message.
 *
 * [type] is derived from the message itself rather than passed in, because the chat list renders a
 * message with a completely different view holder depending on what kind of message it is: an
 * ordinary body, a compact continuation of the previous author's block, a reply header, a system
 * notice, a failed send, a slash-command invocation, an invite reminder or an AutoMod block notice.
 */
data class MessageEntry(
    val message: Message,
    val messageState: StoreMessageState.State?,
    val author: GuildMember?,
    val firstMentionedUser: GuildMember? = null,
    val roles: Map<Long, GuildRole>?,
    val nickOrUsernames: Map<Long, String>,
    private val isMinimal: Boolean = false,
    private val isExpandedBlocked: Boolean,
    val animateEmojis: Boolean = true,
    val replyData: ReplyData? = null,
    val interactionAuthor: GuildMember? = null,
    val isThreadStarterMessage: Boolean = false,
    val isGuildForumPostFirstMessage: Boolean = false,
    val isGuildForumPostAuthor: Boolean = false,
    val ctaData: CtaData? = null,
    val permissionsForChannel: Long?,
) : ChatListEntry() {

    override val type: Int = resolveType(message, isMinimal)

    /**
     * Local messages are keyed by nonce rather than id: an optimistic message keeps its nonce when
     * the server assigns the real id, so the row survives the swap instead of being torn down.
     */
    override val key: String = "$type ${message.nonce ?: message.id}"

    override fun isInExpandedBlockedMessageChunk(): Boolean = isExpandedBlocked

    /** The sticker CTA a welcome or role-subscription-purchase system message offers. */
    data class CtaData(val sticker: BaseSticker, val channel: Channel)

    /**
     * The inline preview above a reply. [messageEntry] is the referenced message rendered through
     * this same pipeline, so the preview shows real markdown rather than raw content.
     */
    data class ReplyData(
        val messageState: StoreMessageReplies.MessageState,
        val messageEntry: MessageEntry?,
        val isRepliedUserBlocked: Boolean = false,
    )

    private companion object {
        private const val TYPE_DEFAULT = 0
        private const val TYPE_CALL = 3
        private const val TYPE_CHANNEL_FOLLOW_ADD = 13
        private const val TYPE_REPLY = 19
        private const val TYPE_APPLICATION_COMMAND = 20
        private const val TYPE_GUILD_INVITE_REMINDER = 22
        private const val TYPE_CONTEXT_MENU_COMMAND = 23

        private const val TYPE_LOCAL = -1
        private const val TYPE_LOCAL_FAILED = -2
        private const val TYPE_LOCAL_INVALID = -3
        private const val TYPE_LOCAL_SLOWMODE = -6
        private const val TYPE_LOCAL_AUTOMOD_BLOCKED = -8

        /**
         * Reproduces the reference's message-type → row-type table exactly.
         *
         * Anything the viewer typed (or that behaves like it — plain, local, slow-mode-rejected)
         * renders as an ordinary body; the remaining branches pick a specialised row.
         */
        fun resolveType(message: Message, isMinimal: Boolean): Int {
            if (isMinimal) return ChatListEntry.MESSAGE_MINIMAL
            val messageType = message.type
            if (message.hasSnapshot() ||
                message.isInteraction() ||
                messageType == null ||
                messageType == TYPE_DEFAULT ||
                messageType == TYPE_LOCAL ||
                messageType == TYPE_LOCAL_SLOWMODE
            ) {
                return ChatListEntry.MESSAGE
            }
            if ((messageType == TYPE_APPLICATION_COMMAND || messageType == TYPE_CONTEXT_MENU_COMMAND) &&
                message.interaction == null
            ) {
                return ChatListEntry.APPLICATION_COMMAND
            }
            return when (messageType) {
                TYPE_CALL, TYPE_CHANNEL_FOLLOW_ADD -> ChatListEntry.CALL_MESSAGE
                TYPE_LOCAL_FAILED, TYPE_LOCAL_INVALID -> ChatListEntry.MESSAGE_FAILED
                TYPE_REPLY -> ChatListEntry.REPLY
                TYPE_GUILD_INVITE_REMINDER -> ChatListEntry.GUILD_INVITE_REMINDER
                TYPE_LOCAL_AUTOMOD_BLOCKED -> ChatListEntry.AUTOMOD_BLOCKED
                else -> ChatListEntry.SYSTEM_MESSAGE
            }
        }
    }
}
