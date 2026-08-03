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

data class EmbeddedActivityOAuthApplication(
    val id: Long,
    val name: String,
    val icon: String? = null,
)

data class EmbeddedActivityOAuthUser(
    val id: Long,
    val username: String,
    val discriminator: String = "0000",
    val avatar: String? = null,
    val globalName: String? = null,
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
