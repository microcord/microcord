package com.discord.widgets.guilds.list

import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.permission.Permission
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.stores.StoreGuildsSorted
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.guilds.list.GuildsListStoreHooks.GuildStream
import com.discord.widgets.guilds.list.WidgetGuildsListViewModel.StoreState

/**
 * Turns one [StoreState] snapshot into the sidebar's flat row list.
 *
 * Split out of [WidgetGuildsListViewModel] because it is pure: the whole sidebar layout — ordering,
 * folder aggregation, which indicators light up — is decided here and can be asserted without a
 * store graph or a main looper behind it.
 */
internal object GuildListItemBuilder {

    /** Friends, divider, create, space — the rows that are always there, near enough. */
    private const val FIXED_ROW_ESTIMATE = 3

    /** DM notification settings are stored under the pseudo-guild id `0`. */
    private const val PRIVATE_CHANNELS_SETTINGS_ID = 0L

    /**
     * Assembles the whole sidebar, in order: Friends, mentioned DMs, the pending-guilds folder,
     * lurked guilds, a divider, the user's own guilds and folders, the outage row, "+", the
     * student hub, the help row for new accounts, and a spacer sized to the tab bar.
     */
    fun buildItems(storeState: StoreState): List<GuildListItem> {
        val items = ArrayList<GuildListItem>(
            storeState.pendingGuilds.size + storeState.sortedGuilds.size + FIXED_ROW_ESTIMATE,
        )

        items.add(GuildListItem.FriendsItem(isSelected = storeState.selectedGuildId <= 0))
        items.addAll(
            createDirectMessageItems(
                storeState.privateChannels,
                storeState.mentionCounts,
                storeState.guildSettings[PRIVATE_CHANNELS_SETTINGS_ID],
            ),
        )

        // Guild mention counts are the sum over the guild's channels, so they are computed once
        // here and shared by the folder aggregation and the guild rows.
        val mentionCountsByGuild = HashMap<Long, Int>()
        for (entry in storeState.sortedGuilds) {
            when (entry) {
                is StoreGuildsSorted.Entry.SingletonGuild ->
                    mentionCountsByGuild[entry.guild.id] =
                        sumMentionCountsForGuild(entry.guild.id, storeState.channelIds, storeState.mentionCounts)
                is StoreGuildsSorted.Entry.Folder -> for (guild in entry.guilds) {
                    mentionCountsByGuild[guild.id] =
                        sumMentionCountsForGuild(guild.id, storeState.channelIds, storeState.mentionCounts)
                }
            }
        }

        // Lurked guilds are previewed, not joined: they are pinned above the divider with the DMs
        // instead of taking part in the user's own ordering below it.
        val lurkerGuildItems = storeState.sortedGuilds
            .asSequence()
            .filterIsInstance<StoreGuildsSorted.Entry.SingletonGuild>()
            .filter { storeState.lurkingGuildIds.contains(it.guild.id) }
            .map { createGuildItem(it.guild, storeState, mentionCountsByGuild[it.guild.id] ?: 0) }

        val guildItems = ArrayList<GuildListItem>()
        storeState.sortedGuilds
            .asSequence()
            .filterNot {
                it is StoreGuildsSorted.Entry.SingletonGuild &&
                    storeState.lurkingGuildIds.contains(it.guild.id)
            }
            .forEach { entry ->
                when (entry) {
                    is StoreGuildsSorted.Entry.SingletonGuild -> guildItems.add(
                        createGuildItem(entry.guild, storeState, mentionCountsByGuild[entry.guild.id] ?: 0),
                    )
                    is StoreGuildsSorted.Entry.Folder ->
                        guildItems.addAll(createFolderItems(entry, storeState, mentionCountsByGuild))
                }
            }

        if (storeState.openFolderIds.contains(GuildListItem.ID_PENDING_GUILDS_FOLDER)) {
            GuildsListStoreHooks.fetchPendingGuilds()
        }
        if (hasReviewablePendingGuilds(storeState)) {
            items.addAll(createPendingGuildsFolder(storeState, mentionCountsByGuild))
        }

        items.addAll(lurkerGuildItems)
        items.add(GuildListItem.DividerItem)
        items.addAll(guildItems)

        if (storeState.unavailableGuilds.isNotEmpty()) {
            items.add(GuildListItem.UnavailableItem(storeState.unavailableGuilds.size))
        }
        items.add(GuildListItem.CreateItem)
        if (GuildsListStoreHooks.isHubDiscoveryEnabled()) {
            items.add(
                GuildListItem.HubItem(
                    showSparkle = storeState.showHubSparkle && GuildsListStoreHooks.isHubDiscoverySparkleEnabled(),
                ),
            )
        }
        if (storeState.isNewUser) {
            items.add(GuildListItem.DividerItem)
            items.add(GuildListItem.HelpItem)
        }
        items.add(GuildListItem.SpaceItem)

        return items
    }

