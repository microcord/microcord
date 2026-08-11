package com.discord.models.domain

import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.google.gson.annotations.SerializedName

data class ModelUserRelationship(
    val id: Long = 0,
    val type: Int = TYPE_NONE,
    val user: User? = null,
    @SerializedName("user_id") val userId: Long? = null,
    /** Per-friend nickname, shown instead of their display name outside guilds. */
    val nickname: String? = null,
    /**
     * Whether the viewer has ignored this user.
     *
     * Orthogonal to [type] rather than a value of it: a friend or a pending request can be ignored,
     * and the server keeps the flag when the relationship changes.
     */
    @SerializedName("user_ignored") val userIgnored: Boolean = false,
    /** When the relationship started. Absent on friendships that predate the field. */
    val since: UtcDateTime? = null,
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
