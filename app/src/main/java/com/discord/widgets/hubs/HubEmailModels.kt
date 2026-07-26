package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.hubs.EmailVerification
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

/**
 * Where the school-email prompt was opened from. Selects the wording of the header and subheader,
 * and whether declining continues into guild creation.
 */
enum class HubEmailEntryPoint {
    Default,
    Invite,
    Onboarding,
}

/**
 * Arguments for [WidgetHubEmailFlow].
 *
 * [guildName] and [guildMemberCount] are only read for [HubEmailEntryPoint.Invite], where the header
 * reads "Join N others in the Student Hub for X".
 */
data class HubEmailArgs(
    val guildName: String = "",
    val guildMemberCount: Int = 0,
    val entryPoint: HubEmailEntryPoint = HubEmailEntryPoint.Default,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        guildName = parcel.readString().orEmpty(),
        guildMemberCount = parcel.readInt(),
        entryPoint = parcel.readString()?.let(::enumValueOfOrDefault) ?: HubEmailEntryPoint.Default,
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(guildName)
        parcel.writeInt(guildMemberCount)
        parcel.writeString(entryPoint.name)
    }

    companion object CREATOR : Parcelable.Creator<HubEmailArgs> {
        override fun createFromParcel(parcel: Parcel) = HubEmailArgs(parcel)
        override fun newArray(size: Int): Array<HubEmailArgs?> = arrayOfNulls(size)
    }
}

/**
 * The reference calls `Enum.valueOf` directly, which throws if a future build drops a value from a
 * parcel written by an older one. Unknown names fall back to the default entry point instead.
 */
private fun enumValueOfOrDefault(name: String): HubEmailEntryPoint =
    HubEmailEntryPoint.values().firstOrNull { it.name == name } ?: HubEmailEntryPoint.Default

/**
 * View state of [WidgetHubEmailFlow].
 *
 * The screen is a three-way flip driven entirely by [verifyEmailAsync] and [waitlistResult]:
 * the input page while neither has produced anything, the confirmation page once the lookup found a
 * matching guild, and the waitlist page once the lookup found none (or the waitlist was joined).
 */
data class HubEmailState(
    val email: String? = null,
    val selectedGuildId: Long? = null,
    val waitlistResult: HubWaitlistResult? = null,
    val verifyEmailAsync: RestCallState<EmailVerification> = Default,
) {
    /** The email matched more than one hub, so the user has to pick one. */
    val hasMultipleDomains: Boolean by lazy {
        verifyEmailAsync()?.guildsInfo?.isNotEmpty() == true
    }
}