    /**
     * The DMs that currently have a mention and are not individually muted.
     *
     * Sorted with the channel-list comparator (`ChannelUtils.h`, confirmed by disassembly) rather
     * than by recency — surprising, but that is what the reference does.
     */
    private fun createDirectMessageItems(
        privateChannels: Map<Long, Channel>,
        mentionCounts: Map<Long, Int>,
        dmSettings: ModelNotificationSettings?,
    ): Sequence<GuildListItem.PrivateChannelItem> = privateChannels.values
        .asSequence()
        .filter { channel ->
            mentionCounts.containsKey(channel.id) &&
                dmSettings?.getChannelOverride(channel.id)?.isMuted != true
        }
        .sortedWith(Channel.getSortByNameAndType())
        .map { GuildListItem.PrivateChannelItem(it, mentionCounts[it.id] ?: 0) }

    private fun createGuildItem(
        guild: Guild,
        storeState: StoreState,
        numMentions: Int,
        folderId: Long? = null,
        isLastGuildInFolder: Boolean? = null,
        isPendingGuild: Boolean = false,
    ): GuildListItem.GuildItem {
        val guildId = guild.id
        val guildChannelIds = storeState.channelIds[guildId]
        val notificationSettings = storeState.guildSettings[guildId]
        val isUnread = if (notificationSettings?.isMuted == true) {
            false
        } else {
            storeState.unreadGuildIds.contains(guildId)
        }
        val isConnectedToVoice = storeState.selectedVoiceChannelId > 0 &&
            guildChannelIds?.contains(storeState.selectedVoiceChannelId) == true
        val selectedVoiceChannel = storeState.channels[storeState.selectedVoiceChannelId]

        return GuildListItem.GuildItem(
            guild = guild,
            mentionCount = numMentions,
            isLurkingGuild = storeState.lurkingGuildIds.contains(guildId),
            isUnread = isUnread,
            isSelected = guildId == storeState.selectedGuildId,
            folderId = folderId,
            isConnectedToVoice = isConnectedToVoice,
            hasOngoingApplicationStream = shouldDisplayVideoIconOnGuild(
                guildId,
                notificationSettings,
                storeState.applicationStreams,
                storeState.allChannelPermissions,
            ),
            isTargetedForFolderCreation = false,
            isLastGuildInFolder = isLastGuildInFolder,
            applicationStatus = storeState.guildJoinRequests[guildId]?.applicationStatus,
            isPendingGuild = isPendingGuild,
            hasActiveStageChannel = storeState.guildIdsWithActiveStageEvents.contains(guildId),
            isConnectedToStageChannel = selectedVoiceChannel?.isStageVoiceChannel() == true,
            hasActiveScheduledEvent = storeState.guildIdsWithActiveScheduledEvents.contains(guildId),
        )
    }

