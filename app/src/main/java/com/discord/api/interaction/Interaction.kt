package com.discord.api.interaction

import com.discord.api.user.User

data class Interaction(
    val id: Long? = null,
    val type: Int? = null,
    val name: String? = null,
    val user: User? = null,
    val member: Member? = null,
) {
    companion object {
        const val TYPE_INTERACTION_PING = 1
        const val TYPE_INTERACTION_APPLICATION_COMMAND = 2
    }
}

data class Member(
    val roles: List<Long>,
    val joinedAt: String,
    val mute: Boolean,
    val deaf: Boolean,
)
