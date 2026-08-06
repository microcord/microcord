package com.discord.api.user

import java.io.Serializable

/** Pinned on the account's first purchase; payouts follow it. */
data class UserStoreCountry(
    val country: String,
    val setAt: String? = null,
    val isLocked: Boolean = false,
) : Serializable
