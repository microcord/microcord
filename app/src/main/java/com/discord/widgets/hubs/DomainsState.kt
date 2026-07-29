package com.discord.widgets.hubs

import com.discord.api.hubs.EmailVerification
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

data class DomainsState(
    val selectedGuildId: Long? = null,
    val verifyEmailAsync: RestCallState<EmailVerification> = Default,
)
