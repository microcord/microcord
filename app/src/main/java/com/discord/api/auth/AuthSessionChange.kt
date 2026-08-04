package com.discord.api.auth

/**
 * Payload of `AUTH_SESSION_CHANGE`.
 *
 * A password reset moves the live connection to a new auth session without dropping it. Ignoring
 * this leaves the sessions list offering to revoke the session the user is on.
 */
data class AuthSessionChange(
    val authSessionIdHash: String?,
)
