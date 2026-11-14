package com.discord.widgets.channels.permissions

import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember
import com.discord.models.user.User

/**
 * The modern Basic permissions view: whether a channel is private, and who can still get in.
 *
 * A channel is private when its @everyone overwrite denies viewing it. Access is then granted per
 * role or member by an overwrite allowing [accessPermission]; administrators always have it.
 */
internal object BasicChannelPermissions {

    /** Viewing, plus connecting for channels people join with voice. */
    fun accessPermission(channel: Channel): Long =
        if (channel.isGuildVocalChannel()) Permission.VIEW_CHANNEL or Permission.CONNECT else Permission.VIEW_CHANNEL

    fun isPrivate(channel: Channel): Boolean =
        everyoneOverwrite(channel)?.deny?.and(Permission.VIEW_CHANNEL) == Permission.VIEW_CHANNEL

    /** The @everyone overwrite after making [channel] private or public, or null when it ends up empty. */
    fun everyoneOverwriteFor(channel: Channel, private: Boolean): PermissionOverwrite? {
        val current = everyoneOverwrite(channel)
            ?: PermissionOverwrite(channel.guildId, PermissionOverwrite.Type.ROLE, 0L, 0L)
        val view = Permission.VIEW_CHANNEL
        val updated = if (private) {
            current.copy(allow = current.allow and view.inv(), deny = current.deny or view)
        } else {
            current.copy(deny = current.deny and view.inv())
        }
        return updated.takeUnless { it.allow == 0L && it.deny == 0L }
    }

    /**
     * Who can access a private [channel], in the order React Native lists them: administrator roles,
     * then roles let in by an overwrite, then members let in by one. Only overwrites can be removed.
     */
    fun accessItems(
        channel: Channel,
        roles: Collection<GuildRole>,
        users: Map<Long, User>,
        members: Map<Long, GuildMember>,
        ownerId: Long,
        canEdit: Boolean,
    ): List<PermissionOwnerListView.Item> {
        val overwrites = channel.permissionOverwrites.orEmpty()
        fun allows(overwrite: PermissionOverwrite) = overwrite.allow and Permission.VIEW_CHANNEL != 0L
        val allowedRoles = overwrites
            .filter { it.type == PermissionOverwrite.Type.ROLE && it.id != channel.guildId && allows(it) }
            .mapTo(HashSet()) { it.id }
        val (admins, others) = roles
            .filter { it.id != channel.guildId }
            .partition { it.permissions and Permission.ADMINISTRATOR != 0L }
        val notEditable = PermissionOwnerListView.RemoveStatus.CannotRemove(
            PermissionOwnerListView.RemoveStatus.CannotRemove.Reason.HAS_NO_PERMISSION,
        )
        val adminItems = admins.sortedByDescending { it.position }.map {
            PermissionOwnerListView.Item(
                PermissionOwner.Role(it),
                PermissionOwnerListView.RemoveStatus.CannotRemove(
                    PermissionOwnerListView.RemoveStatus.CannotRemove.Reason.IS_ADMINISTRATOR,
                ),
            )
        }
        val roleItems = others.filter { it.id in allowedRoles }.sortedByDescending { it.position }.map {
            PermissionOwnerListView.Item(
                PermissionOwner.Role(it),
                if (canEdit) PermissionOwnerListView.RemoveStatus.CanRemove else notEditable,
            )
        }
        val memberItems = overwrites
            .filter { it.type == PermissionOverwrite.Type.MEMBER && allows(it) }
            .mapNotNull { users[it.id] }
            .sortedBy { it.username.lowercase() }
            .map { user ->
                PermissionOwnerListView.Item(
                    PermissionOwner.Member(user, members[user.id]?.nick, user.id == ownerId),
                    when {
                        user.id == ownerId -> PermissionOwnerListView.RemoveStatus.CannotRemove(
                            PermissionOwnerListView.RemoveStatus.CannotRemove.Reason.IS_GUILD_OWNER,
                        )
                        canEdit -> PermissionOwnerListView.RemoveStatus.CanRemove
                        else -> notEditable
                    },
                )
            }
        return adminItems + roleItems + memberItems
    }

    private fun everyoneOverwrite(channel: Channel): PermissionOverwrite? =
        channel.permissionOverwrites.orEmpty()
            .firstOrNull { it.type == PermissionOverwrite.Type.ROLE && it.id == channel.guildId }
}
