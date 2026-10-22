package com.discord.widgets.channels.permissions

import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import rx.Observable
import com.discord.api.channel.Channel
import com.discord.utilities.permissions.PermissionUtils

class WidgetStageChannelModeratorPermissionsViewModel(channelId: Long) :
    AppViewModel<WidgetStageChannelModeratorPermissionsViewModel.ViewState>() {
    init {
        val guilds = StoreStream.getGuilds()
        Observable.combineLatest(
            StoreStream.getChannels().observeChannel(channelId),
            StoreStream.getUsers().observeAllUsers(),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForChannel(channelId),
        ) { channel, users, me, permissions ->
            val guild = channel?.let { guilds.getGuild(it.guildId) }
            if (channel == null || guild == null) ViewState.Invalid else buildViewState(
                StoreState.Valid(
                    guild,
                    channel.permissionOverwrites.orEmpty(),
                    guilds.getRoles()[guild.id].orEmpty(),
                    users,
                    guilds.getMembers()[guild.id].orEmpty(),
                    users[guild.ownerId],
                    canEditStageModerators(guild, me.id, channel, permissions),
                ),
            )
        }.ui(this).subscribe(::updateViewState)
    }

    /** The reference's `StageChannelUtils.computeCanEditStageModerators`. */
    private fun canEditStageModerators(guild: Guild, meId: Long, channel: Channel, channelPermissions: Long?): Boolean {
        if (guild.isOwner(meId) || PermissionUtils.can(Permission.ADMINISTRATOR, channelPermissions)) return true
        val moderation = Permission.MANAGE_ROLES or Permission.MANAGE_CHANNELS or
            Permission.MUTE_MEMBERS or Permission.MOVE_MEMBERS
        if (PermissionUtils.can(moderation, StoreStream.getPermissions().getGuildPermissions()[guild.id])) return true
        val member = StoreStream.getGuilds().getMembers()[guild.id]?.get(meId)
        val overwrite = PermissionUtils.computeChannelOverwrite(meId, guild.id, member, channel.permissionOverwrites)
        return PermissionUtils.can(Permission.MANAGE_ROLES, overwrite)
    }

    internal fun buildViewState(state: StoreState.Valid): ViewState.Valid {
        val moderator = Permission.MODERATE_STAGE_CHANNEL
        val roleItems = state.guildRoles.values
            .filter { role ->
                role.permissions and moderator == moderator ||
                    state.channelPermissionOverwrites.any { it.type == PermissionOverwrite.Type.ROLE && it.id == role.id && it.allow and moderator == moderator }
            }
            .sortedByDescending { it.position }
            .map { role ->
                val reason = when {
                    !state.canEditModerators -> PermissionOwnerListView.RemoveStatus.CannotRemove.Reason.HAS_NO_PERMISSION
                    role.permissions and Permission.ADMINISTRATOR == Permission.ADMINISTRATOR -> PermissionOwnerListView.RemoveStatus.CannotRemove.Reason.IS_ADMINISTRATOR
                    role.permissions and moderator == moderator -> PermissionOwnerListView.RemoveStatus.CannotRemove.Reason.IS_NOT_OVERRIDE
                    else -> null
                }
                PermissionOwnerListView.Item(
                    PermissionOwner.Role(role),
                    reason?.let { PermissionOwnerListView.RemoveStatus.CannotRemove(it) }
                        ?: PermissionOwnerListView.RemoveStatus.CanRemove,
                )
            }
        val memberItems = state.channelPermissionOverwrites.asSequence()
            .filter { it.type == PermissionOverwrite.Type.MEMBER && it.allow and moderator == moderator }
            .mapNotNull { state.usersWithOverwrites[it.id] }
            .sortedBy { it.username.lowercase() }
            .map { user ->
                PermissionOwnerListView.Item(
                    PermissionOwner.Member(user, state.guildMembers[user.id]?.nick, user.id == state.guild.ownerId),
                    when {
                        !state.canEditModerators -> PermissionOwnerListView.RemoveStatus.CannotRemove(PermissionOwnerListView.RemoveStatus.CannotRemove.Reason.HAS_NO_PERMISSION)
                        user.id == state.guild.ownerId -> PermissionOwnerListView.RemoveStatus.CannotRemove(PermissionOwnerListView.RemoveStatus.CannotRemove.Reason.IS_GUILD_OWNER)
                        else -> PermissionOwnerListView.RemoveStatus.CanRemove
                    },
                )
            }.toList()
        return ViewState.Valid(roleItems, memberItems, state.canEditModerators)
    }

    sealed class StoreState {
        object Invalid : StoreState()
        data class Valid(
            val guild: Guild,
            val channelPermissionOverwrites: List<PermissionOverwrite>,
            val guildRoles: Map<Long, GuildRole>,
            val usersWithOverwrites: Map<Long, User>,
            val guildMembers: Map<Long, GuildMember>,
            val guildOwnerUser: User?,
            val canEditModerators: Boolean,
        ) : StoreState()
    }
    sealed class ViewState {
        object Invalid : ViewState()
        data class Valid(
            val roleItems: List<PermissionOwnerListView.Item>,
            val memberItems: List<PermissionOwnerListView.Item>,
            val canEditModerators: Boolean,
        ) : ViewState()
    }
}
