package com.discord.widgets.hubs

import com.discord.api.hubs.EmailVerification
import com.discord.api.hubs.EmailVerificationCode
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

data class HubAuthenticationState(
    val email: String? = null,
    val resendEmailAsync: RestCallState<EmailVerification> = Default,
    val authenticationAsync: RestCallState<EmailVerificationCode> = Default,
) {
    override fun toString() =
        "HubAuthenticationState(email=${if (email == null) "null" else "REDACTED"}, " +
            "resendEmailAsync=$resendEmailAsync, authenticationAsync=$authenticationAsync)"
}
