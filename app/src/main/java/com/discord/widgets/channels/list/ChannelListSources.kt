package com.discord.widgets.channels.list

import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.stores.StoreThreadsActiveJoined.ActiveJoinedThread
import com.discord.stores.StoreThreadsJoined.JoinedThread
import com.discord.widgets.channels.list.items.ChannelListItemVoiceUser
import com.discord.widgets.voice.VoiceStoreAccess
import rx.Observable

/**
 * The store inputs of [WidgetChannelListModel.get] that `StoreStream` does not publish yet.
 *
 * Every store listed here **already exists** under `com.discord.stores`; what is missing is the
 * `StoreStream.Companion` accessor (and, for several of them, the construction site inside
 * `StoreStream` itself). Rather than let the sidebar silently render a wrong list, each input is a
 * single named provider with an inert default, so the effect of the gap is visible in one place
 * and closing it is a one-line change per entry.
 *
 * This is a seam, not an architecture: when `StoreStream` grows the accessors, replace each
 * default body with the real call and delete the property.
 *
 * | Provider | Needs on `StoreStream` | Effect while inert |
 * |---|---|---|
 * | [voiceUsers] | `getVoiceStates()`, `getApplicationStreaming()` | voice rows list no participants |
 * | [joinedThreads] / [activeJoinedThreads] / [channelsWithActiveThreads] | `getThreadsJoined()`, `getThreadsActiveJoined()`, `getThreadsActive()` | no thread rows, and text channels never show the "has threads" icon |
 * | [streamingUserIds] | `getApplicationStreaming()` | DM rows never show "streaming" |
 * | [selectedVoiceChannelId] | `getVoiceChannelSelected()` | the joined voice channel is never highlighted, and a collapsed category hides it |
 * | [guildScheduledEvents] | `getGuildScheduledEvents()` | no events row, no "happening now" banner for scheduled events |
 * | [stageChannels] | `getStageChannels()` | stage rows show no speakers and no audience count |
 * | [guildJoinRequest] | `getGuildJoinRequests()` | membership-screening card never appears |
 * | [canSeeGuildRoleSubscriptions] | `GuildRoleSubscriptionsFeatureFlag` | role-subscription rows stay hidden |
 * | [canCreateAnyEvent] | `GuildScheduledEventUtilities` | the events row is hidden in guilds with no events |
 * | [showPremiumGuildHint] | `getNux()` | the boost hint bubble never shows |
 * | [hubEnabled] / [hubStudyGroupEnabled] / [hubEventsEnabled] | `GrowthTeamFeatures` | hub guilds render as ordinary guilds |
 */
object ChannelListSources {

    /**
     * Connected participants per voice/stage channel id, pre-sorted for display.
     *
     * Live as soon as `StoreStream` calls `VoiceStoreAccess.install(...)`.
     */
    @JvmField
    var voiceUsers: (guildId: Long) -> Observable<Map<Long, List<ChannelListItemVoiceUser>>> =
        { guildId -> WidgetChannelListModel.VoiceStates.get(guildId) }

    /** Live once `VoiceStoreAccess` is installed. */
    @JvmField
    var selectedVoiceChannelId: () -> Observable<Long> = {
        StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId()
    }

    /** `parentChannelId -> threadId -> thread`, newest join first. */
    @JvmField
    var activeJoinedThreads: (guildId: Long) -> Observable<Map<Long, Map<Long, ActiveJoinedThread>>> =
        { Observable.just(emptyMap()) }

    /** Per-thread mute state, keyed by thread id. */
    @JvmField
    var joinedThreads: () -> Observable<Map<Long, JoinedThread>> = { Observable.just(emptyMap()) }

    /** Channels with at least one active thread, which changes the channel's icon. */
    @JvmField
    var channelsWithActiveThreads: (guildId: Long) -> Observable<Set<Long>> =
        { Observable.just(emptySet()) }

    /** Users currently streaming an application. Live once `VoiceStoreAccess` is installed. */
    @JvmField
    var streamingUserIds: () -> Observable<Set<Long>> = {
        StoreStream.getApplicationStreaming().observeStreamsByUser().map { it.keys }
    }

    @JvmField
    var guildScheduledEvents: (guildId: Long) -> Observable<List<GuildScheduledEvent>> =
        { Observable.just(emptyList()) }

    @JvmField
    var stageChannels: (guildId: Long) -> Observable<Map<Long, WidgetChannelListModel.StageChannelInfo>> =
        { Observable.just(emptyMap()) }

    @JvmField
    var guildJoinRequest: (guildId: Long) -> Observable<GuildJoinRequest?> =
        { Observable.just(null) }

    @JvmField
    var canSeeGuildRoleSubscriptions: (guildId: Long) -> Observable<Boolean> =
        { Observable.just(false) }

    @JvmField
    var canCreateAnyEvent: (guildId: Long) -> Observable<Boolean> = { Observable.just(false) }

    @JvmField
    var showPremiumGuildHint: (guildId: Long) -> Observable<Boolean> = { Observable.just(false) }

    /** Unread "new server" counts per hub directory channel. */
    @JvmField
    var directoryUnreadCounts: (guildId: Long) -> Observable<Map<Long, Int>> =
        { Observable.just(emptyMap()) }

    @JvmField
    var hubEnabled: () -> Boolean = { false }

    @JvmField
    var hubStudyGroupEnabled: () -> Boolean = { false }

    @JvmField
    var hubEventsEnabled: () -> Boolean = { false }
}
