package com.discord.widgets.channels.permissions

import com.discord.api.role.GuildRole
import com.discord.models.user.User
import java.io.Serializable

sealed class PermissionOwner(val entityId: Long) : Serializable {
    data class Role(val role: GuildRole) : PermissionOwner(role.id)
    data class Member(
        val user: User,
        val nickname: String?,
        val isOwner: Boolean,
    ) : PermissionOwner(user.id)
}
