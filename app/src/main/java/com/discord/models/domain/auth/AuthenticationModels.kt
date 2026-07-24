package com.discord.models.domain.auth

import com.discord.api.auth.RequiredAction

data class ModelUserSettingsBootstrap(
    val theme: String?,
    val locale: String?,
)

data class ModelLoginResult(
    val mfa: Boolean = false,
    val ticket: String?,
    val token: String?,
    val userSettings: ModelUserSettingsBootstrap?,
    val requiredActions: List<RequiredAction>?,
) {
    override fun toString(): String =
        "ModelLoginResult(mfa=$mfa, ticket=<redacted>, token=<redacted>, userSettings=$userSettings, requiredActions=$requiredActions)"
}
