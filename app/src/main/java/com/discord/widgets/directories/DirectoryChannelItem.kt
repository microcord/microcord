package com.discord.widgets.directories

import androidx.annotation.StringRes
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

/**
 * A row in a directory list — the directory channel's category pages and the directory search
 * results share one adapter, so they share one row type.
 *
 * The reference declares [ViewType] and switches on `ordinal`; the ordinals are load bearing because
 * they are also the recycler's view types. They are `Item = 0`, `AddServer = 1`, `SectionHeader = 2`
 * (verified at bytecode level — the Vineflower source order is alphabetical and misleading, the same
 * trap `CallStatus` set).
 */
sealed class DirectoryChannelItem(val viewType: ViewType) : MGRecyclerDataPayload {

    override val type: Int get() = viewType.ordinal

    /** A single listed server. */
    data class DirectoryItem(val directoryEntryData: DirectoryEntryData) :
        DirectoryChannelItem(ViewType.Item) {
        override val key: String get() = "directory-entry-${directoryEntryData.entry.guild.id}"
    }

    /** The "Add Server" call to action, shown only to members who may add one. */
    object AddServer : DirectoryChannelItem(ViewType.AddServer) {
        override val key: String get() = "directory-add-server"
    }

    /** A "Newest"/"All Servers" divider on the directory home page. */
    data class SectionHeader(@StringRes val textRes: Int) : DirectoryChannelItem(ViewType.SectionHeader) {
        override val key: String get() = "directory-section-$textRes"
    }

    enum class ViewType {
        Item,
        AddServer,
        SectionHeader;

        companion object {
            fun findType(type: Int): ViewType = values()[type]
        }
    }
}
