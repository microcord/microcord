package com.discord.widgets.chat.input.autocomplete.sources

import com.discord.utilities.user.NicknameUtils
import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserPresence
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.AutocompletableComparator
import com.discord.widgets.chat.input.autocomplete.GlobalRoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.LeadingIdentifier
import com.discord.widgets.chat.input.autocomplete.RoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.UserAutocompletable
import java.util.TreeSet
import rx.Observable

/**
 * `@mention` candidates: guild members plus mentionable roles, or the recipients of a DM.
 *
 * Membership of the *candidate* set is not the same as permission to mention: a member who cannot
 * read the channel is still offered (with [UserAutocompletable.canUserReadChannel] false) because
 * mentioning them is what triggers the "add them to the channel?" prompt. The message-context
 * filter in `AutocompleteModelUtils` is what drops them for ordinary messages.
 *
 * The per-member permission computation is skipped entirely when @everyone already has
 * `VIEW_CHANNEL` on the channel, which is the common case and by far the most expensive check.
 */
class UserAutocompletableSource(
    private val storeUsers: StoreUser,
    private val storeGuilds: StoreGuilds,
    private val storePresences: StoreUserPresence,
    private val storePermissions: StorePermissions,
    private val storeChannels: StoreChannels,
) {

    fun observeAutocompletables(
        channel: Channel,
    ): Observable<Map<LeadingIdentifier, TreeSet<Autocompletable>>> =
        if (channel.isPrivate()) observeForPrivateChannel(channel) else observeForGuildChannel(channel)

    private fun observeForPrivateChannel(
        channel: Channel,
    ): Observable<Map<LeadingIdentifier, TreeSet<Autocompletable>>> {
        val recipientIds = channel.recipients.orEmpty().map { it.id }
        return Observable.combineLatest(
            storeUsers.observeAllUsers().computationLatest(),
            storePresences.observePresencesForUsers(recipientIds).computationLatest(),
        ) { users, presences ->
            val nicks = channel.nicks.orEmpty().associateBy { it.id }
            val result = TreeSet<Autocompletable>(AutocompletableComparator())
            for (id in recipientIds) {
                val user = users[id] ?: continue
                result.add(
                    UserAutocompletable(
                        user = user,
                        nickname = nicks[id]?.nick ?: NicknameUtils.getNickname(null, channel.id, id),
                        presence = presences[id],
                        canUserReadChannel = true,
                    ),
                )
            }
            mapOf(LeadingIdentifier.MENTION to result)
        }.distinctUntilChanged()
    }

    private fun observeForGuildChannel(
        channel: Channel,
    ): Observable<Map<LeadingIdentifier, TreeSet<Autocompletable>>> {
        val guildId = channel.guildId
        return Observable.combineLatest(
            storeUsers.observeAllUsers().computationLatest(),
            storeGuilds.observeComputed(guildId).computationLatest(),
            storeGuilds.observeRoles(guildId),
            storePresences.observeAllPresences().computationLatest(),
            storePermissions.observePermissionsForChannel(channel.id),
            storeGuilds.observeGuild(guildId),
        ) { users, members, roles, presences, myPermissions, guild ->
            val parentChannel = channel.parentId.takeIf { it != 0L }?.let(storeChannels::getChannel)
            val result = TreeSet<Autocompletable>(AutocompletableComparator())

            val everyoneCanRead = PermissionUtils.canEveryone(
                Permission.VIEW_CHANNEL, channel, parentChannel, roles,
            )
            for ((userId, member) in members) {
                val user = users[userId] ?: continue
                val canRead = everyoneCanRead || PermissionUtils.can(
                    Permission.VIEW_CHANNEL,
                    PermissionUtils.computePermissions(
                        userId = userId,
                        channel = channel,
                        parentChannel = parentChannel,
                        guildOwnerId = guild?.ownerId ?: 0L,
                        member = member,
                        guildRoles = roles,
                        stageInstances = null,
                        hasJoinedThread = true,
                    ),
                )
                result.add(
                    UserAutocompletable(
                        user = user,
                        guildMember = member,
                        nickname = member.nick,
                        presence = presences[userId],
                        canUserReadChannel = canRead,
                    ),
                )
            }

            addRoles(result, roles, guildId, myPermissions)
            mapOf(LeadingIdentifier.MENTION to result)
        }.distinctUntilChanged()
    }

    /**
     * Adds role candidates. `@everyone` and `@here` are only offered when the author holds
     * `MENTION_EVERYONE`; individual roles are always offered, with
     * [RoleAutocompletable.canMention] recording whether pinging them would actually notify.
     */
    private fun addRoles(
        result: TreeSet<Autocompletable>,
        roles: Map<Long, GuildRole>,
        guildId: Long,
        myPermissions: Long?,
    ) {
        val canMentionEveryone = PermissionUtils.can(Permission.MENTION_EVERYONE, myPermissions)
        if (canMentionEveryone) {
            result.add(GlobalRoleAutocompletable.everyone)
            result.add(GlobalRoleAutocompletable.here)
        }
        for ((roleId, role) in roles) {
            // The @everyone role is represented by GlobalRoleAutocompletable, not by itself.
            if (roleId == guildId) continue
            result.add(RoleAutocompletable(role, role.mentionable || canMentionEveryone))
        }
    }
}
