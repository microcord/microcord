package com.discord.widgets.hubs

import com.discord.api.hubs.EmailVerification
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

data class HubEmailState(
    val email: String? = null,
    val selectedGuildId: Long? = null,
    val waitlistResult: HubWaitlistResult? = null,
    val verifyEmailAsync: RestCallState<EmailVerification> = Default,
) {
    val hasMultipleDomains: Boolean by lazy { verifyEmailAsync()?.guildsInfo?.isNotEmpty() == true }

    override fun toString() =
        "HubEmailState(email=${if (email == null) "null" else "REDACTED"}, " +
            "selectedGuildId=$selectedGuildId, waitlistResult=$waitlistResult, " +
            "verifyEmailAsync=$verifyEmailAsync)"
}
