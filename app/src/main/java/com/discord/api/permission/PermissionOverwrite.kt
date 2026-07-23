package com.discord.api.permission

import com.google.gson.annotations.SerializedName

data class PermissionOverwrite(
    val id: Long,
    val type: Type,
    val allow: Long,
    val deny: Long,
) {
    enum class Type {
        @SerializedName("0") ROLE,
        @SerializedName("1") MEMBER,
    }
}
