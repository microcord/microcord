package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.hubs.WaitlistSignup
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

/**
 * Result of one of the screens the multi-domain email flow can push.
 *
 * [WidgetHubEmailFlow] starts [WidgetHubDomains] / [WidgetHubWaitlist] for a result and switches on
 * the concrete type: a waitlist signup keeps the user on the flow with the "you're on the list" page,
 * an authentication success selects the guild and closes it.
 */
interface MultipleDomainResult : Parcelable

/** Arguments for [WidgetHubWaitlist]: the school email the waitlist entry is filed under. */
data class HubWaitlistArgs(val email: String) : Parcelable {

    constructor(parcel: Parcel) : this(email = parcel.readString().orEmpty())

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(email)
    }

    override fun toString(): String = "HubWaitlistArgs(email=<redacted>)"

    companion object CREATOR : Parcelable.Creator<HubWaitlistArgs> {
        override fun createFromParcel(parcel: Parcel) = HubWaitlistArgs(parcel)
        override fun newArray(size: Int): Array<HubWaitlistArgs?> = arrayOfNulls(size)
    }
}

/** The school the user was put on the waitlist for. */
data class HubWaitlistResult(val school: String) : MultipleDomainResult {

    constructor(parcel: Parcel) : this(school = parcel.readString().orEmpty())

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(school)
    }

    companion object CREATOR : Parcelable.Creator<HubWaitlistResult> {
        override fun createFromParcel(parcel: Parcel) = HubWaitlistResult(parcel)
        override fun newArray(size: Int): Array<HubWaitlistResult?> = arrayOfNulls(size)
    }
}

/** View state of [WidgetHubWaitlist] — the signup request and nothing else. */
data class HubWaitlistState(
    val waitlistAsync: RestCallState<WaitlistSignup> = Default,
)
