package com.discord.utilities.permissions

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember

object PermissionUtils {
    @JvmStatic
    fun can(permission: Long, permissions: Long?): Boolean = ((permissions ?: Permission.NONE) and permission) == permission

    @JvmStatic
    fun computeGuildPermissions(
        userId: Long,
        guildId: Long,
        ownerId: Long,
        member: GuildMember?,
        roles: Map<Long, GuildRole>?,
    ): Long {
        if (userId == ownerId) return Permission.ALL
        var permissions = roles?.get(guildId)?.permissions ?: Permission.NONE
        member?.getRoles().orEmpty().forEach { roleId -> permissions = permissions or (roles?.get(roleId)?.permissions ?: 0L) }
        return if (can(Permission.ADMINISTRATOR, permissions)) Permission.ALL else permissions
    }

    @JvmStatic
    fun computeChannelPermissions(
        userId: Long,
        guildId: Long,
        ownerId: Long,
        member: GuildMember?,
        roles: Map<Long, GuildRole>?,
        channel: Channel,
        parentChannel: Channel? = null,
    ): Long {
        if (channel.type == Channel.DM || channel.type == Channel.GROUP_DM) return Permission.ALL
        var permissions = computeGuildPermissions(userId, guildId, ownerId, member, roles)
        if (permissions == Permission.ALL) return permissions

        val overwrites = if (channel.type in Channel.ANNOUNCEMENT_THREAD..Channel.PRIVATE_THREAD) {
            parentChannel?.permissionOverwrites.orEmpty()
        } else channel.permissionOverwrites.orEmpty()
        overwrites.firstOrNull { it.type == PermissionOverwrite.Type.ROLE && it.id == guildId }?.let {
            permissions = applyOverwrite(permissions, it)
        }

        val memberRoles = member?.getRoles().orEmpty().toSet()
        var deny = Permission.NONE
        var allow = Permission.NONE
        overwrites.forEach {
            if (it.type == PermissionOverwrite.Type.ROLE && it.id in memberRoles) {
                deny = deny or it.deny
                allow = allow or it.allow
            }
        }
        permissions = (permissions and deny.inv()) or allow

        overwrites.firstOrNull { it.type == PermissionOverwrite.Type.MEMBER && it.id == userId }?.let {
            permissions = applyOverwrite(permissions, it)
        }
        if (channel.type in Channel.ANNOUNCEMENT_THREAD..Channel.PRIVATE_THREAD) {
            permissions = if (can(Permission.SEND_MESSAGES_IN_THREADS, permissions)) {
                permissions or Permission.SEND_MESSAGES
            } else {
                permissions and Permission.SEND_MESSAGES.inv()
            }
            if (channel.type == Channel.PRIVATE_THREAD && channel.member == null && !can(Permission.MANAGE_THREADS, permissions)) {
                return Permission.NONE
            }
        }
        if (member?.isCommunicationDisabled() == true) permissions = permissions and Permission.COMMUNICATIONS_DISABLED_DEFAULT
        return permissions
    }

    @JvmStatic
    fun hasAccess(channel: Channel, permissions: Long?): Boolean {
        if (channel.type == Channel.DM || channel.type == Channel.GROUP_DM) return true
        val required = if (channel.type == Channel.GUILD_VOICE || channel.type == Channel.GUILD_STAGE_VOICE) {
            Permission.VIEW_CHANNEL or Permission.CONNECT
        } else {
            Permission.VIEW_CHANNEL
        }
        return can(required, permissions)
    }

    private fun applyOverwrite(permissions: Long, overwrite: PermissionOverwrite) =
        (permissions and overwrite.deny.inv()) or overwrite.allow
}
