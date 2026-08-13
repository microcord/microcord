package com.discord.api.embeddedactivities

data class EmbeddedActivityOAuthLocationContext(
    val channelId: String,
    val channelType: Int,
    val guildId: String? = null,
)

data class EmbeddedActivityOAuthAuthorizeBody(
    val authorize: Boolean = true,
    val integrationType: Int = USER_INSTALL,
    val locationContext: EmbeddedActivityOAuthLocationContext,
) {
    companion object {
        const val USER_INSTALL = 1
    }
}

data class EmbeddedActivityOAuthAuthorizeResponse(val location: String)

/**
 * Response of `GET oauth2/authorize`, which reports whether this user has already granted the
 * requested scopes. [authorized] is what lets a `prompt=none` request skip the consent screen.
 */
data class EmbeddedActivityOAuthPreauthorization(
    val application: EmbeddedActivityOAuthApplication,
    val authorized: Boolean = false,
    val user: EmbeddedActivityOAuthUser? = null,
)

data class EmbeddedActivityOAuthApplication(
    val id: Long,
    val name: String,
    val description: String = "",
    val icon: String? = null,
    val rpcOrigins: List<String>? = null,
)

data class EmbeddedActivityOAuthUser(
    val id: Long,
    val username: String,
    val discriminator: String = "0000",
    val avatar: String? = null,
    val globalName: String? = null,
    val publicFlags: Int = 0,
)

data class EmbeddedActivityOAuthAuthorization(
    val application: EmbeddedActivityOAuthApplication,
    val scopes: List<String>,
    val expires: String,
    val user: EmbeddedActivityOAuthUser? = null,
)

data class EmbeddedActivityOAuthAuthentication(
    val authorization: EmbeddedActivityOAuthAuthorization,
    val accessToken: String,
) {
    override fun toString() =
        "EmbeddedActivityOAuthAuthentication(authorization=$authorization, accessToken=<redacted>)"
}
