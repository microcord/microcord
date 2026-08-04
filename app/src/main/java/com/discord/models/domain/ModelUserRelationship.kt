package com.discord.models.domain

import com.discord.api.user.User
import com.google.gson.annotations.SerializedName

data class ModelUserRelationship(
    val id: Long = 0,
    val type: Int = TYPE_NONE,
    val user: User? = null,
    @SerializedName("user_id") val userId: Long? = null,
    /** Per-friend nickname, shown instead of their display name outside guilds. */
    val nickname: String? = null,
) {
    fun hydrate(users: Map<Long, User>): ModelUserRelationship =
        if (userId == null) this else copy(user = users[userId], userId = null)

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_FRIEND = 1
        const val TYPE_BLOCKED = 2
        const val TYPE_INVITE_INCOMING = 3
        const val TYPE_INVITE_OUTGOING = 4

        @JvmStatic fun getType(type: Int?) = type ?: TYPE_NONE
        @JvmStatic fun isType(type: Int?, expected: Int) = type == expected
    }
}
