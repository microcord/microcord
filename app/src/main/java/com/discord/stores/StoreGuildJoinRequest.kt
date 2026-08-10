package com.discord.stores

import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.api.guild.Guild
import com.discord.models.domain.ModelPayload
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The current user's membership applications ("join requests") to gated community guilds.
 *
 * Two collections, and the split matters:
 *
 * - [guildJoinRequests] is the application itself, keyed by guild. It arrives in `READY` and is
 *   maintained by `GUILD_JOIN_REQUEST_CREATE/UPDATE/DELETE`. Every handler filters on
 *   `userId == me`, because the same events are broadcast to guild moderators for *other* people's
 *   applications and those must not overwrite the user's own state.
 * - [pendingGuilds] is the guild a pending application points at. While an application is pending
 *   the user is not a member, so `StoreGuilds` has nothing for that id and the "pending" row in the
 *   guild list would render blank. [fetchPendingGuilds] fills that gap from
 *   `GET /users/@me/join-request-guilds`, and only when at least one request references a guild
 *   that is neither joined nor already fetched.
 *
 * **Divergence:** [pendingGuilds] holds the wire [Guild] rather than the reference's
 * `com.discord.models.guild.Guild`. The api-to-model conversion in this target is
 * `StoreGuilds.merge`, which is private; switch the value type once that conversion is exposed.
 *
 * An application flipping to [ApplicationStatus.APPROVED] with `lastSeen == null` is the moment the
 * "you're in" dialog should be shown exactly once; that is delegated to [onJoinRequestApproved] so
 * this store stays free of UI. `lastSeen` is what makes it once-only — the server records that the
 * user has seen the approval and subsequent payloads carry a non-null value.
 */
class StoreGuildJoinRequest(
    private val userStore: StoreUser,
    private val guildsStore: StoreGuilds,
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    /**
     * Enqueues the member-verification success dialog after production confirms the guild exists.
     */
    private val onJoinRequestApproved: (guildId: Long) -> Unit = {},
) : StoreV2() {

    private val guildJoinRequests = HashMap<Long, GuildJoinRequest>()
    private var guildJoinRequestsSnapshot: Map<Long, GuildJoinRequest> = emptyMap()
    private val pendingGuilds = HashMap<Long, Guild>()
    private var pendingGuildsSnapshot: Map<Long, Guild> = emptyMap()

    fun getGuildJoinRequest(guildId: Long): GuildJoinRequest? = guildJoinRequestsSnapshot[guildId]

    fun getGuildJoinRequests(): Map<Long, GuildJoinRequest> = guildJoinRequestsSnapshot

    fun getPendingGuilds(): Map<Long, Guild> = pendingGuildsSnapshot

    fun observeGuildJoinRequest(guildId: Long): Observable<GuildJoinRequest?> =
        observationDeck.connectRx(this) { guildJoinRequestsSnapshot[guildId] }.distinctUntilChanged()

    fun observeGuildJoinRequests(): Observable<Map<Long, GuildJoinRequest>> =
        observationDeck.connectRx(this) { guildJoinRequestsSnapshot }.distinctUntilChanged()

    fun observePendingGuilds(): Observable<Map<Long, Guild>> =
        observationDeck.connectRx(this) { pendingGuildsSnapshot }.distinctUntilChanged()

    /**
     * Fetches the guilds behind pending applications, but only if at least one application points
     * at a guild that is neither joined nor already fetched — otherwise the call is skipped.
     */
    fun fetchPendingGuilds() = dispatcher.schedule {
        val joined = guildsStore.getGuilds()
        val missing = guildJoinRequests.keys.filter { it !in joined && it !in pendingGuilds }
        if (missing.isEmpty()) return@schedule
        val api = restApi() ?: return@schedule
        api.getUserJoinRequestGuilds()
            .restSubscribeOn(false)
            .appSubscribe(StoreGuildJoinRequest::class.java) { results ->
                dispatcher.schedule { handlePendingGuildsLoaded(results) }
            }
    }

    @StoreThread
    internal fun handlePendingGuildsLoaded(results: List<Guild>) {
        results.forEach { guild -> pendingGuilds[guild.id] = guild }
        markChanged()
    }

    /**
     * `READY`. Deliberately *merges* rather than replaces, matching the reference: a resumed
     * session re-sends the same applications, and clearing first would blank the pending rows for
     * one dispatch. [reset] is the clearing path.
     */
    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        payload.guildJoinRequests?.forEach { request ->
            guildJoinRequests[request.guildId] = request
        }
        markChanged()
    }

    /** Logout. */
    @StoreThread
    internal fun reset() {
        guildJoinRequests.clear()
        pendingGuilds.clear()
        markChanged()
    }

    @StoreThread
    fun handleGuildJoinRequestCreateOrUpdate(guildId: Long, request: GuildJoinRequest) {
        if (request.userId != userStore.getMeInternal().id) return
        guildJoinRequests[guildId] = request
        if (request.applicationStatus == ApplicationStatus.APPROVED && request.lastSeen == null) {
            onJoinRequestApproved(guildId)
        }
        markChanged()
    }

    @StoreThread
    fun handleGuildJoinRequestDelete(userId: Long, guildId: Long) {
        if (userId != userStore.getMeInternal().id) return
        guildJoinRequests.remove(guildId)
        pendingGuilds.remove(guildId)
        markChanged()
    }

    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        if (guildJoinRequests.remove(guildId) != null) markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        guildJoinRequestsSnapshot = HashMap(guildJoinRequests)
        pendingGuildsSnapshot = HashMap(pendingGuilds)
    }
}
