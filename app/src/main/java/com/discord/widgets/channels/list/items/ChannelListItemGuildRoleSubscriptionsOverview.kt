package com.discord.widgets.channels.list.items

/** Entry point to the guild role-subscription tiers. At most one per list. */
object ChannelListItemGuildRoleSubscriptionsOverview : ChannelListItem {
    override val type: Int = ChannelListItem.TYPE_GUILD_ROLE_SUBSCRIPTIONS_OVERVIEW
    override val key: String = type.toString()
}
