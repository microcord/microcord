package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.channel.Channel
import com.discord.api.channel.isHQDirectoryChannel
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.models.hubs.DirectoryEntryCategory
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

/**
 * Arguments for [WidgetHubDescription], which both adds a server to a hub and edits an entry already
 * in one.
 *
 * [isEditing] flips the title, the button label and the REST verb (PATCH instead of POST).
 * [isNewGuild] records that the guild was created moments ago inside the add-server flow, so the
 * confirmation dialog can say so.
 */
data class HubDescriptionArgs(
    val guildId: Long,
    val channelId: Long,
    val isEditing: Boolean,
    val hubName: String,
    val isNewGuild: Boolean = false,
    val description: String? = null,
    val primaryCategoryId: Int? = null,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        guildId = parcel.readLong(),
        channelId = parcel.readLong(),
        isEditing = parcel.readInt() != 0,
        hubName = parcel.readString().orEmpty(),
        isNewGuild = parcel.readInt() != 0,
        description = parcel.readString(),
        primaryCategoryId = if (parcel.readInt() != 0) parcel.readInt() else null,
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(guildId)
        parcel.writeLong(channelId)
        parcel.writeInt(if (isEditing) 1 else 0)
        parcel.writeString(hubName)
        parcel.writeInt(if (isNewGuild) 1 else 0)
        parcel.writeString(description)
        if (primaryCategoryId != null) {
            parcel.writeInt(1)
            parcel.writeInt(primaryCategoryId)
        } else {
            parcel.writeInt(0)
        }
    }

    companion object CREATOR : Parcelable.Creator<HubDescriptionArgs> {
        override fun createFromParcel(parcel: Parcel) = HubDescriptionArgs(parcel)
        override fun newArray(size: Int): Array<HubDescriptionArgs?> = arrayOfNulls(size)
    }
}

/** The server that was listed, and the hub it was listed in. Returned to the caller as a result. */
data class DescriptionResult(
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

    companion object CREATOR : Parcelable.Creator<DescriptionResult> {
        override fun createFromParcel(parcel: Parcel) = DescriptionResult(parcel)
        override fun newArray(size: Int): Array<DescriptionResult?> = arrayOfNulls(size)
    }
}

/** View state of [WidgetHubDescription]. */
data class HubDescriptionState(
    val channel: Channel? = null,
    val primaryCategoryId: Int? = null,
    val guildName: String = "",
    val addServerAsync: RestCallState<DirectoryEntryGuild> = Default,
) {
    /**
     * The picked category, resolved against the category set that belongs to this directory.
     *
     * The school and internal-HQ category lists deliberately reuse the same numeric keys, so the key
     * alone is ambiguous — it only means something once paired with the directory channel.
     */
    val selectedCategory: DirectoryEntryCategory?
        get() = primaryCategoryId?.let { key ->
            DirectoryEntryCategory.findByKey(key, channel?.isHQDirectoryChannel() == true)
        }
}
