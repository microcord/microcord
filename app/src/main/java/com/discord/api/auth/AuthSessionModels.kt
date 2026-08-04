package com.discord.api.auth

/**
 * One signed-in session, as listed by `GET auth/sessions`.
 *
 * [idHash] is what `auth/sessions/logout` revokes, and matches the gateway's
 * `auth_session_id_hash`, which is how the current session is picked out of the list.
 */
data class AuthSession(
    val idHash: String,
    val approxLastUsedTime: String?,
    val clientInfo: ClientInfo?,
) {
    data class ClientInfo(
        val os: String?,
        val platform: String?,
        val location: String?,
        /** Shown in place of [location] when the server could not resolve one. */
        val ip: String?,
    )
}

data class AuthSessionsResponse(
    val userSessions: List<AuthSession>?,
)
