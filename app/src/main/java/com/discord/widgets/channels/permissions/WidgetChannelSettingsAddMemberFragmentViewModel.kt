package com.discord.widgets.channels.permissions

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetChannelSettingsAddMemberFragmentViewModel(
    private val channelId: Long,
    private val showRolesWithGuildPermission: Boolean,
) : AppViewModel<WidgetChannelSettingsAddMemberFragmentViewModel.ViewState>() {
    private var query = ""
    private val selected = LinkedHashMap<Long, PermissionOverwrite.Type>()
    private var storeState: StoreState? = null

    init {
        val guilds = StoreStream.getGuilds()
        Observable.combineLatest(
            StoreStream.getChannels().observeChannel(channelId),
            guilds.observeGuilds(),
            guilds.observeRoles(),
            guilds.observeComputed(),
            StoreStream.getUsers().observeAllUsers(),
        ) { channel, guildMap, roleMap, memberMap, users ->
            channel?.let { StoreState(guildMap[it.guildId], it, roleMap[it.guildId].orEmpty(), memberMap[it.guildId].orEmpty(), users) }
        }.ui(this).subscribe { state -> state?.let(::handleStoreState) }
    }

    fun updateQuery(value: String) {
        query = value
        storeState?.let(::publish)
        if (value.isNotBlank()) storeState?.guild?.let { StoreStream.getGatewayConnection().requestGuildMembers(it.id, value.trim()) }
    }

    fun toggleItem(id: Long, type: PermissionOverwrite.Type) {
        if (selected.remove(id) == null) selected[id] = type
        storeState?.let(::publish)
    }

    private fun handleStoreState(state: StoreState) {
        storeState = state
        publish(state)
    }

    private fun publish(state: StoreState) = updateViewState(
        ViewState(state.guild, state.channel, query, buildItems(state, query, selected), selected.toMap()),
    )

    internal fun buildItems(
        state: StoreState,
        query: String,
        selected: Map<Long, PermissionOverwrite.Type>,
    ): List<AddMemberAdapter.Item> {
        val existing = state.channel.permissionOverwrites.orEmpty().associateBy(PermissionOverwrite::id)
        val roles = state.roles.values
            .filter { it.permissions and Permission.ADMINISTRATOR == 0L }
            .filter { it.id != state.guild?.id }
            .filter { it.name.contains(query.trim(), true) }
            .filter { showRolesWithGuildPermission || !PermissionUtils.canRole(Permission.MODERATE_STAGE_CHANNEL, it, existing[it.id]) }
            .filter { existing[it.id]?.allow?.and(Permission.MODERATE_STAGE_CHANNEL) != Permission.MODERATE_STAGE_CHANNEL }
            .map { role ->
                val hasGuildPermission = PermissionUtils.canRole(Permission.MODERATE_STAGE_CHANNEL, role, existing[role.id])
                AddMemberAdapter.Item.PermissionOwnerItem(
                    PermissionOwner.Role(role),
                    hasGuildPermission || selected[role.id] == PermissionOverwrite.Type.ROLE,
                    if (hasGuildPermission) {
                        AddMemberAdapter.Item.AddStatus.CannotAdd(AddMemberAdapter.Item.AddStatus.Reason.HAS_GUILD_PERMISSIONS)
                    } else AddMemberAdapter.Item.AddStatus.CanAdd,
                )
            }
        val members = state.users.values.asSequence()
            .filter { state.members.containsKey(it.id) }
            .filter { it.id != state.guild?.ownerId }
            .filter { existing[it.id]?.allow?.and(Permission.MODERATE_STAGE_CHANNEL) != Permission.MODERATE_STAGE_CHANNEL }
            .filter { user ->
                val member = state.members[user.id]
                user.username.contains(query.trim(), true) || member?.nick?.contains(query.trim(), true) == true
            }
            .sortedBy { it.username.lowercase() }
            .map { user ->
                AddMemberAdapter.Item.PermissionOwnerItem(
                    PermissionOwner.Member(user, state.members[user.id]?.nick, user.id == state.guild?.ownerId),
                    selected[user.id] == PermissionOverwrite.Type.MEMBER,
                    AddMemberAdapter.Item.AddStatus.CanAdd,
                )
            }.toList()
        return buildList {
            add(AddMemberAdapter.Item.CategoryItem(AddMemberAdapter.Item.CategoryType.ROLE))
            addAll(roles)
            add(AddMemberAdapter.Item.CategoryItem(AddMemberAdapter.Item.CategoryType.MEMBER))
            addAll(members)
        }
    }

    data class StoreState(
        val guild: Guild?,
        val channel: Channel,
        val roles: Map<Long, GuildRole>,
        val members: Map<Long, GuildMember>,
        val users: Map<Long, User>,
    )
    data class ViewState(
        val guild: Guild?,
        val channel: Channel,
        val query: String,
        val items: List<AddMemberAdapter.Item>,
        val selected: Map<Long, PermissionOverwrite.Type>,
    )
}
