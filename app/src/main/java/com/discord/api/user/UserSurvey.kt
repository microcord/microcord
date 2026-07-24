package com.discord.api.user

import com.google.gson.annotations.SerializedName

enum class SurveyGuildRequirements(val value: String) {
    IS_OWNER("is_owner"),
    IS_ADMIN("is_admin"),
    IS_COMMUNITY("is_community"),
    GUILD_SIZE("guild_size"),
    IS_HUB("is_hub"),
    GUILD_PERMISSIONS("guild_permissions"),
}

data class UserSurvey(
    val prompt: String,
    val cta: String,
    val url: String,
    val key: String,
    @SerializedName("guild_requirements") val guildRequirements: List<String>,
    @SerializedName("guild_size") val guildSize: List<Int?>,
    @SerializedName("guild_permissions") val guildPermissions: List<Long>,
)

data class UserSurveyFetchResponse(val survey: UserSurvey?)
