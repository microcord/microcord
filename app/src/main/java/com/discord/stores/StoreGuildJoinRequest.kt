package com.discord.stores

import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.models.guild.Guild
import com.discord.models.guild.merge
import com.discord.models.domain.ModelInvite
import com.discord.models.domain.ModelPayload
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable
import com.discord.api.guild.Guild as ApiGuild

/**
 * The current user's membership applications ("join requests") to gated community guilds.
 *
 * Two collections, and the split matters:
 *
 * - [guildJoinRequests] is the application itself, keyed by guild. It arrives in `READY` and is
 *   maintained by `GUILD_JOIN_REQUEST_CREATE/UPDATE/DELETE`. Every handler filters on
 *   `userId == me`, because the same events are broadcast to guild moderators for *other* people's
 *   applications and those must not overwrite the user's own state.
 * - [pendingGuilds] is the guild a pending application points at. A guild that screens members
 *   manually never sends a `GUILD_CREATE` — the user is not a member of it, only an applicant — so
 *   `StoreGuilds` has nothing and every screen that names the guild would render blank. Two
 *   payloads carry the missing record and both are folded in here: `GET
 *   /users/@me/join-request-guilds` ([fetchPendingGuilds]) and the guild attached to the
 *   membership-screening form ([handleMemberVerificationGuild]).
 *
 * An application flipping to [ApplicationStatus.APPROVED] with `lastSeen == null` is the moment the
 * "you're in" dialog should be shown exactly once; that is delegated to [onJoinRequestApproved] so
 * this store stays free of UI. `lastSeen` is what makes it once-only — the server records that the
 * user has seen the approval, and an application that comes back already acked is dropped outright
 * rather than kept, which is what retires the guild's row from the pending folder.
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

    /** The guild behind an application, for screens that run while the user is not a member. */
    fun getPendingGuild(guildId: Long): Guild? = pendingGuildsSnapshot[guildId]

    fun observeGuildJoinRequest(guildId: Long): Observable<GuildJoinRequest?> =
        observationDeck.connectRx(this) { guildJoinRequestsSnapshot[guildId] }.distinctUntilChanged()

    fun observeGuildJoinRequests(): Observable<Map<Long, GuildJoinRequest>> =
        observationDeck.connectRx(this) { guildJoinRequestsSnapshot }.distinctUntilChanged()

    fun observePendingGuilds(): Observable<Map<Long, Guild>> =
        observationDeck.connectRx(this) { pendingGuildsSnapshot }.distinctUntilChanged()

    fun observePendingGuild(guildId: Long): Observable<Guild?> =
        observationDeck.connectRx(this) { pendingGuildsSnapshot[guildId] }.distinctUntilChanged()

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
    internal fun handlePendingGuildsLoaded(results: List<ApiGuild>) {
        results.forEach { guild -> putPendingGuild(guild) }
        markChanged()
    }

    /**
     * Accepting an invite to a guild that screens members manually.
     *
     * The accept response already carries the application it created and the guild it belongs to,
     * so seeding them here means the screen the user is about to land on has both immediately
     * rather than after `GUILD_JOIN_REQUEST_CREATE` and a round trip for the guild.
     */
    fun handleInviteAccepted(invite: ModelInvite) = dispatcher.schedule {
        val request = invite.getJoinRequest()
        val guild = invite.guild
        if (request == null && guild == null) return@schedule
        if (request != null && request.userId == userStore.getMeInternal().id && !request.isApprovedAndAcked) {
            guildJoinRequests[request.guildId] = request
        }
        if (guild != null && guildsStore.getGuild(guild.id) == null) putPendingGuild(guild)
        markChanged()
    }

    /**
     * The partial guild the membership-screening form carries.
     *
     * For a manual-approval guild this arrives before — and often instead of — the join-request
     * guild list, so the screen that asked for the form can name the guild it is asking about.
     */
    @StoreThread
    fun handleMemberVerificationGuild(guild: ApiGuild) {
        putPendingGuild(guild)
        markChanged()
    }

    @StoreThread
    private fun putPendingGuild(guild: ApiGuild) {
        // Merged onto whatever is already held: the two payloads carry different subsets, and the
        // later one must not blank a field the earlier one supplied.
        pendingGuilds[guild.id] = (pendingGuilds[guild.id] ?: Guild()).merge(guild)
    }

    /**
     * `READY`. Deliberately *merges* rather than replaces, matching the reference: a resumed
     * session re-sends the same applications, and clearing first would blank the pending rows for
     * one dispatch. [reset] is the clearing path.
     */
    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        payload.guildJoinRequests?.forEach { request ->
            if (request.isApprovedAndAcked) {
                guildJoinRequests.remove(request.guildId)
            } else {
                guildJoinRequests[request.guildId] = request
            }
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
        if (request.isApprovedAndAcked) {
            guildJoinRequests.remove(guildId)
            pendingGuilds.remove(guildId)
            markChanged()
            return
        }
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
