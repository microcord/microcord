package com.discord.widgets.channels.list

import com.discord.stores.StoreStream
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.stores.StoreThreadsActiveJoined.ActiveJoinedThread
import com.discord.stores.StoreThreadsJoined.JoinedThread
import com.discord.utilities.guildscheduledevent.GuildScheduledEventUtilities
import com.discord.widgets.channels.list.items.ChannelListItemVoiceUser
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionsFeatureFlag
import com.discord.widgets.stage.model.StageChannel
import rx.Observable

/**
 * Replaceable inputs used by [WidgetChannelListModel.get].
 *
 * The indirection keeps the model builder deterministic in tests. Production defaults are live
 * store/feature sources except for directory unread counts, whose owning source is not present in
 * the target yet.
 */
object ChannelListSources {

    /**
     * Connected participants per voice/stage channel id, pre-sorted for display.
     *
     * Built from the live voice-state, member, user, stream, channel and permission stores.
     */
    @JvmField
    var voiceUsers: (guildId: Long) -> Observable<Map<Long, List<ChannelListItemVoiceUser>>> =
        { guildId -> WidgetChannelListModel.VoiceStates.get(guildId) }

    /** The currently joined voice channel, or `0` when disconnected. */
    @JvmField
    var selectedVoiceChannelId: () -> Observable<Long> = {
        StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId()
    }

    /** `parentChannelId -> threadId -> thread`, newest join first. */
    @JvmField
    var activeJoinedThreads: (guildId: Long) -> Observable<Map<Long, Map<Long, ActiveJoinedThread>>> =
        { StoreStream.getThreadsActiveJoined().observeActiveJoinedThreadsForGuild(it) }

    /** Per-thread mute state, keyed by thread id. */
    @JvmField
    var joinedThreads: () -> Observable<Map<Long, JoinedThread>> = {
        StoreStream.getThreadsJoined().observeJoinedThreads()
    }

    /** Channels with at least one active thread, which changes the channel's icon. */
    @JvmField
    var channelsWithActiveThreads: (guildId: Long) -> Observable<Set<Long>> = {
        StoreStream.getThreadsActive().observeChannelsWithActiveThreadsByGuild(it)
    }

    /** Users currently streaming an application. Live once `VoiceStoreAccess` is installed. */
    @JvmField
    var streamingUserIds: () -> Observable<Set<Long>> = {
        StoreStream.getApplicationStreaming().observeStreamsByUser().map { it.keys }
    }

    @JvmField
    var guildScheduledEvents: (guildId: Long) -> Observable<List<GuildScheduledEvent>> = {
        StoreStream.getGuildScheduledEvents().observeGuildScheduledEvents(it, false)
    }

    @JvmField
    var stageChannels: (guildId: Long) -> Observable<Map<Long, StageChannel>> = {
        StoreStream.getStageChannels().observeGuildStageChannels(it).map { channels ->
            channels.mapValues { (_, stage) ->
                StageChannel(
                    channel = stage.channel,
                    participants = stage.participants,
                    myRoles = stage.myRoles,
                    speakerIds = stage.speakerIds,
                    speakers = stage.speakers,
                    audienceSize = stage.audienceSize,
                    stageInstance = stage.stageInstance,
                    canAccess = stage.canAccess,
                    containsMe = stage.containsMe,
                )
            }
        }
    }

    @JvmField
    var guildJoinRequest: (guildId: Long) -> Observable<GuildJoinRequest?> = {
        StoreStream.getGuildJoinRequests().observeGuildJoinRequest(it)
    }

    @JvmField
    var canSeeGuildRoleSubscriptions: (guildId: Long) -> Observable<Boolean> = {
        GuildRoleSubscriptionsFeatureFlag.INSTANCE.observeCanGuildSeeGuildRoleSubscriptions(it)
    }

    @JvmField
    var canCreateAnyEvent: (guildId: Long) -> Observable<Boolean> = {
        GuildScheduledEventUtilities.observeCanCreateAnyEvent(it)
    }

    @JvmField
    var showPremiumGuildHint: (guildId: Long) -> Observable<Boolean> = { guildId ->
        StoreStream.getNux().getNuxState().map { it.guildBoostHintGuildId == guildId }
    }

    /** Unread "new server" counts per hub directory channel. */
    @JvmField
    var directoryUnreadCounts: (guildId: Long) -> Observable<Map<Long, Int>> =
        { Observable.just(emptyMap()) }

    @JvmField
    var hubEnabled: () -> Boolean = { true }

    @JvmField
    var hubStudyGroupEnabled: () -> Boolean = {
        StoreStream.getExperiments()
            .getUserExperiment("2021-10_study_group", true)
            ?.bucket == 1
    }
}
