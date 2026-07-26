package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.hubs.EmailVerification
import com.discord.api.hubs.GuildInfo
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

/**
 * One hub the submitted school email is eligible for.
 *
 * A parcelable projection of [GuildInfo], because the list travels to [WidgetHubDomains] in the
 * fragment arguments rather than being re-fetched.
 */
data class DomainGuildInfo(
    val icon: String?,
    val id: Long,
    val name: String,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        icon = parcel.readString(),
        id = parcel.readLong(),
        name = parcel.readString().orEmpty(),
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(icon)
        parcel.writeLong(id)
        parcel.writeString(name)
    }

    companion object CREATOR : Parcelable.Creator<DomainGuildInfo> {
        override fun createFromParcel(parcel: Parcel) = DomainGuildInfo(parcel)
        override fun newArray(size: Int): Array<DomainGuildInfo?> = arrayOfNulls(size)

        @JvmStatic
        fun from(guildInfo: GuildInfo): DomainGuildInfo =
            DomainGuildInfo(guildInfo.icon, guildInfo.id, guildInfo.name)
    }
}

/** Arguments for [WidgetHubDomains] and [WidgetHubDomainSearch]. */
data class HubDomainArgs(
    val email: String,
    val guildInfos: List<DomainGuildInfo>,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(email)
        parcel.writeInt(guildInfos.size)
        guildInfos.forEach { it.writeToParcel(parcel, 0) }
    }

    override fun toString(): String = "HubDomainArgs(guildInfos=$guildInfos, email=<redacted>)"

    companion object CREATOR : Parcelable.Creator<HubDomainArgs> {
        override fun createFromParcel(parcel: Parcel): HubDomainArgs {
            val email = parcel.readString().orEmpty()
            val size = parcel.readInt()
            val guildInfos = ArrayList<DomainGuildInfo>(size.coerceAtLeast(0))
            repeat(size) { guildInfos.add(DomainGuildInfo.createFromParcel(parcel)) }
            return HubDomainArgs(email, guildInfos)
        }

        override fun newArray(size: Int): Array<HubDomainArgs?> = arrayOfNulls(size)
    }
}

/**
 * View state shared by [WidgetHubDomains] and [WidgetHubDomainSearch] — they run one activity-scoped
 * view model so a pick made in the search sheet lands on the list behind it.
 *
 * Neither screen reads the response body: they only need to know that re-submitting the email against
 * the picked guild succeeded before pushing the code screen. (The reference erased the type parameter
 * to `Any` for that reason; the real response type is kept here since it costs nothing.)
 */
data class DomainsState(
    val selectedGuildId: Long? = null,
    val verifyEmailAsync: RestCallState<EmailVerification> = Default,
)
