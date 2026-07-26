package com.discord.widgets.chat.list.entries

import com.discord.api.channel.ForumTag
import com.discord.api.channel.ForumTagId
import com.discord.api.sticker.Sticker
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember

/**
 * The rows that head a conversation: the "this is the beginning of…" panels, the new-guild welcome
 * call to action, and the greet-sticker prompts shown in an empty DM.
 */

/**
 * Head of a guild channel, thread or forum post.
 *
 * Which affordances the panel offers depends on what the viewer may do here: [canManageChannel]
 * shows the edit-channel shortcut, [canManageThread] the thread settings, and
 * [canReadMessageHistory] decides whether the panel claims to be the start of the conversation at
 * all — without that permission there is history the viewer simply cannot see.
 */
data class StartOfChatEntry(
    val channelId: Long,
    val channelName: String,
    val canReadMessageHistory: Boolean,
    val canManageChannel: Boolean,
    val canManageThread: Boolean,
    val isThread: Boolean,
    val threadAutoArchiveDuration: Int?,
    val threadCreatorMember: GuildMember?,
    val threadCreatorName: String,
    val isTextInVoice: Boolean,
    val isGuildForumPost: Boolean,
    val canAccessRedesignedForumChannels: Boolean,
    val availableTags: List<ForumTag>?,
    val appliedTags: List<ForumTagId>?,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.START_OF_CHAT
    override val key: String = "${ChatListEntry.START_OF_CHAT}$channelId"
}

/**
 * Head of a DM or group DM. [mutualGuilds] backs the "you share N servers" line, and
 * [isSystemDM] switches the copy to the official-notices variant.
 */
data class StartOfPrivateChatEntry(
    val channelId: Long,
    val channelName: String,
    val channelType: Int,
    val channelIcon: String?,
    val isSystemDM: Boolean,
    val mutualGuilds: List<Guild>,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.START_OF_PRIVATE_CHAT
    override val key: String = "${ChatListEntry.START_OF_PRIVATE_CHAT}$channelId"
}

/**
 * Replaces the start-of-chat panel in a brand new guild's default channel, offering the setup
 * actions the viewer is allowed to take (upload an icon, invite friends).
 */
data class GuildWelcomeEntry(
    val isOwner: Boolean,
    val guildHasIcon: Boolean,
    val canInvite: Boolean,
    val guildId: Long,
    val guildName: String,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.GUILD_WELCOME
    override val key: String = "${ChatListEntry.GUILD_WELCOME}$guildId"
}

/** Full-size "say hi with a sticker" prompt shown in a DM with no history at all. */
data class StickerGreetEntry(
    val sticker: Sticker,
    val channelId: Long,
    val channelName: String,
    val channelType: Int,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.STICKER_GREET
    override val key: String = "${ChatListEntry.STICKER_GREET} -- ${sticker.id}"
}

/** Compact greet prompt shown when the DM already has a few messages. */
data class StickerGreetCompactEntry(
    val sticker: Sticker,
    val channelId: Long,
    val channelName: String,
    val channelType: Int,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.STICKER_GREET_COMPACT
    override val key: String = "${ChatListEntry.STICKER_GREET_COMPACT} -- ${sticker.id}"
}
