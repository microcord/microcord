package com.discord.widgets.directories

import com.discord.api.directory.DirectoryEntryGuild

/**
 * Everything a directory row can ask its host screen to do.
 *
 * Both hosts (the directory channel's category pages and the directory search screen) implement it;
 * only [onAddServerClicked] has a default, because the search screen never shows that row.
 */
interface DirectoryChannelItemClickInterface {

    /** The "Add Server" row was tapped. */
    fun onAddServerClicked() = Unit

    /** The viewer wants to join [guildId], which is listed in [channelId]. */
    fun onEntryClicked(guildId: Long, channelId: Long)

    /** The row itself was tapped, away from either button: preview [guildId] if it allows it. */
    fun onEntryPreviewClicked(guildId: Long, channelId: Long) = Unit

    /** The viewer already belongs to [guildId] and wants to open it. */
    fun onGoToGuildClicked(guildId: Long)

    /**
     * A row was long-pressed. [isServerOwner] selects the owner menu (edit/remove) over the
     * everyone-else menu (report).
     */
    fun onOverflowClicked(directoryEntry: DirectoryEntryGuild, channelId: Long, isServerOwner: Boolean)
}
