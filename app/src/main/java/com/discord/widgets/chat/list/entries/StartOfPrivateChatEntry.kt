package com.discord.widgets.chat.list.entries

import com.discord.api.user.AvatarDecorationData
import com.discord.models.guild.Guild

/** Header row for a direct or group-direct message. */
data class StartOfPrivateChatEntry(
    val channelId: Long,
    val channelName: String,
    val channelType: Int,
    val channelIcon: String?,
    val isSystemDM: Boolean,
    val mutualGuilds: List<Guild>,
    val avatarDecoration: AvatarDecorationData? = null,
) : ChatListEntry() {
    override val type: Int = ChatListEntry.START_OF_PRIVATE_CHAT
    override val key: String = "${ChatListEntry.START_OF_PRIVATE_CHAT}$channelId"
}
