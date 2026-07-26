package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.models.guild.Guild

/** Arguments for [WidgetHubAddServerConfirmationDialog]. */
data class AddServerConfirmationArgs(
    val guildId: Long,
    val hubName: String,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        guildId = parcel.readLong(),
        hubName = parcel.readString().orEmpty(),
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(guildId)
        parcel.writeString(hubName)
    }

    companion object CREATOR : Parcelable.Creator<AddServerConfirmationArgs> {
        override fun createFromParcel(parcel: Parcel) = AddServerConfirmationArgs(parcel)
        override fun newArray(size: Int): Array<AddServerConfirmationArgs?> = arrayOfNulls(size)
    }
}

/**
 * View state of [WidgetHubAddServer].
 *
 * [selectedIndex] is the segmented-control tab and is the one field the store update must not
 * clobber: the store rebuilds the two guild lists constantly, and the reference explicitly preserves
 * the tab across those rebuilds.
 *
 * [addedGuilds] and [addedDirectories] describe the same servers from two angles — the guild for
 * rendering the row, the directory entry for the "edit / remove" sheet the row opens.
 */
data class HubAddServerState(
    val hubName: String = "",
    val directoryChannelId: Long? = null,
    val selectedIndex: Int = 0,
    val selectableGuilds: List<Guild> = emptyList(),
    val addedGuilds: List<Guild> = emptyList(),
    val addedDirectories: List<DirectoryEntryGuild> = emptyList(),
)
