package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable
import com.discord.models.guild.Guild
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

/** Arguments for [WidgetHubAddName]: the hub whose nickname is being set. */
data class HubAddNameArgs(val guildId: Long) : Parcelable {

    constructor(parcel: Parcel) : this(guildId = parcel.readLong())

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(guildId)
    }

    companion object CREATOR : Parcelable.Creator<HubAddNameArgs> {
        override fun createFromParcel(parcel: Parcel) = HubAddNameArgs(parcel)
        override fun newArray(size: Int): Array<HubAddNameArgs?> = arrayOfNulls(size)
    }
}

/**
 * View state of [WidgetHubAddName].
 *
 * [nickname] is seeded from the user's existing guild nickname so re-entering the screen shows what
 * is already set rather than an empty field.
 */
data class AddNameState(
    val nickname: String = "",
    val guild: Guild? = null,
    val changeNicknameAsync: RestCallState<Void?> = Default,
)
