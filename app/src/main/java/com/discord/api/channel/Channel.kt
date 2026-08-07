package com.discord.api.channel

import com.discord.api.guildhash.GuildHashes
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.thread.ThreadMember
import com.discord.api.thread.ThreadMetadata
import com.discord.api.user.User
import com.discord.primitives.ForumTagId

data class Channel(
    val topic: String?,
    val type: Int,
    val guildId: Long,
    val name: String?,
    val lastMessageId: Long,
    val id: Long,
    val ownerId: Long,
    val recipientIds: List<Long>?,
    val recipients: List<User>?,
    val position: Int,
    val permissionOverwrites: List<PermissionOverwrite>?,
    val userLimit: Int,
    val bitrate: Int,
    val icon: String?,
    val originChannelId: Long,
    val applicationId: Long,
    val nicks: List<ChannelRecipientNick>?,
    val nsfw: Boolean,
    val parentId: Long,
    val memberListId: String?,
    val rateLimitPerUser: Int,
    val defaultAutoArchiveDuration: Int?,
    val rtcRegion: String?,
    val flags: Long?,
    @Transient var guildHashes: GuildHashes?,
    val availableTags: List<ForumTag>?,
    val appliedTags: List<ForumTagId>?,
    /** What a thread-only channel starts on before the viewer overrides it for themselves. */
    val defaultForumLayout: Int? = null,
    val defaultSortOrder: Int? = null,
    val defaultTagSetting: String? = null,
    val threadMetadata: ThreadMetadata?,
    var messageCount: Int?,
    val memberCount: Int?,
    val memberIdsPreview: List<Long>?,
    val member: ThreadMember?,
    val isMessageRequest: Boolean = false,
    val isMessageRequestTimestamp: String? = null,
    val isSpam: Boolean = false,
    /** Copied into each new post as its own `rateLimitPerUser`; existing posts keep theirs. */
    val defaultThreadRateLimitPerUser: Int? = null,
) {
    companion object {
        const val GUILD_TEXT = 0
        const val DM = 1
        const val GUILD_VOICE = 2
        const val GROUP_DM = 3
        const val CATEGORY = 4
        const val GUILD_ANNOUNCEMENT = 5
        const val GUILD_STORE = 6
        const val ANNOUNCEMENT_THREAD = 10
        const val PUBLIC_THREAD = 11
        const val PRIVATE_THREAD = 12
        const val GUILD_STAGE_VOICE = 13
        const val DIRECTORY = 14
        const val GUILD_FORUM = 15
        const val GUILD_MEDIA = 16
        const val FLAGS_NONE = 0L
        const val FLAG_PINNED = 2L
        const val FLAG_REQUIRE_TAG = 16L
        const val FLAG_IS_SPOILER_CHANNEL = 2097152L
    }
}

data class ChannelRecipientNick(val id: Long, val nick: String)

data class ForumTag(
    val id: ForumTagId,
    val name: String,
    val emojiId: Long?,
    val emojiName: String?,
    /** Only a member with `MANAGE_THREADS` may apply this one. */
    val moderated: Boolean = false,
) {
    val customEmojiId: String? = emojiId?.toString()
}
