package com.discord.widgets.chat.list.model

import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isGuildForumPost
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.channel.isThread
import com.discord.api.channel.isVoiceChannel
import com.discord.api.permission.Permission
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.StoreMessagesLoader
import com.discord.stores.StoreStream
import com.discord.stores.utilities.leadingEdgeThrottle
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.threads.ThreadUtils
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.GuildWelcomeEntry
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.entries.SpacerEntry
import com.discord.widgets.chat.list.entries.StartOfChatEntry
import com.discord.widgets.chat.list.entries.StartOfPrivateChatEntry
import java.util.concurrent.TimeUnit
import rx.Observable

/** The single row above a channel's oldest loaded message. */
data class WidgetChatListModelTop(val item: ChatListEntry) {

    internal data class WelcomeInputs(
        val guild: Guild?,
        val me: MeUser,
        val channelPermissions: Long?,
        val defaultChannelId: Long,
        val channelOwnerMember: GuildMember?,
        val channelOwnerUser: User?,
        val mutualGuilds: List<Guild>,
        val parentChannel: Channel?,
        val canAccessRedesignedForumChannels: Boolean,
    )

    companion object {
        private const val FORUM_REDESIGN_EXPERIMENT = "2022-03_android_forum_channel_redesign"

        @JvmStatic
        fun get(channel: Channel): Observable<WidgetChatListModelTop> =
            StoreStream.getMessagesLoader().getMessagesLoadedState(channel.id)
                .switchMap { state ->
                    when {
                        state.isOldestMessagesLoaded -> getWelcomeEntry(channel)
                        !state.isTouchedSinceLastJump && state.isInitialMessagesLoaded ->
                            Observable.just(SpacerEntry(channel.id))
                        else -> Observable.just(LoadingEntry())
                    }.map(::WidgetChatListModelTop)
                }
                .distinctUntilChanged()

        internal fun topEntry(
            channelId: Long,
            state: StoreMessagesLoader.ChannelLoadedState,
            welcomeEntry: ChatListEntry,
        ): ChatListEntry = when {
            state.isOldestMessagesLoaded -> welcomeEntry
            !state.isTouchedSinceLastJump && state.isInitialMessagesLoaded -> SpacerEntry(channelId)
            else -> LoadingEntry()
        }

        internal fun welcomeEntry(channel: Channel, inputs: WelcomeInputs): ChatListEntry {
            val guildHasIcon = inputs.guild?.hasIcon() == true
            val canReadHistory = PermissionUtils.can(Permission.READ_MESSAGE_HISTORY, inputs.channelPermissions)
            val canInvite = PermissionUtils.can(Permission.CREATE_INSTANT_INVITE, inputs.channelPermissions)
            val canManageChannel = PermissionUtils.can(Permission.MANAGE_CHANNELS, inputs.channelPermissions)
            val isOwner = inputs.guild?.isOwner(inputs.me.id) == true

            if (
                channel.isGuildTextyChannel() &&
                inputs.guild != null && channel.id == inputs.defaultChannelId && canReadHistory &&
                (canInvite || !guildHasIcon)
            ) {
                return GuildWelcomeEntry(
                    isOwner = isOwner,
                    guildHasIcon = guildHasIcon,
                    canInvite = canInvite,
                    guildId = inputs.guild.id,
                    guildName = inputs.guild.name.orEmpty(),
                )
            }

            if (channel.isPrivate()) {
                return StartOfPrivateChatEntry(
                    channelId = channel.id,
                    channelName = channel.getDisplayName(),
                    channelType = channel.type,
                    channelIcon = IconUtils.getForChannel(channel),
                    isSystemDM = channel.isSystemDM(),
                    mutualGuilds = inputs.mutualGuilds,
                )
            }

            return StartOfChatEntry(
                channelId = channel.id,
                channelName = channel.getDisplayName(),
                canReadMessageHistory = canReadHistory,
                canManageChannel = canManageChannel,
                canManageThread = ThreadUtils.canManageThread(inputs.me, channel, inputs.channelPermissions),
                isThread = channel.isThread(),
                threadAutoArchiveDuration = channel.threadMetadata?.autoArchiveDuration,
                threadCreatorMember = inputs.channelOwnerMember,
                threadCreatorName = inputs.channelOwnerUser?.let {
                    GuildMember.getNickOrUsername(inputs.channelOwnerMember, it)
                }.orEmpty(),
                isTextInVoice = channel.isVoiceChannel(),
                isGuildForumPost = channel.isGuildForumPost(inputs.parentChannel),
                canAccessRedesignedForumChannels = inputs.canAccessRedesignedForumChannels,
                availableTags = inputs.parentChannel?.availableTags,
                appliedTags = channel.appliedTags,
            )
        }

        private fun getWelcomeEntry(channel: Channel): Observable<ChatListEntry> {
            val stores = StoreStream
            val primary = Observable.combineLatest(
                stores.getGuilds().observeGuild(channel.guildId),
                stores.getUsers().observeMe(),
                stores.getPermissions().observePermissionsForChannel(channel.id),
                stores.getChannels().observeDefaultChannel(channel.guildId).map { it?.id ?: 0L },
                ::PrimaryInputs,
            )
            val recipientId = channel.getDMRecipient()?.id ?: 0L
            val mutualGuilds = Observable.combineLatest(
                stores.getGuildsSorted().observeOrderedGuilds(),
                stores.getGuilds().observeComputed(),
            ) { guilds, members -> guilds.values.filter { members[it.id]?.containsKey(recipientId) == true } }
            val secondary = Observable.combineLatest(
                stores.getGuilds().observeGuildMember(channel.guildId, channel.ownerId)
                    .leadingEdgeThrottle(1, TimeUnit.SECONDS),
                stores.getUsers().observeUser(channel.ownerId),
                mutualGuilds,
                stores.getChannels().observeChannel(channel.parentId),
                stores.getExperiments().observeGuildExperiment(
                    FORUM_REDESIGN_EXPERIMENT,
                    channel.guildId,
                    false,
                ).map { it?.bucket == 1 },
                ::SecondaryInputs,
            )
            return Observable.combineLatest(primary, secondary) { first, second ->
                welcomeEntry(
                    channel,
                    WelcomeInputs(
                        first.guild,
                        first.me,
                        first.permissions,
                        first.defaultChannelId,
                        second.ownerMember,
                        second.ownerUser,
                        second.mutualGuilds,
                        second.parentChannel,
                        second.forumRedesign,
                    ),
                )
            }
        }

        private data class PrimaryInputs(
            val guild: Guild?,
            val me: MeUser,
            val permissions: Long?,
            val defaultChannelId: Long,
        )

        private data class SecondaryInputs(
            val ownerMember: GuildMember?,
            val ownerUser: User?,
            val mutualGuilds: List<Guild>,
            val parentChannel: Channel?,
            val forumRedesign: Boolean,
        )
    }
}
