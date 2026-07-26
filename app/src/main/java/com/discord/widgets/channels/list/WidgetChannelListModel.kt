package com.discord.widgets.channels.list

import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isCategory
import com.discord.api.channel.isThread
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.api.stageinstance.StageInstance
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.models.guild.UserGuildMember
import com.discord.models.member.GuildMember
import com.discord.stores.StoreThreadsActiveJoined.ActiveJoinedThread
import com.discord.stores.StoreThreadsJoined.JoinedThread
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.channels.list.items.ChannelListBottomNavSpaceItem
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemActiveEvent
import com.discord.widgets.channels.list.items.ChannelListItemActiveEventData
import com.discord.widgets.channels.list.items.ChannelListItemAddServer
import com.discord.widgets.channels.list.items.ChannelListItemCategory
import com.discord.widgets.channels.list.items.ChannelListItemDirectory
import com.discord.widgets.channels.list.items.ChannelListItemEventsSeparator
import com.discord.widgets.channels.list.items.ChannelListItemGuildJoinRequest
import com.discord.widgets.channels.list.items.ChannelListItemGuildRoleSubscriptionsOverview
import com.discord.widgets.channels.list.items.ChannelListItemGuildScheduledEvents
import com.discord.widgets.channels.list.items.ChannelListItemInvite
import com.discord.widgets.channels.list.items.ChannelListItemMfaNotice
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import com.discord.widgets.channels.list.items.ChannelListItemStageAudienceCount
import com.discord.widgets.channels.list.items.ChannelListItemStageVoiceChannel
import com.discord.widgets.channels.list.items.ChannelListItemTextChannel
import com.discord.widgets.channels.list.items.ChannelListItemThread
import com.discord.widgets.channels.list.items.ChannelListItemVoiceChannel
import com.discord.widgets.channels.list.items.ChannelListItemVoiceUser
import com.discord.widgets.guildscheduledevent.GuildScheduledEventLocationInfo

/**
 * Everything the channels sidebar renders for one guild (or for the private-channel list).
 *
 * [items] is the flat row list handed straight to `WidgetChannelsListAdapter`; the remaining
 * fields drive the surrounding chrome (banner, premium hint, empty state).
 */
