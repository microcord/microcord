package com.discord.api.message.allowedmentions

import com.google.gson.annotations.SerializedName

data class MessageAllowedMentions(
    val parse: List<MessageAllowedMentionsTypes>? = null,
    val users: List<Long>? = null,
    val roles: List<Long>? = null,
    val repliedUser: Boolean? = null,
)

enum class MessageAllowedMentionsTypes(val apiStringRepresentation: String) {
    @SerializedName("users") USERS("users"),
    @SerializedName("roles") ROLES("roles"),
    @SerializedName("everyone") EVERYONE("everyone"),
    ;

    fun serialize() = apiStringRepresentation
}
