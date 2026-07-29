package com.discord.widgets.servers.settings.members

import com.discord.api.role.GuildRole
import com.discord.api.permission.Permission
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.permissions.ManageUserContext
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * State for the server-settings member list.
 *
 * The list is filtered on two independent axes at once — a name/id search box and a role spinner —
 * and both are inputs rather than fields, so this model is rebuilt from scratch on every change to
 * either. That is affordable because the source is the already-materialised guild member map.
 *
 * `null` from [get] means "this guild, this member or these permissions are gone" and the screen
 * closes; it is not a loading state, because the member map is always present for a joined guild.
 *
 * @param myHighestRole the acting user's top role — the hierarchy check the list uses to decide
 *   whether tapping a row may open the edit screen.
 * @param canKick gates the toolbar's prune entry, not the per-row edit.
 * @param canManageMembers whether any per-member moderation is possible at all.
 */
data class WidgetServerSettingsMembersModel(
    val guild: Guild,
    val roles: Map<Long, GuildRole>,
    val memberItems: List<MemberItem>,
    val myHighestRole: GuildRole?,
    val meUser: MeUser,
    val canKick: Boolean,
    val canManageMembers: Boolean,
) {

    /**
     * One member row.
     *
     * @param isManagable whether the acting user may moderate this member at all; a row that is not
     *   manageable shows a padlock instead of the overflow chevron.
     */
    data class MemberItem(
        val user: User,
        val userDisplayName: String,
        val roles: List<GuildRole>,
        val isManagable: Boolean,
        val guildMember: GuildMember,
    ) : MGRecyclerDataPayload {
        override val type: Int get() = MEMBER_LIST_ITEM_TYPE
        override val key: String get() = user.id.toString()
    }

    companion object {
        const val MEMBER_LIST_ITEM_TYPE = 1

        /** Case-insensitive by display name, then by id so the order is total and stable. */
        private val MEMBER_COMPARATOR = Comparator<MemberItem> { first, second ->
            val byName = first.userDisplayName.compareTo(second.userDisplayName, ignoreCase = true)
            if (byName != 0) byName else first.user.id.compareTo(second.user.id)
        }

        /**
         * @param filterPublisher the search box's text; matched against user id, username and nick.
         * @param roleFilterPublisher the selected role id. The guild id doubles as `@everyone`'s
         *   role id, which is how "all members" is expressed.
         */
        fun get(
            guildId: Long,
            filterPublisher: Observable<String>,
            roleFilterPublisher: Observable<Long>,
        ): Observable<WidgetServerSettingsMembersModel?> = StoreStream.getGuilds()
            .observeComputed(guildId)
            .switchMap { members ->
                Observable.combineLatest(
                    StoreStream.getGuilds().observeGuild(guildId),
                    StoreStream.getPermissions().observePermissionsForGuild(guildId),
                    StoreStream.getUsers().observeMe(),
                    // Only users who are members of this guild; the global user store also holds
                    // people met elsewhere.
                    StoreStream.getUsers().observeUsers(members.keys),
                    StoreStream.getGuilds().observeRoles(guildId),
                    filterPublisher.debounce(FILTER_DEBOUNCE_MS, TimeUnit.MILLISECONDS),
                    roleFilterPublisher,
                ) { guild, myPermissions, meUser, users, roles, filter, roleIdFilter ->
                    toModel(
                        guildId, guild, myPermissions, meUser, users, roles, members, filter, roleIdFilter,
                    )
                }
            }
            .computationLatest()
            .distinctUntilChanged()

        @Suppress("LongParameterList")
        private fun toModel(
            guildId: Long,
            guild: Guild?,
            myPermissions: Long?,
            meUser: MeUser,
            users: Map<Long, User>,
            roles: Map<Long, GuildRole>,
            members: Map<Long, GuildMember>,
            filter: String,
            roleIdFilter: Long,
        ): WidgetServerSettingsMembersModel? {
            val myMember = members[meUser.id]
            if (guild == null || myPermissions == null || myMember == null) return null

            // The search box accepts a raw user id as well as a name, which is the only way to find
            // a member the client has not received a full user object for.
            val filterAsId = filter.toLongOrNull()

            val items = sortedSetOf(MEMBER_COMPARATOR)
            for (user in users.values) {
                val member = members[user.id] ?: continue
                // `@everyone`'s role id equals the guild id and no member carries it explicitly.
                val matchesRole = roleIdFilter == guildId || member.getRoles().contains(roleIdFilter)
                if (!matchesRole) continue
                val matchesName = (filterAsId != null && user.id == filterAsId) ||
                    user.username.contains(filter, ignoreCase = true) ||
                    member.nick?.contains(filter, ignoreCase = true) == true
                if (!matchesName) continue

                val manageContext = ManageUserContext.from(
                    guild,
                    meUser,
                    user,
                    myMember.getRoles(),
                    member.getRoles(),
                    myPermissions,
                    roles,
                )
                items.add(
                    MemberItem(
                        user = user,
                        userDisplayName = GuildMember.getNickOrUsername(member, user),
                        roles = member.getRoles().mapNotNull { roles[it] },
                        isManagable = manageContext.canManage() || manageContext.canDisableCommunication,
                        guildMember = member,
                    ),
                )
            }

            return WidgetServerSettingsMembersModel(
                guild = guild,
                roles = sortRoles(roles),
                memberItems = items.toList(),
                myHighestRole = RoleUtils.getHighestRole(roles, myMember),
                meUser = meUser,
                // Prune is a bulk kick, so it needs Kick Members with MFA elevation — the owner is
                // exempt from the permission but not from the screen.
                canKick = guild.isOwner(meUser.id) || PermissionUtils.canAndIsElevated(
                    Permission.KICK_MEMBERS,
                    myPermissions,
                    meUser.mfaEnabled,
                    guild.mfaLevel,
                ),
                canManageMembers = PermissionUtils.canManageGuildMembers(
                    guild.isOwner(meUser.id),
                    meUser.mfaEnabled,
                    guild.mfaLevel,
                    myPermissions,
                ),
            )
        }

        /** Roles in hierarchy order, so the spinner reads top-down like the roles screen. */
        private fun sortRoles(guildRoles: Map<Long, GuildRole>): Map<Long, GuildRole> =
            guildRoles.values
                .sortedWith(RoleUtils.ROLE_COMPARATOR)
                .associateByTo(LinkedHashMap()) { it.id }

        private const val FILTER_DEBOUNCE_MS = 300L
    }
}
