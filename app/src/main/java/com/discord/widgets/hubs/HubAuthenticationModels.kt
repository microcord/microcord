package com.discord.widgets.hubs

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.hubs.EmailVerification
import com.discord.api.hubs.EmailVerificationCode
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

/**
 * Arguments for [WidgetHubAuthentication].
 *
 * [guildId] is null when the email matched exactly one hub — the server already knows which one — and
 * set when the user picked a hub out of several on [WidgetHubDomains].
 */
data class HubAuthenticationArgs(
    val email: String = "",
    val guildId: Long? = null,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        email = parcel.readString().orEmpty(),
        guildId = if (parcel.readInt() != 0) parcel.readLong() else null,
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(email)
        if (guildId != null) {
            parcel.writeInt(1)
            parcel.writeLong(guildId)
        } else {
            parcel.writeInt(0)
        }
    }

    override fun toString(): String = "HubAuthenticationArgs(guildId=$guildId, email=<redacted>)"

    companion object CREATOR : Parcelable.Creator<HubAuthenticationArgs> {
        override fun createFromParcel(parcel: Parcel) = HubAuthenticationArgs(parcel)
        override fun newArray(size: Int): Array<HubAuthenticationArgs?> = arrayOfNulls(size)
    }
}

/** The hub the redeemed verification code joined the user to. */
data class AuthenticationResult(val guildId: Long) : MultipleDomainResult {

    constructor(parcel: Parcel) : this(guildId = parcel.readLong())

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(guildId)
    }

    companion object CREATOR : Parcelable.Creator<AuthenticationResult> {
        override fun createFromParcel(parcel: Parcel) = AuthenticationResult(parcel)
        override fun newArray(size: Int): Array<AuthenticationResult?> = arrayOfNulls(size)
    }
}

/**
 * View state of [WidgetHubAuthentication].
 *
 * The two requests are tracked separately because they surface differently: resending shows a toast
 * and leaves the screen alone, redeeming the code either closes the screen or reports a bad code.
 */
data class HubAuthenticationState(
    val email: String? = null,
    val resendEmailAsync: RestCallState<EmailVerification> = Default,
    val authenticationAsync: RestCallState<EmailVerificationCode> = Default,
) {
    /**
     * Redacts [email]. A `data class` would otherwise print the user's address, and view state is
     * exactly what ends up in a crash report or a state-transition log.
     */
    override fun toString(): String =
        "HubAuthenticationState(email=${if (email == null) "null" else "REDACTED"}, " +
            "resendEmailAsync=$resendEmailAsync, authenticationAsync=$authenticationAsync)"
}
