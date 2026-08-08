package com.discord.stores

import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.models.domain.ModelInvite
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * Invites, in two unrelated roles that happen to share a store.
 *
 * - **[invites]** — the invites a guild has *issued*, keyed `guildId -> code -> invite`. This backs
 *   the server-settings invite list and is replaced wholesale by [fetchGuildInvites].
 * - **[knownInvites]** — resolution of an invite *code* the user has encountered, keyed by
 *   [ModelInvite.getInviteStoreKey]. This backs every invite card in chat and the invite-accept
 *   screen.
 *
 * The key is the code **plus the scheduled-event id**, not the code alone: the same invite link
 * resolves to different content depending on `?event=`, and collapsing them would make one card
 * overwrite the other.
 *
 * The error split gives the invite card its three visual states:
 * - A dead code is terminal, and toasts are suppressed because the card renders the failure itself.
 * - [RestApiError.Type.NETWORK] — the request never landed. [fetchInviteIfNotLoaded] retries over
 *   this state, so the card recovers when connectivity returns.
 * - Anything else leaves the key on [InviteState.Loading].
 *
 * A resolved invite that carries a scheduled event hands it to [onGuildScheduledEventResolved], so
 * the event store can answer for an event in a guild the user has not joined.
 */
class StoreInstantInvites(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    /** Reference: `StoreStream.getGuildScheduledEvents().addGuildScheduledEventFromInvite(event)`. */
    private val onGuildScheduledEventResolved: (GuildScheduledEvent) -> Unit = {},
) : StoreV2() {

    sealed class InviteState {
        object Loading : InviteState()

        /** Transport failure — retried on the next [fetchInviteIfNotLoaded]. */
        object LoadFailed : InviteState()

        /** The server rejected the code. Terminal. */
        object Invalid : InviteState()
        data class Resolved(val invite: ModelInvite) : InviteState()
    }

    private val invites = LinkedHashMap<Long, MutableMap<String, ModelInvite>>()
    private var invitesSnapshot: Map<Long, Map<String, ModelInvite>> = emptyMap()

    private val knownInvites = LinkedHashMap<String, InviteState>()
    private var knownInvitesSnapshot: Map<String, InviteState> = emptyMap()

    // region guild invite list

    fun getInvites(guildId: Long): Map<String, ModelInvite> = invitesSnapshot[guildId].orEmpty()

    fun observeInvites(guildId: Long): Observable<Map<String, ModelInvite>> =
        observationDeck.connectRx(this) { getInvites(guildId) }.distinctUntilChanged()

    fun fetchGuildInvites(guildId: Long) {
        val api = restApi() ?: return
        api.getGuildInvites(guildId)
            .restSubscribeOn(false)
            .appSubscribe(StoreInstantInvites::class.java) { loaded ->
                dispatcher.schedule { onLoadedInvites(loaded) }
            }
    }

    /**
     * Clears the *contents* of the guild's invite list but keeps the entry, so a screen can tell
     * "revoked everything" from "never fetched".
     */
    fun clearInvites(guildId: Long) = dispatcher.schedule {
        invites[guildId]?.clear()
        markChanged()
    }

    /** Applied optimistically after a successful revoke. */
    fun onInviteRemoved(invite: ModelInvite) = dispatcher.schedule {
        val guildId = invite.guild?.id ?: return@schedule
        val code = invite.code ?: return@schedule
        if (invites[guildId]?.remove(code) != null) markChanged()
    }

    /**
     * Rebuilds the whole index. Invites without a guild — group-DM invites — are dropped, because
     * the index is keyed by guild.
     */
    @StoreThread
    internal fun onLoadedInvites(updatedInvites: List<ModelInvite>) {
        invites.clear()
        updatedInvites.forEach { invite ->
            val guildId = invite.guild?.id ?: return@forEach
            val code = invite.code ?: return@forEach
            invites.getOrPut(guildId, ::LinkedHashMap)[code] = invite
        }
        markChanged()
    }

    // endregion

    // region received installation ids

    /**
     * Reference `InstantInviteStore`'s received-installation-id map: the `installationId` a deep
     * link carried, held until the matching invite is accepted and then cleared.
     */
    private val receivedInstallationIds = LinkedHashMap<String, String>()

    fun setReceivedInstallationId(inviteCode: String, installationId: String) = dispatcher.schedule {
        receivedInstallationIds[inviteCode] = installationId
    }

    fun getReceivedInstallationId(inviteCode: String): String? = receivedInstallationIds[inviteCode]

    fun clearReceivedInstallationId(inviteCode: String) = dispatcher.schedule {
        receivedInstallationIds.remove(inviteCode)
    }

    // endregion

    // region code resolution

    fun getKnownInvites(): Map<String, InviteState> = knownInvitesSnapshot

    /**
     * `null` code resolves to [InviteState.LoadFailed] rather than throwing — a chat row can bind
     * before its code has been parsed out.
     */
    fun observeInvite(inviteKey: String?): Observable<InviteState> {
        if (inviteKey == null) return Observable.just(InviteState.LoadFailed)
        return observationDeck.connectRx(this) { getKnownInvites() }
            .map { known -> known[inviteKey] ?: InviteState.Loading }
            .distinctUntilChanged()
    }

    fun observeKnownInvites(): Observable<Map<String, InviteState>> =
        observationDeck.connectRx(this) { getKnownInvites() }.distinctUntilChanged()

    /**
     * Resolves [inviteCode] unless it is already known. A previous [InviteState.LoadFailed] does
     * *not* count as known, so a transport failure retries.
     *
     * @param eventId scopes the resolution to a scheduled event; part of the cache key.
     * @param targetChannelId deep-link context the server validates and echoes back on the invite.
     * @param onError invoked before the state transition, on the REST thread, for callers that
     *   need to dismiss a screen.
     *
     * The reference also takes `location: String?` and `inviteResolved: Boolean?`. Both feed
     * `AnalyticsTracker` only — no analytics layer exists in this target — so they are omitted
     * rather than carried as dead parameters.
     */
    @JvmOverloads
    fun fetchInviteIfNotLoaded(
        inviteCode: String,
        eventId: Long? = null,
        targetChannelId: Long? = null,
        targetMessageId: Long? = null,
        onError: (() -> Unit)? = null,
    ) {
        val inviteKey = ModelInvite.getInviteStoreKey(inviteCode, eventId)
        dispatcher.schedule {
            val known = knownInvites[inviteKey]
            if (known != null && known !is InviteState.LoadFailed) return@schedule
            setChatInvites(inviteKey, InviteState.Loading)
            val api = restApi() ?: return@schedule
            api.getInviteCode(
                code = inviteCode,
                guildScheduledEventId = eventId,
                targetChannelId = targetChannelId,
                targetMessageId = targetMessageId,
            )
                .restSubscribeOn(false)
                .appSubscribe(
                    StoreInstantInvites::class.java,
                    errorHandler = { error ->
                        onError?.invoke()
                        when (error.type) {
                            RestApiError.Type.DISCORD_REQUEST_ERROR,
                            RestApiError.Type.DISCORD_REQUEST_RESOURCE_NOT_FOUND,
                            -> {
                                error.showErrorToasts = false
                                dispatcher.schedule { setChatInvites(inviteKey, InviteState.Invalid) }
                            }
                            RestApiError.Type.NETWORK ->
                                dispatcher.schedule { setChatInvites(inviteKey, InviteState.LoadFailed) }
                            else -> Unit
                        }
                    },
                ) { invite ->
                    dispatcher.schedule {
                        setChatInvites(inviteKey, InviteState.Resolved(invite))
                        invite.getGuildScheduledEvent()?.let(onGuildScheduledEventResolved)
                    }
                }
        }
    }

    @StoreThread
    internal fun setChatInvites(inviteKey: String, inviteState: InviteState) {
        knownInvites[inviteKey] = inviteState
        markChanged()
    }

    // endregion

    @StoreThread
    internal fun reset() {
        invites.clear()
        knownInvites.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        invitesSnapshot = invites.mapValues { (_, byCode) -> HashMap(byCode) }
        knownInvitesSnapshot = HashMap(knownInvites)
    }
}