data class WidgetChannelListModel(
    val selectedGuild: Guild?,
    val items: List<ChannelListItem>,
    val isGuildSelected: Boolean = false,
    val showPremiumGuildHint: Boolean = false,
    val showEmptyState: Boolean = false,
    val guildScheduledEvents: List<GuildScheduledEvent> = emptyList(),
) {

    /**
     * Whether the first row already supplies its own top padding, so the list does not need to
     * add any. Categories, invites, the MFA notice, the events separator and the join-request
     * card all draw their own leading space.
     */
    val startsWithPaddedElement: Boolean
        get() = when (items.firstOrNull()) {
            null -> false
            is ChannelListItemInvite,
            is ChannelListItemMfaNotice,
            is ChannelListItemCategory,
            is ChannelListItemEventsSeparator,
            is ChannelListItemGuildJoinRequest,
            -> true
            else -> false
        }

    /** Which segment of the vertical connector a thread row draws to its parent channel. */
    sealed class ThreadSpineType {
        /** The only thread under its parent: a single elbow. */
        object Single : ThreadSpineType()

        /** First of several: elbow plus a line continuing down. */
        object Start : ThreadSpineType()

        /** Neither first nor last: a straight line plus a tick. */
        object Middle : ThreadSpineType()

        /** Last of several: the line terminates in an elbow. */
        object End : ThreadSpineType()
    }

    /** Selection / unread state shared by text channels and threads. */
    data class TextLikeChannelData(
        val selected: Boolean,
        val mentionCount: Int,
        val unread: Boolean,
        val locked: Boolean,
        val hide: Boolean,
    )

    /** Selection / unread state shared by voice and stage channels. */
    data class VocalChannelData(
        val voiceChannelSelected: Boolean,
        val textChannelSelected: Boolean,
        val mentionCount: Int,
        val unread: Boolean,
        val locked: Boolean,
        val nsfw: Boolean,
        val permission: Long?,
        val numUsersConnected: Int,
        val isGuildRoleSubscriptionLockedChannel: Boolean,
        val isGuildRoleSubscriptionChannel: Boolean,
    )

    /**
     * The guild-level state the row builder reads.
     *
     * The reference passes `com.discord.utilities.channel.GuildChannelsInfo`, which does not
     * exist in this project yet and is owned by another package. This carries exactly the
     * members [guildListBuilder] uses; collapse it into `GuildChannelsInfo` when that lands.
     */
    data class GuildInfo(
        val guild: Guild?,
        val everyoneRole: GuildRole?,
        val guildRoles: Map<Long, GuildRole>?,
        val notificationSettings: ModelNotificationSettings,
        val hideMutedChannels: Boolean,
        val channelPermissions: Map<Long, Long>,
        val ableToInstantInvite: Boolean,
        val unelevated: Boolean,
    )

    /**
     * The live stage state for one stage channel.
     *
     * Stands in for `com.discord.widgets.stage.model.StageChannel`, which belongs to the stage
     * package and is not reconstructed yet.
     */
    data class StageChannelInfo(
        val channel: Channel,
        val stageInstance: StageInstance?,
        val speakerIds: Set<Long>,
        val speakers: List<UserGuildMember>,
        val audienceSize: Int,
        val containsMe: Boolean,
        val isMeSpeaker: Boolean,
    )

    companion object {

        /**
         * Builds the private-channel ("Direct Messages") list.
         *
         * [channels] is expected pre-sorted by most recent message; the reference sorts with
         * `Channel.getSortByMostRecent(mostRecentMessageIds)` before calling in.
         */
        @JvmStatic
        fun privateListBuilder(
            channels: List<Channel>,
            selectedChannelId: Long,
            presences: Map<Long, com.discord.models.presence.Presence>,
            mentionCounts: Map<Long, Int>,
            streamingUserIds: Set<Long>,
            notificationSettings: ModelNotificationSettings,
        ): WidgetChannelListModel {
            val rows = channels.map { channel ->
                val recipientId = channel.recipients?.firstOrNull()?.id
                ChannelListItemPrivate(
                    channel = channel,
                    presence = presences[recipientId],
                    selected = channel.id == selectedChannelId,
                    mentionCount = mentionCounts[channel.id] ?: 0,
                    isApplicationStreaming = recipientId != null && recipientId in streamingUserIds,
                    muted = notificationSettings.getChannelOverride(channel.id)?.isMuted == true,
                )
            }
            return WidgetChannelListModel(
                selectedGuild = null,
                items = rows + ChannelListBottomNavSpaceItem(),
                showEmptyState = rows.isEmpty(),
            )
        }

        /**
         * Builds the guild channel list.
         *
         * The row order is derived entirely from [guildChannels]: channels are bucketed by
         * category (a category lands in its own bucket, ahead of its children, because
         * `getSortByNameAndType` sorts categories first), and each bucket is emitted in turn.
         * Channels the user cannot see, channels hidden by a collapsed category, and categories
         * that ended up with no visible children are dropped as the list is assembled.
         *
         * @param canSeeGuildRoleSubscriptions gate for surfacing locked role-subscription
         *   channels; `false` hides them entirely.
         * @param isGuildRoleSubscriptionChannel the reference calls
         *   `ChannelUtils.isGuildRoleSubscriptionChannel`, which is not reconstructed yet, so
         *   the predicate is injected. Defaults to "no channel is one".
         * @param hubEnabled / @param hubStudyGroupEnabled / @param hubEventsEnabled stand in for
         *   `GrowthTeamFeatures`, which does not exist in this project.
         */
        @JvmStatic
        @Suppress("LongParameterList", "LongMethod", "ComplexMethod")
        fun guildListBuilder(
            selectedGuildId: Long,
            guild: GuildInfo,
            guildChannels: Map<Long, Channel>,
            activeJoinedGuildThreads: Map<Long, Map<Long, ActiveJoinedThread>>,
            joinedThreads: Map<Long, JoinedThread>,
            channelsWithActiveThreads: Set<Long>,
            selectedChannel: Channel?,
            selectedVoiceChannelId: Long,
            voiceStates: Map<Long, Collection<ChannelListItemVoiceUser>>,
            mentionCounts: Map<Long, Int>,
            unreadChannelIds: Set<Long>,
            collapsedCategories: Set<Long>,
            stageChannels: Map<Long, StageChannelInfo> = emptyMap(),
            stageInstances: Map<Long, StageInstance> = emptyMap(),
            guildScheduledEvents: List<GuildScheduledEvent> = emptyList(),
            canCreateAnyEvent: Boolean = false,
            canSeeGuildRoleSubscriptions: Boolean = false,
            directoryUnreadCounts: Map<Long, Int> = emptyMap(),
            guildJoinRequest: GuildJoinRequest? = null,
            member: GuildMember? = null,
            meUserId: Long = 0L,
            isGuildRoleSubscriptionChannel: (Channel) -> Boolean = { false },
            hubEnabled: Boolean = false,
            hubStudyGroupEnabled: Boolean = false,
            hubEventsEnabled: Boolean = false,
        ): List<ChannelListItem> {
            val items = ArrayList<ChannelListItem>(guildChannels.size + joinedThreads.size)
            val isHub = guild.guild?.isHub() == true

            if (guild.unelevated) items.add(ChannelListItemMfaNotice())

            val eventNotice = getChannelEventNoticeData(
                channelPermissions = guild.channelPermissions,
                stageChannels = stageChannels,
                guildScheduledEvents = guildScheduledEvents,
                voiceStates = voiceStates,
                guildChannels = guildChannels,
                selectedVoiceChannelId = selectedVoiceChannelId,
                meUserId = meUserId,
            )
            if (eventNotice != null) {
                items.add(ChannelListItemEventsSeparator(isTop = true))
                items.add(ChannelListItemActiveEvent(eventNotice))
                items.add(ChannelListItemEventsSeparator(isTop = false))
            }

            guild.guild?.let { models ->
                val hasEvents = guildScheduledEvents.isNotEmpty()
                val canCreate = models.hasFeature(GuildFeature.COMMUNITY) && canCreateAnyEvent
                if (hasEvents || canCreate) {
                    items.add(ChannelListItemGuildScheduledEvents(guildScheduledEvents, models))
                }
            }

            if (canSeeGuildRoleSubscriptions) {
                items.add(ChannelListItemGuildRoleSubscriptionsOverview)
            }

            val threadParentMap = sortThreadsForChannelList(activeJoinedGuildThreads, selectedChannel)
            val sortedCategories = sortedCategories(guildChannels)

            /** Categories the user can manage: kept even when they end up empty. */
            val forceViewCategories = HashSet<Long>()

            /** Text channels already dropped, so their threads can be dropped too. */
            val hiddenChannelIds = HashSet<Long>()

            fun isThreadUnread(thread: Channel) = thread.id in unreadChannelIds

            fun areAllChildThreadsRead(parentChannelId: Long) =
                threadParentMap[parentChannelId]?.none(::isThreadUnread) ?: true

            fun areAnyChildThreadsSelected(parentChannelId: Long) =
                selectedChannel != null &&
                    threadParentMap[parentChannelId]?.any { it.id == selectedChannel.id } == true

            /** Drops a category header that collected no visible children. */
            fun removeTrailingEmptyCategory() {
                val last = items.lastOrNull() as? ChannelListItemCategory ?: return
                val categoryId = last.channel.id
                if (categoryId !in forceViewCategories && categoryId !in collapsedCategories) {
                    items.removeAt(items.size - 1)
                }
            }

            for ((categoryId, categoryChannels) in sortedCategories) {
                val categoryPermissions = guild.channelPermissions[categoryId]
                val canViewCategory = PermissionUtils.can(Permission.VIEW_CHANNEL, categoryPermissions)
                val canManageChannels = PermissionUtils.can(Permission.MANAGE_CHANNELS, categoryPermissions)
                if (canViewCategory && canManageChannels) forceViewCategories.add(categoryId)
                val isCategoryMuted =
                    guild.notificationSettings.getChannelOverride(categoryId)?.isMuted == true

                for (channel in categoryChannels) {
                    val channelId = channel.id
                    val permissions = guild.channelPermissions[channelId]
                    val muted =
                        guild.notificationSettings.getChannelOverride(channelId)?.isMuted == true

                    // A hub shows only its directory channels unless study groups are enabled.
                    if (isHub && channel.type != Channel.DIRECTORY && !hubStudyGroupEnabled) continue

                    fun getTextLikeChannelData(
                        textChannel: Channel,
                        isMuted: Boolean,
                        isParentMuted: Boolean? = null,
                    ): TextLikeChannelData {
                        val id = textChannel.id
                        val channelSelected = selectedChannel != null && id == selectedChannel.id
                        val mentionCount = mentionCounts[id] ?: 0
                        val unread = id in unreadChannelIds
                        val locked = textChannel.permissionOverwrites
                            ?.firstOrNull { it.id == selectedGuildId }
                            ?.let { (it.deny and Permission.VIEW_CHANNEL) == Permission.VIEW_CHANNEL }
                            ?: false

                        val hide = if (textChannel.isThread()) {
                            // A thread rides along with its parent: it disappears with the parent,
                            // and while the parent's category is collapsed or the parent is muted
                            // unless it is selected or unread.
                            val parent = guildChannels[textChannel.parentId]
                            if (parent == null) {
                                true
                            } else if (textChannel.parentId in hiddenChannelIds) {
                                true
                            } else {
                                val parentInFocus = (selectedChannel != null && parent.id == selectedChannel.id) ||
                                    areAnyChildThreadsSelected(parent.id)
                                val suppressed = parent.parentId in collapsedCategories ||
                                    isParentMuted == true
                                !parentInFocus && !isThreadUnread(textChannel) && suppressed
                            }
                        } else {
                            val inFocus = channelSelected || areAnyChildThreadsSelected(id)
                            val collapsedAway = textChannel.parentId in collapsedCategories &&
                                mentionCount <= 0 &&
                                !(!isCategoryMuted && !isMuted && unread)
                            val mutedAway = isMuted && guild.hideMutedChannels
                            val result = !inFocus &&
                                areAllChildThreadsRead(id) &&
                                (collapsedAway || mutedAway)
                            if (result) hiddenChannelIds.add(id)
                            result
                        }
                        return TextLikeChannelData(channelSelected, mentionCount, unread, locked, hide)
                    }

                    fun getVocalChannelData(vocalChannel: Channel): VocalChannelData? {
                        val voiceChannelSelected = channelId == selectedVoiceChannelId
                        val textChannelSelected =
                            selectedChannel != null && channelId == selectedChannel.id
                        val numUsersConnected = voiceStates[channelId]?.size ?: 0
                        val canView = PermissionUtils.can(Permission.VIEW_CHANNEL, permissions)
                        val isRoleSubscription = canSeeGuildRoleSubscriptions &&
                            isGuildRoleSubscriptionChannel(vocalChannel)
                        val everyoneOverwrite = vocalChannel.permissionOverwrites
                            ?.firstOrNull { it.id == selectedGuildId }
                        val everyoneRole = guild.everyoneRole
                        val everyoneCanConnect = everyoneRole != null &&
                            PermissionUtils.canRole(Permission.CONNECT, everyoneRole, everyoneOverwrite)
                        val locked = !everyoneCanConnect || (!canView && isRoleSubscription)

                        if (!canView && !voiceChannelSelected && !isRoleSubscription) return null
                        // A collapsed category keeps only the voice channels somebody is in.
                        if (vocalChannel.parentId in collapsedCategories &&
                            numUsersConnected == 0 && !voiceChannelSelected
                        ) {
                            return null
                        }

                        val roleSubscriptionLocked =
                            (!canView || !PermissionUtils.can(Permission.CONNECT, permissions)) &&
                                isRoleSubscription
                        return VocalChannelData(
                            voiceChannelSelected = voiceChannelSelected,
                            textChannelSelected = textChannelSelected,
                            mentionCount = mentionCounts[channelId] ?: 0,
                            unread = channelId in unreadChannelIds,
                            locked = locked,
                            nsfw = vocalChannel.nsfw,
                            permission = permissions,
                            numUsersConnected = numUsersConnected,
                            isGuildRoleSubscriptionLockedChannel = roleSubscriptionLocked,
                            isGuildRoleSubscriptionChannel = isRoleSubscription,
                        )
                    }

                    fun toTextChannelItem(): ChannelListItemTextChannel? {
                        val isRoleSubscription = canSeeGuildRoleSubscriptions &&
                            isGuildRoleSubscriptionChannel(channel)
                        val hasAccess = PermissionUtils.hasAccess(channel, permissions)
                        if (!hasAccess && !isRoleSubscription) return null
                        val data = getTextLikeChannelData(channel, muted)
                        if (data.hide) return null
                        return ChannelListItemTextChannel(
                            channel = channel,
                            selected = data.selected,
                            mentionCount = data.mentionCount,
                            isUnread = data.unread,
                            muted = muted,
                            isLocked = data.locked,
                            hasActiveThreads = channel.id in channelsWithActiveThreads,
                            isGuildRoleSubscriptionLockedChannel = !hasAccess && isRoleSubscription,
                            isGuildRoleSubscriptionChannel = isRoleSubscription,
                        )
                    }

                    fun toThreadItem(thread: Channel, joined: JoinedThread?): ChannelListItemThread? {
                        val threadPermissions = guild.channelPermissions[thread.id]
                        val isParentMuted = guild.notificationSettings
                            .getChannelOverride(thread.parentId)?.isMuted == true
                        val threadMuted = joined?.muted == true
                        if (!PermissionUtils.hasAccess(thread, threadPermissions)) return null
                        val data = getTextLikeChannelData(thread, threadMuted, isParentMuted)
                        if (data.hide) return null
                        return ChannelListItemThread(
                            channel = thread,
                            selected = data.selected,
                            mentionCount = data.mentionCount,
                            isUnread = data.unread,
                            isLocked = data.locked,
                            muted = threadMuted,
                        )
                    }

                    fun threadItemsFor(parentChannel: Channel): List<ChannelListItemThread> {
                        val threads = threadParentMap[parentChannel.id] ?: return emptyList()
                        val rows = threads.mapNotNull { toThreadItem(it, joinedThreads[it.id]) }
                        rows.forEachIndexed { index, row ->
                            row.spineType = spineTypeFor(index, rows.size)
                        }
                        return rows
                    }

                    fun toVoiceChannelItem(): ChannelListItemVoiceChannel? {
                        val data = getVocalChannelData(channel) ?: return null
                        val activeEvent = guildScheduledEvents.firstOrNull {
                            it.status == GuildScheduledEventStatus.ACTIVE && it.channelId == channel.id
                        }
                        val isAnyoneUsingVideo =
                            voiceStates[channel.id]?.any { it.voiceState.selfVideo } == true
                        return ChannelListItemVoiceChannel(
                            channel = channel,
                            textSelected = data.textChannelSelected,
                            voiceSelected = data.voiceChannelSelected,
                            permission = permissions,
                            mentionCount = data.mentionCount,
                            isUnread = data.unread,
                            numUsersConnected = data.numUsersConnected,
                            isLocked = data.locked,
                            isNsfw = data.nsfw,
                            isAnyoneUsingVideo = isAnyoneUsingVideo,
                            guildMaxVideoChannelUsers = guild.guild?.maxVideoChannelUsers
                                ?: GuildMaxVideoChannelUsers.Unlimited,
                            isGuildRoleSubscriptionLockedChannel = data.isGuildRoleSubscriptionLockedChannel,
                            isGuildRoleSubscriptionChannel = data.isGuildRoleSubscriptionChannel,
                            guildScheduledEvent = activeEvent,
                        )
                    }

                    fun toStageChannelItem(): ChannelListItemStageVoiceChannel? {
                        val data = getVocalChannelData(channel) ?: return null
                        return ChannelListItemStageVoiceChannel(
                            channel = channel,
                            selected = data.voiceChannelSelected,
                            permission = permissions,
                            isLocked = data.locked,
                            numUsersConnected = data.numUsersConnected,
                            stageInstance = stageInstances[channel.id],
                            hasSpeakers = stageChannels[channel.id]?.speakerIds?.isNotEmpty() == true,
                            isGuildRoleSubscriptionLockedChannel = data.isGuildRoleSubscriptionLockedChannel,
                            isGuildRoleSubscriptionChannel = data.isGuildRoleSubscriptionChannel,
                        )
                    }

                    /** Only the speakers appear under a stage row; the audience is a count. */
                    fun stageSpeakerItems(): List<ChannelListItemVoiceUser> {
                        val connected = voiceStates[channelId] ?: return emptyList()
                        val speakerIds = stageChannels[channelId]?.speakerIds ?: emptySet()
                        return connected.filter { it.user.id in speakerIds }
                    }

                    when (channel.type) {
                        Channel.GUILD_VOICE -> {
                            val item = toVoiceChannelItem() ?: continue
                            items.add(item)
                            items.addAll(voiceStates[channelId] ?: emptyList())
                        }

                        Channel.CATEGORY -> {
                            removeTrailingEmptyCategory()
                            items.add(
                                ChannelListItemCategory(
                                    channel = channel,
                                    isCollapsed = channelId in collapsedCategories,
                                    isMuted = muted,
                                    canManageChannels = canManageChannels,
                                ),
                            )
                        }

                        Channel.GUILD_STAGE_VOICE -> {
                            val item = toStageChannelItem() ?: continue
                            items.add(item)
                            items.addAll(stageSpeakerItems())
                            val audienceSize = stageChannels[channelId]?.audienceSize
                            if (audienceSize != null &&
                                (audienceSize > 0 || item.hasActiveEvent || item.hasSpeakers)
                            ) {
                                items.add(ChannelListItemStageAudienceCount(channel, audienceSize))
                            }
                        }

                        Channel.DIRECTORY -> {
                            if (!PermissionUtils.hasAccess(channel, permissions) || !hubEnabled) continue
                            val directoryItem = ChannelListItemDirectory(
                                channel = channel,
                                selected = selectedChannel != null && channel.id == selectedChannel.id,
                                unreadCount = minOf(
                                    MAX_DIRECTORY_UNREADS,
                                    directoryUnreadCounts[channelId] ?: 0,
                                ),
                            )
                            if (isHub) {
                                val eventsRow = guild.guild
                                    ?.takeIf { hubEventsEnabled && !channel.isHqDirectoryChannel() }
                                    ?.let { ChannelListItemGuildScheduledEvents(guildScheduledEvents, it) }
                                items.addAll(
                                    listOfNotNull(
                                        eventsRow,
                                        directoryItem,
                                        ChannelListItemAddServer(),
                                        ChannelListItemInvite(selectedGuildId, true),
                                    ),
                                )
                            } else {
                                items.add(directoryItem)
                            }
                        }

                        Channel.GUILD_TEXT, Channel.GUILD_ANNOUNCEMENT, Channel.GUILD_FORUM -> {
                            val item = toTextChannelItem() ?: continue
                            items.add(item)
                            items.addAll(threadItemsFor(channel))
                        }

                        else -> continue
                    }
                }
            }

            // The invite row goes to the very top, below the MFA notice when one is present.
            if (guild.ableToInstantInvite && !isHub) {
                items.add(guild.unelevated.toIndex(), ChannelListItemInvite(selectedGuildId, false))
            }

            if (guildJoinRequest != null && member != null &&
                guildJoinRequest.applicationStatus != ApplicationStatus.APPROVED && member.pending
            ) {
                val index = guild.unelevated.toIndex()
                items.add(index, ChannelListItemEventsSeparator(isTop = false))
                items.add(index, ChannelListItemGuildJoinRequest(guildJoinRequest, member))
            }

            removeTrailingEmptyCategory()
            return items
        }

        /**
         * The "happening now" banner, if anything is live.
         *
         * Priority: a stage the user could join, then an active voice event they are not
         * already in, then an external (off-Discord) event.
         */
        @JvmStatic
        fun getChannelEventNoticeData(
            channelPermissions: Map<Long, Long>,
            stageChannels: Map<Long, StageChannelInfo>,
            guildScheduledEvents: List<GuildScheduledEvent>,
            voiceStates: Map<Long, Collection<ChannelListItemVoiceUser>>,
            guildChannels: Map<Long, Channel>,
            selectedVoiceChannelId: Long,
            meUserId: Long,
        ): ChannelListItemActiveEventData? {
            stageChannels.values.firstOrNull { it.containsMe }?.let { stage ->
                return ChannelListItemActiveEventData(
                    topic = stage.stageInstance?.topic,
                    locationInfo = GuildScheduledEventLocationInfo.ChannelLocation(stage.channel),
                    audienceSize = stage.audienceSize,
                    speakers = stage.speakers,
                    connected = stage.containsMe,
                    isSpeaker = stage.isMeSpeaker,
                    eventId = null,
                )
            }

            val liveVoiceEvent = guildScheduledEvents.firstOrNull { event ->
                event.status == GuildScheduledEventStatus.ACTIVE &&
                    guildChannels[event.channelId]?.type == Channel.GUILD_VOICE &&
                    PermissionUtils.can(Permission.CONNECT, channelPermissions[event.channelId]) &&
                    event.channelId != selectedVoiceChannelId
            }
            if (liveVoiceEvent != null) {
                val channel = guildChannels[liveVoiceEvent.channelId]
                if (channel != null) {
                    val connected = voiceStates[liveVoiceEvent.channelId]
                    val speakers = connected.orEmpty()
                        .map { UserGuildMember(it.user, it.computed) }
                    val meConnected = connected?.any { it.user.id == meUserId } == true
                    if (!meConnected) {
                        return ChannelListItemActiveEventData(
                            topic = liveVoiceEvent.name,
                            locationInfo = GuildScheduledEventLocationInfo.ChannelLocation(channel),
                            audienceSize = 0,
                            speakers = speakers,
                            connected = false,
                            isSpeaker = false,
                            eventId = null,
                        )
                    }
                }
            }

            val externalEvent = guildScheduledEvents.firstOrNull { event ->
                event.status == GuildScheduledEventStatus.ACTIVE &&
                    event.entityType == GuildScheduledEventEntityType.EXTERNAL &&
                    event.entityMetadata?.location != null
            } ?: return null

            return ChannelListItemActiveEventData(
                topic = externalEvent.name,
                locationInfo = GuildScheduledEventLocationInfo.ExternalLocation(
                    externalEvent.entityMetadata?.location.orEmpty(),
                ),
                audienceSize = 0,
                speakers = emptyList(),
                connected = false,
                isSpeaker = false,
                eventId = externalEvent.id,
            )
        }

        /**
         * Buckets channels by category and sorts both the buckets and their contents with
         * `getSortByNameAndType`.
         *
         * A category is filed under its *own* id, so it shares a bucket with its children and
         * — because that comparator puts categories first — always comes out ahead of them.
         * Uncategorised channels land in bucket `0`, which resolves to a `null` channel and
         * therefore sorts first.
         */
        @JvmStatic
        fun sortedCategories(guildChannels: Map<Long, Channel>): Map<Long, Collection<Channel>> {
            val comparator = Channel.getSortByNameAndType()
            val buckets = sortedMapOf<Long, MutableSet<Channel>>(
                Comparator<Long> { first, second ->
                    comparator.compare(guildChannels[first], guildChannels[second])
                },
            )
            for (channel in guildChannels.values) {
                val key = if (channel.isCategory()) channel.id else channel.parentId
                buckets.getOrPut(key) {
                    sortedSetOf(Comparator<Channel> { a, b -> comparator.compare(a, b) })
                }.add(channel)
            }
            return buckets
        }

        /**
         * Groups the user's joined threads by parent channel, newest join first, and makes sure
         * the thread being viewed is present even when it fell out of the active set.
         */
        @JvmStatic
        fun sortThreadsForChannelList(
            threadsMap: Map<Long, Map<Long, ActiveJoinedThread>>,
            selectedChannel: Channel?,
        ): Map<Long, Collection<Channel>> {
            val sorted = threadsMap.mapValues { (_, threads) ->
                threads.values
                    .sortedWith(
                        compareByDescending<ActiveJoinedThread> { it.joinTimestamp.dateTimeMillis },
                    )
                    .map(ActiveJoinedThread::channel)
            }
            if (selectedChannel == null || !selectedChannel.isThread()) return sorted
            val siblings = sorted[selectedChannel.parentId].orEmpty()
            if (siblings.any { it.id == selectedChannel.id }) return sorted
            return sorted.toMutableMap().apply {
                put(selectedChannel.parentId, listOf(selectedChannel) + siblings)
            }
        }

        /** The connector segment for the thread at [position] of [threadCount] siblings. */
        @JvmStatic
        fun spineTypeFor(position: Int, threadCount: Int): ThreadSpineType = when {
            threadCount == 1 -> ThreadSpineType.Single
            position == 0 -> ThreadSpineType.Start
            position == threadCount - 1 -> ThreadSpineType.End
            else -> ThreadSpineType.Middle
        }

        /** The reference hub row shows at most five new servers. */
        private const val MAX_DIRECTORY_UNREADS = 5

        /** Discord's own seeded directory channel, which never gets the events row. */
        private const val HQ_DIRECTORY_CHANNEL_ID = 883060064561299456L

        private fun Channel.isHqDirectoryChannel() = id == HQ_DIRECTORY_CHANNEL_ID

        /** The MFA notice occupies index 0 when present, so inserts go after it. */
        private fun Boolean.toIndex() = if (this) 1 else 0
    }
}
