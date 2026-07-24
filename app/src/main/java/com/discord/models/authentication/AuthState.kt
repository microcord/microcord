package com.discord.models.authentication

import com.discord.api.auth.RequiredAction

data class AuthState(
    val token: String,
    val requiredActions: Set<RequiredAction>,
) {
    override fun toString(): String = "AuthState(token=<redacted>, requiredActions=$requiredActions)"

    companion object {
        fun from(token: String?, requiredActions: Set<RequiredAction>): AuthState? =
            token?.let { AuthState(it, requiredActions) }

        fun isUpdatePasswordRequired(authState: AuthState?): Boolean =
            authState?.requiredActions?.contains(RequiredAction.UPDATE_PASSWORD) == true
    }
}
