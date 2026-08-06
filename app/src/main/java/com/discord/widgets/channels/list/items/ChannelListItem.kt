package com.discord.widgets.channels.list.items

import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

/**
 * A single row in the channels sidebar.
 *
 * [type] is returned verbatim as the RecyclerView view type, so the constants below are the
 * contract between the model builder ([com.discord.widgets.channels.list.WidgetChannelListModel])
 * and the adapter ([com.discord.widgets.channels.list.WidgetChannelsListAdapter]). The numbering
 * is sparse because types 4, 5 and 10 were retired; do not renumber.
 */
interface ChannelListItem : MGRecyclerDataPayload {

    /**
     * Rows that carry no identity of their own (MFA notice, add-server, audience count,
     * invite, role-subscription overview) are keyed by their type alone, so at most one of
     * each can exist in a list.
     */
    override val key: String get() = type.toString()

    companion object {
        const val TYPE_CHANNEL = 0
        const val TYPE_CHANNEL_VOICE = 1
        const val TYPE_CHANNEL_VOICE_USER = 2
        const val TYPE_CHANNEL_PRIVATE = 3
        const val TYPE_HEADER = 6
        const val TYPE_MFA_NOTICE = 7
        const val TYPE_CATEGORY = 8
        const val TYPE_INVITE = 9
        const val TYPE_BOTTOM_NAV_SPACE = 11
        const val TYPE_THREAD = 12
        const val TYPE_CHANNEL_STAGE_VOICE = 13
        const val TYPE_STAGE_EVENTS_SEPARATOR = 14
        const val TYPE_ACTIVE_EVENT = 15
        const val TYPE_STAGE_AUDIENCE_COUNT = 16
        const val TYPE_DIRECTORY = 17
        const val TYPE_ADD_SERVER = 18
        const val TYPE_GUILD_SCHEDULED_EVENTS = 19
        const val TYPE_HUB_INVITE = 20
        const val TYPE_GUILD_ROLE_SUBSCRIPTIONS_OVERVIEW = 21
        const val TYPE_GUILD_JOIN_REQUEST = 22
        const val TYPE_GUILD_ONBOARDING = 23
    }
}
