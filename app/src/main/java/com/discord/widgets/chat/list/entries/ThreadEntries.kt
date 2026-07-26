package com.discord.widgets.chat.list.entries

import com.discord.api.channel.Channel
import com.discord.api.role.GuildRole
import com.discord.models.botuikit.MessageComponent
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreThreadDraft

/** Thread affordances inside the chat list, plus the bot UI-kit component block. */

/**
 * The thread preview card under the message that started a thread: name, reply count and a preview
 * of the newest reply. [roleMentions] is non-null only when that preview actually contains a role
 * mention, so the common case skips resolving the guild's roles.
 */
data class ThreadEmbedEntry(
    val messageId: Long,
    val thread: Channel,
    val threadMessageCount: Int,
    val mostRecentMessage: Message?,
    val roleMentions: Map<Long, GuildRole>?,
    val nickOrUsernames: Map<Long, String>,
    val animateEmojis: Boolean,
    val mostRecentMessageGuildMember: GuildMember?,
    val parentMessage: Message,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.THREAD_EMBED
    override val key: String = "${ChatListEntry.THREAD_EMBED} -- $messageId"
}

/**
 * The inline "name your thread" form shown while composing a new thread. [parentMessageId] is set
 * when the thread is being started from an existing message and null for a channel-level thread.
 */
data class ThreadDraftFormEntry(
    val parentChannel: Channel,
    val parentMessageId: Long?,
    val guildId: Long,
    val guild: Guild?,
    val defaultAutoArchiveDuration: Int?,
    val threadDraftState: StoreThreadDraft.ThreadDraftState,
    val canCreatePrivateThread: Boolean,
    val canSeePrivateThreadOption: Boolean,
    val canCreatePublicThread: Boolean,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.THREAD_DRAFT_FORM
    override val key: String = "${ChatListEntry.THREAD_DRAFT_FORM}${parentChannel.id}$parentMessageId"
}

/**
 * The buttons and select menus a bot attached to its message.
 *
 * [applicationId] is the application the interaction is dispatched to — the message's declared
 * application when it has one, otherwise the bot that authored it.
 */
data class BotUiComponentEntry(
    val message: Message,
    val applicationId: Long,
    val guildId: Long?,
    val messageComponents: List<MessageComponent>,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.BOT_UI_COMPONENT
    override val key: String =
        "${ChatListEntry.BOT_UI_COMPONENT} -- ${message.nonce ?: message.id}"
}
