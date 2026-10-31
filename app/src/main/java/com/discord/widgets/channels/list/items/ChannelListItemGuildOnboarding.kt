package com.discord.widgets.channels.list.items

/**
 * The Channels & Roles row. RN titles it Browse Channels when the server has no onboarding
 * prompts, since the same screen then only browses channels.
 */
data class ChannelListItemGuildOnboarding(val browseOnly: Boolean) : ChannelListItem {
    override val type = ChannelListItem.TYPE_GUILD_ONBOARDING
}