    /** The folder row, plus its guild rows when it is expanded. */
    private fun createFolderItems(
        folder: StoreGuildsSorted.Entry.Folder,
        storeState: StoreState,
        mentionCountsByGuild: Map<Long, Int>,
    ): List<GuildListItem> {
        val isOpen = storeState.openFolderIds.contains(folder.id)

        // An expanded folder shows nothing of its own: every badge and indicator belongs to a
        // child row that is already visible.
        var isAnyGuildSelected = false
        var isAnyGuildConnectedToVoice = false
        var isAnyGuildConnectedToStageChannel = false
        var isUnread = false
        var mentionCount = 0
        if (!isOpen) {
            for (guild in folder.guilds) {
                isAnyGuildSelected = isAnyGuildSelected || guild.id == storeState.selectedGuildId
                isAnyGuildConnectedToVoice = isAnyGuildConnectedToVoice ||
                    storeState.channelIds[guild.id]?.contains(storeState.selectedVoiceChannelId) == true
                isAnyGuildConnectedToStageChannel = isAnyGuildConnectedToStageChannel ||
                    storeState.channels[storeState.selectedVoiceChannelId]?.isStageVoiceChannel() == true
                isUnread = isUnread || (
                    storeState.unreadGuildIds.contains(guild.id) &&
                        storeState.guildSettings[guild.id]?.isMuted != true
                    )
                mentionCount += mentionCountsByGuild[guild.id] ?: 0
            }
        }

        val folderItem = GuildListItem.FolderItem(
            folderId = folder.id,
            color = folder.color,
            name = folder.name,
            isOpen = isOpen,
            guilds = folder.guilds,
            isAnyGuildSelected = isAnyGuildSelected,
            isAnyGuildConnectedToVoice = isAnyGuildConnectedToVoice,
            isAnyGuildConnectedToStageChannel = isAnyGuildConnectedToStageChannel,
            mentionCount = mentionCount,
            isUnread = isUnread,
        )
        if (!isOpen) return listOf(folderItem)

        val lastIndex = folder.guilds.lastIndex
        return buildList {
            add(folderItem)
            folder.guilds.forEachIndexed { index, guild ->
                add(
                    createGuildItem(
                        guild = guild,
                        storeState = storeState,
                        numMentions = mentionCountsByGuild[guild.id] ?: 0,
                        folderId = folder.id,
                        isLastGuildInFolder = index == lastIndex,
                    ),
                )
            }
        }
    }

    /**
     * The synthetic folder holding guilds whose membership application is still under review.
     *
     * The reference decides whether to show it against a list that R8 proved empty, so in 126.21
     * this folder never renders. Here the decision is made against the pending guilds themselves,
     * which is the only reading under which the rest of the reference code — this method,
     * `isPendingGuild`, the drag guards, the verification gate — has any effect.
     */
    private fun hasReviewablePendingGuilds(storeState: StoreState): Boolean {
        if (storeState.guildJoinRequests.isEmpty()) return false
        val pendingGuildIds = storeState.pendingGuilds.mapTo(HashSet()) { it.id }
        return storeState.guildJoinRequests.keys.any { it in pendingGuildIds }
    }

    private fun createPendingGuildsFolder(
        storeState: StoreState,
        mentionCountsByGuild: Map<Long, Int>,
    ): List<GuildListItem> {
        val folderItem = GuildListItem.FolderItem(
            folderId = GuildListItem.ID_PENDING_GUILDS_FOLDER,
            color = null,
            name = null,
            isOpen = storeState.openFolderIds.contains(GuildListItem.ID_PENDING_GUILDS_FOLDER),
            guilds = storeState.pendingGuilds,
            isAnyGuildSelected = false,
            isAnyGuildConnectedToVoice = false,
            isAnyGuildConnectedToStageChannel = false,
            mentionCount = 0,
            isUnread = false,
        )
        if (!folderItem.isOpen) return listOf(folderItem)

        val lastIndex = storeState.pendingGuilds.lastIndex
        return buildList {
            add(folderItem)
            storeState.pendingGuilds.forEachIndexed { index, guild ->
                add(
                    createGuildItem(
                        guild = guild,
                        storeState = storeState,
                        numMentions = mentionCountsByGuild[guild.id] ?: 0,
                        folderId = folderItem.folderId,
                        isLastGuildInFolder = index == lastIndex,
                        isPendingGuild = true,
                    ),
                )
            }
        }
    }

    private fun sumMentionCountsForGuild(
        guildId: Long,
        guildChannels: Map<Long, Collection<Long>>,
        mentionCounts: Map<Long, Int>,
    ): Int = guildChannels[guildId]?.sumOf { mentionCounts[it] ?: 0 } ?: 0

    /** True when someone is going live in a channel of this guild that the user can see. */
    private fun shouldDisplayVideoIconOnGuild(
        guildId: Long,
        guildNotificationSettings: ModelNotificationSettings?,
        streams: List<GuildStream>,
        permissions: Map<Long, Long>,
    ): Boolean {
        if (streams.isEmpty()) return false
        if (guildNotificationSettings?.isMuted == true) return false
        return streams.any {
            it.guildId == guildId && PermissionUtils.can(Permission.VIEW_CHANNEL, permissions[it.channelId])
        }
    }
}
