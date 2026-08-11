package com.discord.utilities.applicationcommands

import com.discord.api.channel.Channel
import com.discord.api.channel.isDM
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isThread
import com.discord.api.guild.GuildNsfwLevel
import com.discord.api.user.NsfwAllowance
import com.discord.models.commands.CommandPermissionContext
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.UserSettings
import com.discord.stores.StoreStream
import com.discord.stores.StorePermissions
import com.discord.stores.StoreUser
import com.discord.utilities.rx.computationLatest
import rx.Observable

/**
 * Builds the [CommandPermissionContext] a channel's command list is filtered against.
 *
 * The reference resolves a thread to its parent before doing anything else, so the permissions,
 * the NSFW flag and the channel id all come from the parent when we are in one.
 */
class CommandPermissionContexts(
    private val storeUsers: StoreUser,
    private val storeGuilds: StoreGuilds,
    private val storeChannels: StoreChannels,
    private val storePermissions: StorePermissions,
) {
    fun observe(channel: Channel): Observable<CommandPermissionContext> {
        val checked = if (channel.isThread()) {
            storeChannels.observeChannel(channel.parentId).map { it ?: channel }
        } else {
            Observable.just(channel)
        }
        return checked.computationLatest().switchMap { checkedChannel ->
            Observable.combineLatest(
                storeUsers.observeMe(false),
                storeGuilds.observeComputed(channel.guildId),
                storeGuilds.observeGuild(channel.guildId),
                storePermissions.observePermissionsForChannel(checkedChannel.id),
                UserSettings.viewNsfwCommands.observe(),
            ) { me, members, guild, permissions, viewNsfwCommands ->
                CommandPermissionContext(
                    userId = me.id,
                    roleIds = members[me.id]?.getRoles().orEmpty(),
                    guildId = channel.guildId.takeIf { it != 0L },
                    channelId = checkedChannel.id.takeIf { !channel.isPrivate() },
                    isThread = channel.isThread(),
                    isPrivateChannel = channel.isPrivate(),
                    dmRecipientId = channel.takeIf(Channel::isDM)?.recipients?.firstOrNull()?.id,
                    computedPermissions = permissions,
                    allowNsfw = allowNsfw(
                        channel = channel,
                        checkedChannel = checkedChannel,
                        nsfwAllowed = me.nsfwAllowance == NsfwAllowance.ALLOWED,
                        guildIsNsfw = guild?.nsfwLevel in RESTRICTED_CONTENT_LEVELS,
                        viewNsfwCommands = viewNsfwCommands,
                    ),
                )
            }
        }.computationLatest().distinctUntilChanged()
    }

    companion object {
        /** The same context read straight off the store snapshots, for one-shot surfaces. */
        fun snapshot(channel: Channel): CommandPermissionContext {
            val checked = if (channel.isThread()) {
                StoreStream.getChannels().getChannel(channel.parentId) ?: channel
            } else {
                channel
            }
            val me = StoreStream.getUsers().getMe()
            val guild = StoreStream.getGuilds().getGuild(channel.guildId)
            return CommandPermissionContext(
                userId = me.id,
                roleIds = StoreStream.getGuilds().getMembers()[channel.guildId]
                    ?.get(me.id)?.getRoles().orEmpty(),
                guildId = channel.guildId.takeIf { it != 0L },
                channelId = checked.id.takeIf { !channel.isPrivate() },
                isThread = channel.isThread(),
                isPrivateChannel = channel.isPrivate(),
                dmRecipientId = channel.takeIf(Channel::isDM)?.recipients?.firstOrNull()?.id,
                computedPermissions = StoreStream.getPermissions().getPermissionsByChannel()[checked.id],
                allowNsfw = allowNsfw(
                    channel = channel,
                    checkedChannel = checked,
                    nsfwAllowed = me.nsfwAllowance == NsfwAllowance.ALLOWED,
                    guildIsNsfw = guild?.nsfwLevel in RESTRICTED_CONTENT_LEVELS,
                    viewNsfwCommands = UserSettings.viewNsfwCommands.get(),
                ),
            )
        }

        private val RESTRICTED_CONTENT_LEVELS =
            setOf(GuildNsfwLevel.EXPLICIT, GuildNsfwLevel.AGE_RESTRICTED)

        internal fun allowNsfw(
            channel: Channel,
            checkedChannel: Channel,
            nsfwAllowed: Boolean,
            guildIsNsfw: Boolean,
            viewNsfwCommands: Boolean,
        ): Boolean = when {
            !nsfwAllowed -> false
            channel.guildId != 0L -> checkedChannel.nsfw || guildIsNsfw
            else -> viewNsfwCommands
        }
    }
}
