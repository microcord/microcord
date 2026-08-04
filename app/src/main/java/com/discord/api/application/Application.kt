package com.discord.api.application

import com.discord.api.embeddedactivities.EmbeddedActivityConfig

/** [ThirdPartySku.distributor] value that marks a Play Store listing. */
private const val DISTRIBUTOR_GPLAY = "google_play"

data class Application(
    val id: Long,
    val name: String,
    val description: String?,
    val splash: String?,
    val coverImage: String?,
    val icon: String?,
    val thirdPartySkus: List<ThirdPartySku>?,
    val deeplinkUri: String?,
    val type: ApplicationType?,
    val guildId: Long?,
    val team: Team?,
    val termsOfServiceUrl: String? = null,
    val privacyPolicyUrl: String? = null,
    val bot: com.discord.api.user.User? = null,
    /** Set on a social-layer child application, which the authorized list folds into its parent. */
    val parentId: Long? = null,
    val maxParticipants: Int? = null,
    val embeddedActivityConfig: EmbeddedActivityConfig? = null,
) {
    val launchUri: String
        get() = deeplinkUri?.removeSuffix("/")?.plus("/_discord") ?: "dscd$id://connect/_discord"

    val googlePlayPackageNames: List<String>
        get() = thirdPartySkus.orEmpty().filter { it.distributor == DISTRIBUTOR_GPLAY }.mapNotNull { it.sku }
}

data class ThirdPartySku(val id: String, val sku: String?, val distributor: String?)
data class Team(val id: Long, val icon: String?, val name: String, val ownerUserId: Long)

enum class ApplicationType(val apiValue: Int) {
    UNKNOWN(0),
    GAME(1),
    MUSIC(2),
    TICKETED_EVENTS(3),
    GUILD_ROLE_SUBSCRIPTIONS(4),
}
