package com.discord.api.application

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
) {
    val launchUri: String
        get() = deeplinkUri?.removeSuffix("/")?.plus("/_discord") ?: "dscd$id://connect/_discord"

    val googlePlayPackageNames: List<String>
        get() = thirdPartySkus.orEmpty().filter { it.distributor == "google_play" }.mapNotNull { it.sku }
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
