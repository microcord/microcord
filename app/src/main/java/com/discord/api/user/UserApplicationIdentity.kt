package com.discord.api.user

import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

data class UserApplicationIdentities(
    val identities: List<UserApplicationIdentity>? = null,
)

data class UserApplicationIdentity(
    val applicationId: Long,
    val providerIssuedUserId: String,
    val profile: UserApplicationProfile? = null,
    val profiles: List<UserApplicationProfile>? = null,
) {
    fun displayProfile() = profile ?: profiles.orEmpty().firstOrNull()
}

data class UserApplicationProfile(
    val username: String? = null,
    val metadata: String? = null,
    val data: UserApplicationProfileData? = null,
    val dataTrusted: Boolean? = null,
    val connectionVisible: Boolean? = null,
)

data class UserApplicationProfileData(
    val primary: UserApplicationPrimaryData? = null,
    val dynamic: List<UserApplicationDynamicField>? = null,
)

data class UserApplicationPrimaryData(
    val season: String? = null,
    val rankName: String? = null,
    val highestRank: String? = null,
    val featuredPlayedCharacter: String? = null,
    val featuredPlayedCharacterImage: UserApplicationMedia? = null,
    val playtimeHours: Double? = null,
    val totalWins: Long? = null,
    val currentPeriodWins: Long? = null,
    val totalGames: Long? = null,
    val currentPeriodGames: Long? = null,
    val totalKills: Long? = null,
    val currentPeriodKills: Long? = null,
    val totalAssists: Long? = null,
    val currentPeriodAssists: Long? = null,
    val totalDeaths: Long? = null,
    val currentPeriodDeaths: Long? = null,
    val serverName: String? = null,
    val userId: String? = null,
    val unionLevel: String? = null,
    val totalResonators: Long? = null,
    val totalAchievements: Long? = null,
    val totalEchoes: Long? = null,
    val loginDays: Long? = null,
    val dataBankLevel: String? = null,
)

data class UserApplicationMedia(
    val id: Long? = null,
    val url: String? = null,
    val proxyUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val placeholder: String? = null,
    val placeholderVersion: Int? = null,
    val contentType: String? = null,
)

@JsonAdapter(UserApplicationDynamicFieldAdapter::class)
sealed class UserApplicationDynamicField(open val name: String, open val type: Int) {
    data class Text(override val name: String, val value: String) : UserApplicationDynamicField(name, TYPE_TEXT)
    data class Number(override val name: String, val value: Double) : UserApplicationDynamicField(name, TYPE_NUMBER)
    data class Image(override val name: String, val value: UserApplicationMedia) : UserApplicationDynamicField(name, TYPE_IMAGE)
    data class Unknown(override val name: String, override val type: Int) : UserApplicationDynamicField(name, type)

    companion object {
        const val TYPE_TEXT = 1
        const val TYPE_NUMBER = 2
        const val TYPE_IMAGE = 3
    }
}

class UserApplicationDynamicFieldAdapter : TypeAdapter<UserApplicationDynamicField>() {
    override fun read(reader: JsonReader): UserApplicationDynamicField {
        val objectValue = JsonParser.parseReader(reader).asJsonObject
        val name = objectValue["name"]?.asString.orEmpty()
        val type = objectValue["type"]?.asInt ?: 0
        val value = objectValue["value"]
        return when (type) {
            UserApplicationDynamicField.TYPE_TEXT ->
                UserApplicationDynamicField.Text(name, value?.takeUnless { it.isJsonNull }?.asString.orEmpty())
            UserApplicationDynamicField.TYPE_NUMBER ->
                UserApplicationDynamicField.Number(name, value?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0)
            UserApplicationDynamicField.TYPE_IMAGE -> {
                val media = value?.takeIf { it.isJsonObject }?.asJsonObject
                UserApplicationDynamicField.Image(
                    name,
                    UserApplicationMedia(
                        id = media?.get("id")?.takeUnless { it.isJsonNull }?.asLong,
                        url = media?.get("url")?.takeUnless { it.isJsonNull }?.asString,
                        proxyUrl = media?.get("proxy_url")?.takeUnless { it.isJsonNull }?.asString,
                        width = media?.get("width")?.takeUnless { it.isJsonNull }?.asInt,
                        height = media?.get("height")?.takeUnless { it.isJsonNull }?.asInt,
                        placeholder = media?.get("placeholder")?.takeUnless { it.isJsonNull }?.asString,
                        placeholderVersion = media?.get("placeholder_version")?.takeUnless { it.isJsonNull }?.asInt,
                        contentType = media?.get("content_type")?.takeUnless { it.isJsonNull }?.asString,
                    ),
                )
            }
            else -> UserApplicationDynamicField.Unknown(name, type)
        }
    }

    override fun write(writer: JsonWriter, value: UserApplicationDynamicField?) =
        throw UnsupportedOperationException("User application identities are read-only")
}
