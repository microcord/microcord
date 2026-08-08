package com.discord.widgets.guilds.list

import com.discord.api.channel.Channel
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.models.guild.Guild
import com.discord.widgets.channels.list.WidgetChannelListUnreads

/**
 * One row of the guild sidebar.
 *
 * [itemId] is the RecyclerView stable id. Guild, folder and DM rows carry the real snowflake;
 * the fixed chrome rows use the reserved negative sentinels declared in [GuildListItem.Companion],
 * which is why no folder id may ever be negative.
 *
 * The three rows that can carry unread state implement [WidgetChannelListUnreads.UnreadItem], which
 * is what drives the floating "new messages ↑/↓" pill over the sidebar.
 */
sealed class GuildListItem(val itemId: Long) {

    /** The "Direct Messages" avatar pinned to the top of the sidebar. */
    data class FriendsItem(val isSelected: Boolean) : GuildListItem(ID_FRIENDS_LIST)

    /** Hairline between the DM block and the guild block, and above the help row for new users. */
    object DividerItem : GuildListItem(ID_DIVIDER)

    /** A DM or group DM that currently has unread mentions. */
    data class PrivateChannelItem(
        val channel: Channel,
        override val mentionCount: Int,
    ) : GuildListItem(channel.id), WidgetChannelListUnreads.UnreadItem {
        override val isUnread: Boolean get() = mentionCount > 0
    }

    data class GuildItem(
        val guild: Guild,
        override val mentionCount: Int,
        /** Every mention here came from an all-messages channel, so the badge is drawn muted. */
        val isMentionLowImportance: Boolean,
        val isLurkingGuild: Boolean,
        override val isUnread: Boolean,
        val isSelected: Boolean,
        /** Non-null when the row is rendered *inside* an expanded folder. */
        val folderId: Long?,
        val isConnectedToVoice: Boolean = false,
        val hasOngoingApplicationStream: Boolean = false,
        /** True while a drag hovers this row and dropping would create a new folder. */
        val isTargetedForFolderCreation: Boolean = false,
        /** Null for top-level guilds; drives the rounded bottom of the folder capsule. */
        val isLastGuildInFolder: Boolean? = null,
        val applicationStatus: ApplicationStatus? = null,
        val isPendingGuild: Boolean = false,
        val hasActiveStageChannel: Boolean = false,
        val isConnectedToStageChannel: Boolean = false,
        val hasActiveScheduledEvent: Boolean = false,
    ) : GuildListItem(guild.id), WidgetChannelListUnreads.UnreadItem

    /** Aggregated "N servers are unavailable" row shown during a partial outage. */
    data class UnavailableItem(val unavailableGuildCount: Int) : GuildListItem(ID_UNIDENTIFIED)

    /** The green "+" create-a-server button. */
    object CreateItem : GuildListItem(ID_CREATE)

    data class FolderItem(
        val folderId: Long,
        val color: Int?,
        val name: String?,
        val isOpen: Boolean,
        val guilds: List<Guild>,
        val isAnyGuildSelected: Boolean,
        val isAnyGuildConnectedToVoice: Boolean,
        val isAnyGuildConnectedToStageChannel: Boolean,
        override val mentionCount: Int,
        val isMentionLowImportance: Boolean,
        override val isUnread: Boolean,
        /** True while a drag hovers this folder and dropping would add the guild to it. */
        val isTargetedForFolderAddition: Boolean = false,
    ) : GuildListItem(folderId), WidgetChannelListUnreads.UnreadItem {
        /**
         * The synthetic folder holding guilds whose join request is still being reviewed. It is
         * never reorderable and never a drop target.
         */
        val isPendingGuildsFolder: Boolean = folderId == ID_PENDING_GUILDS_FOLDER
    }

    /** The "?" row, shown only to accounts young enough to still be in the new-user flow. */
    object HelpItem : GuildListItem(ID_HELP)

    /** Bottom padding sized to the tab bar so the last guild is never hidden behind it. */
    object SpaceItem : GuildListItem(ID_BOTTOM_NAV_SPACE)

    /** Student-hub discovery entry point. */
    object HubItem : GuildListItem(ID_HUB)

    companion object {
        const val TYPE_FRIENDS = 0
        const val TYPE_DIVIDER = 1
        const val TYPE_DM = 2
        const val TYPE_GUILD = 3
        const val TYPE_UNAVAILABLE = 4
        const val TYPE_CREATE = 5
        const val TYPE_FOLDER = 6
        const val TYPE_HELP = 7
        const val TYPE_BOTTOM_NAV_SPACE = 8
        const val TYPE_HUB = 9

        private const val ID_UNIDENTIFIED = -1L
        private const val ID_FRIENDS_LIST = -2L
        private const val ID_CREATE = -3L
        private const val ID_DIVIDER = -4L
        private const val ID_HELP = -5L
        private const val ID_BOTTOM_NAV_SPACE = -6L
        const val ID_PENDING_GUILDS_FOLDER = -7L
        private const val ID_HUB = -8L
    }
}
