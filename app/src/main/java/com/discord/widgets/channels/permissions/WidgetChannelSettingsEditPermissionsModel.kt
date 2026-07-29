package com.discord.widgets.channels.permissions

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import rx.Observable

sealed class WidgetChannelSettingsEditPermissionsModel(
    open val channel: Channel,
    open val targetId: Long,
    val type: Int,
) {
    data class ModelForUser(
        override val channel: Channel,
        val myPermissionsForChannel: Long,
        val isMe: Boolean,
        val user: User,
        val guildMember: GuildMember?,
    ) : WidgetChannelSettingsEditPermissionsModel(channel, user.id, PermissionEditor.TYPE_USER)

    data class ModelForRole(
        override val channel: Channel,
        val myPermissionsForChannel: Long,
        val guildRole: GuildRole,
        override val targetId: Long,
        val myPermissionsWithRoleNeutral: Long,
        val myPermissionsWithRoleDenied: Long,
        val meHasRole: Boolean,
        val isEveryoneRole: Boolean,
    ) : WidgetChannelSettingsEditPermissionsModel(channel, targetId, PermissionEditor.TYPE_ROLE) {
        fun canDenyRolePermission(permission: Long) =
            myPermissionsWithRoleDenied and permission == myPermissionsForChannel and permission
        fun canNeutralizeRolePermission(permission: Long) =
            myPermissionsWithRoleNeutral and permission == myPermissionsForChannel and permission
    }

    companion object {
        fun get(guildId: Long, channelId: Long, targetId: Long, type: Int): Observable<WidgetChannelSettingsEditPermissionsModel?> {
            val stores = StoreStream
            return Observable.combineLatest(
                stores.getChannels().observeChannel(channelId),
                stores.getUsers().observeMe(),
                stores.getGuilds().observeGuild(guildId),
                stores.getPermissions().observePermissionsForChannel(channelId),
                stores.getGuilds().observeRoles(guildId),
            ) { channel, me, guild, permissions, roles ->
                if (channel == null || guild == null || permissions == null || !PermissionEditor.canManage(guild, me, permissions)) {
                    return@combineLatest null
                }
                if (type == PermissionEditor.TYPE_USER) {
                    val user = stores.getUsers().getUsers()[targetId] ?: return@combineLatest null
                    ModelForUser(channel, permissions, targetId == me.id, user, stores.getGuilds().getMember(guildId, targetId))
                } else {
                    val role = roles[targetId] ?: return@combineLatest null
                    val member = stores.getGuilds().getMember(guildId, me.id)
                    val overwrites = channel.permissionOverwrites.orEmpty()
                    fun permissionsWith(replacement: PermissionOverwrite) = PermissionUtils.computeNonThreadPermissions(
                        me.id,
                        guildId,
                        guild.ownerId,
                        member,
                        roles,
                        overwrites.filterNot { it.id == targetId && it.type == PermissionOverwrite.Type.ROLE } + replacement,
                    )
                    ModelForRole(
                        channel,
                        permissions,
                        role,
                        targetId,
                        permissionsWith(PermissionOverwrite(targetId, PermissionOverwrite.Type.ROLE, 0L, 0L)),
                        permissionsWith(PermissionOverwrite(targetId, PermissionOverwrite.Type.ROLE, 0L, Permission.ALL)),
                        targetId == guildId || member?.getRoles().orEmpty().contains(targetId),
                        targetId == guildId,
                    )
                }
            }.computationLatest().distinctUntilChanged()
        }
    }
}
