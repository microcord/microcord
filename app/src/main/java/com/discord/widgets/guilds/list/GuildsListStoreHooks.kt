package com.discord.widgets.guilds.list

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.MemberVerificationUtils
import rx.Observable

/**
 * The guild-sidebar inputs that nothing in this project publishes yet.
 *
 * The reference reads all of these straight off `StoreStream` (`StoreLurking`,
 * `StoreVoiceChannelSelected`, `StoreGuildJoinRequest`, `StoreDirectories`,
 * `StreamContextService`, `GuildIdsWithVisibleStageInstanceModel`, `GuildIdsWithActiveEventsModel`)
 * and off `GrowthTeamFeatures` / `MemberVerificationUtils`. In this project some of those stores
 * exist but are not exposed by `StoreStream`, and the rest have not been reconstructed at all — so
 * [WidgetGuildsListViewModel] reads them through this object instead.
 *
 * Every default is inert: an empty stream, a disabled feature, or a no-op. That degrades the
 * sidebar visibly (no voice ring, no Go Live dot, no stage/event indicator, no pending-guild
 * folder, no student-hub row, and a pending guild simply does not open) rather than silently
 * showing wrong state. **Each entry disappears the moment its real source lands** — install it once
 * during app start-up and delete the corresponding field.
 *
 * Suppliers rather than values, so installation order does not matter: the sidebar resolves each
 * one when it subscribes.
 */
object GuildsListStoreHooks {

    /** `StoreLurking.getLurkingGuildIds()` — the store exists, `StoreStream` does not publish it. */
    @Volatile
    var lurkingGuildIds: () -> Observable<Set<Long>> = { Observable.just(emptySet()) }

    /**
     * `StoreVoiceChannelSelected.observeSelectedVoiceChannelId()` — the store exists, `StoreStream`
     * does not publish it. While this is `0` no guild ever shows the voice-connected ring.
     */
    @Volatile
    var selectedVoiceChannelId: () -> Observable<Long> = { Observable.just(0L) }

    /** `StoreGuildJoinRequest.observeGuildJoinRequests()`. Installed by [install]. */
    @Volatile
    var guildJoinRequests: () -> Observable<Map<Long, GuildJoinRequest>> = { Observable.just(emptyMap()) }

    /** `StoreGuildJoinRequest.observePendingGuilds()`, mapped to a list in store order. */
    @Volatile
    var pendingGuilds: () -> Observable<List<Guild>> = { Observable.just(emptyList()) }

    /** `StoreGuildJoinRequest.fetchPendingGuilds()`, fired while an application's guild is unknown. */
    @Volatile
    var fetchPendingGuilds: () -> Unit = {}

    /** `GuildIdsWithVisibleStageInstanceModel.observe()`. */
    @Volatile
    var guildIdsWithActiveStageEvents: () -> Observable<Set<Long>> = { Observable.just(emptySet()) }

    /** `GuildIdsWithActiveEventsModel.observe()`. */
    @Volatile
    var guildIdsWithActiveScheduledEvents: () -> Observable<Set<Long>> = { Observable.just(emptySet()) }

    /**
     * The channels currently being streamed to, one entry per live stream.
     *
     * The reference passes `Map<Long, StreamContext>` from `StreamContextService`, and reads
     * `context.guild.id` and `context.stream.channelId` off it. The target's `StreamContext` is a
     * stub that carries neither, so the two fields the sidebar actually needs are modelled
     * directly. **Replace with `StreamContextService.getForAllStreamingUsers()` once
     * `StreamContext` carries its guild and channel.**
     */
    @Volatile
    var applicationStreams: () -> Observable<List<GuildStream>> = { Observable.just(emptyList()) }

    /** `GrowthTeamFeatures.isHubEnabled()` — when false, tapping a hub guild toasts instead. */
    @Volatile
    var isHubEnabled: () -> Boolean = { true }

    /**
     * `MemberVerificationUtils.maybeShowVerificationGate(...)`, returning whether a gate was shown.
     *
     * Tapping a guild whose join request is still pending must land on the membership-screening
     * gate, never on the guild itself. The default returns `false`, which drops the tap rather than
     * opening a guild the user has not been admitted to; [install] replaces it.
     */
    @Volatile
    var showMemberVerificationGate: (Context, FragmentManager, Long) -> Boolean = { _, _, _ -> false }

    /** One live application stream, reduced to what the sidebar needs. */
    data class GuildStream(val guildId: Long, val channelId: Long)

    /**
     * Points the membership-application entries at their real sources.
     *
     * Called once from application start-up, after `StoreStream.initialize`. Until it runs the
     * sidebar has no pending-applications folder at all, because every default above is inert.
     */
    fun install() {
        guildJoinRequests = { StoreStream.getGuildJoinRequests().observeGuildJoinRequests() }
        pendingGuilds = {
            StoreStream.getGuildJoinRequests().observePendingGuilds()
                .map { guilds -> guilds.values.sortedBy { it.name.lowercase() } }
        }
        fetchPendingGuilds = { StoreStream.getGuildJoinRequests().fetchPendingGuilds() }
        showMemberVerificationGate = { context, fragmentManager, guildId ->
            MemberVerificationUtils.maybeShowVerificationGate(context, fragmentManager, guildId)
        }
    }
}
