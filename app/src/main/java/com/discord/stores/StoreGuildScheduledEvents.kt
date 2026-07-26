package com.discord.stores

import com.discord.api.guild.Guild
import com.discord.api.guildscheduledevent.ApiGuildScheduledEventUser
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.guildscheduledevent.GuildScheduledEventUserUpdate
import com.discord.api.permission.Permission
import com.discord.models.domain.ModelPayload
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import rx.Observable

/**
 * One person who has RSVP'd to a guild scheduled event.
 *
 * **Belongs in `com.discord.widgets.guildscheduledevent`**, where the reference declares it; that
 * package exists here but has no store-facing model yet, and this file may only add types under
 * `com.discord.stores`. Move it when the event-details screen is rebuilt.
 */
data class GuildScheduledEventUser(
    val user: User,
    val guildMember: GuildMember,
    val guildScheduledEventId: Long,
) {
    companion object {
        /** `null` when the server sent neither the user nor the member half. */
        fun from(
            apiGuildScheduledEventUser: ApiGuildScheduledEventUser,
            guildId: Long,
        ): GuildScheduledEventUser? {
            val user = apiGuildScheduledEventUser.user ?: return null
            val member = apiGuildScheduledEventUser.toGuildMember(guildId) ?: return null
            return GuildScheduledEventUser(
                CoreUser(user),
                GuildMember.from(member, guildId),
                apiGuildScheduledEventUser.guildScheduledEventId,
            )
        }
    }
}

/**
 * Guild scheduled events, their RSVP counts, and which of them the current user has RSVP'd to.
 *
 * Five collections, each with a distinct lifetime:
 *
 * - [guildScheduledEvents] — the events themselves, `guildId -> ordered list`. Seeded from `READY`
 *   and `GUILD_CREATE`, maintained by `GUILD_SCHEDULED_EVENT_CREATE/UPDATE/DELETE`, and refreshed
 *   with real user counts by [fetchGuildScheduledEventUserCounts].
 * - [meGuildScheduledEventIds] — the current user's RSVPs, per guild. Kept separately because a
 *   gateway event never says "and you are attending"; that comes from `GET
 *   /users/@me/scheduled-events` or from the `userRsvp` field on an invite-resolved event.
 * - [guildScheduledEventUsers] — the attendee list of one event, for the RSVP sheet.
 * - The three `*Fetches` / `*FetchTimestamps` sets — de-duplication, so a screen that re-binds does
 *   not re-request. Counts are re-fetched after [FETCH_GUILD_EVENTS_THRESHOLD]; the other two are
 *   once per session.
 * - [rsvpsAwaitingNetwork] and [rsvpsAwaitingSnapshot] — the optimistic-RSVP guard, described on
 *   [toggleMeRsvpForEvent].
 *
 * The `UPDATE` payload deliberately does **not** overwrite a known user count: the gateway omits
 * `user_count`, so [processGuildScheduledEventCreateOrUpdate] carries the previous count forward
 * rather than letting the attendee number flicker to zero on every edit.
 */
class StoreGuildScheduledEvents(
    private val dispatcher: Dispatcher,
    private val permissionsStore: StorePermissions,
    private val userStore: StoreUser,
    private val guildsStore: StoreGuilds,
    private val clock: Clock,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    private val guildScheduledEvents = HashMap<Long, MutableList<GuildScheduledEvent>>()
    private var guildScheduledEventsSnapshot: Map<Long, List<GuildScheduledEvent>> = emptyMap()

    private val meGuildScheduledEventIds = HashMap<Long, MutableSet<Long>>()
    private var meGuildScheduledEventIdsSnapshot: Map<Long, Set<Long>> = emptyMap()

    private val guildScheduledEventUsers = HashMap<Long, HashMap<Long, GuildScheduledEventUser>>()
    private var guildScheduledEventUsersSnapshot: Map<Long, Map<Long, GuildScheduledEventUser>> = emptyMap()

    private val lastAckedGuildScheduledEventIds = HashMap<Long, Long>()
    private val guildScheduledEventsFetchTimestamps = HashMap<Long, Long>()
    private val meGuildScheduledEventsFetches = HashSet<Long>()
    private val guildScheduledEventUsersFetches = HashMap<Long, MutableSet<Long>>()

    private var isFetchingGuildScheduledEventUsers = false
    private var isGuildScheduledEventUsersError = false

    /** Toggles in flight over the network. */
    private val rsvpsAwaitingNetwork = HashSet<Long>()

    /** Toggles applied since the last snapshot; cleared by [snapshotData]. */
    private val rsvpsAwaitingSnapshot = HashSet<Long>()

    // region reads

    fun getAllGuildScheduledEvents(): Map<Long, List<GuildScheduledEvent>> = guildScheduledEventsSnapshot

    /**
     * @param filterInaccessible drops completed events and events in channels the user cannot see.
     *   An event with no channel (an external event) is always kept.
     */
    @JvmOverloads
    fun getGuildScheduledEvents(
        guildId: Long,
        filterInaccessible: Boolean = true,
    ): List<GuildScheduledEvent> {
        val permissionsByChannel = permissionsStore.getPermissionsByChannel()
        val events = guildScheduledEventsSnapshot[guildId] ?: return emptyList()
        if (!filterInaccessible) return events
        return events.filter { event ->
            when {
                event.status == GuildScheduledEventStatus.COMPLETED -> false
                event.channelId != null ->
                    PermissionUtils.can(Permission.VIEW_CHANNEL, permissionsByChannel[event.channelId])
                else -> true
            }
        }
    }

    /**
     * Looks [eventId] up in [guildId] first, then across every guild. The fallback is what lets an
     * event resolved from an invite — in a guild the user has not joined — still be found.
     */
    @JvmOverloads
    fun findEventFromStore(eventId: Long, guildId: Long? = null): GuildScheduledEvent? {
        if (guildId != null) {
            guildScheduledEventsSnapshot[guildId]?.firstOrNull { it.id == eventId }?.let { return it }
        }
        return guildScheduledEventsSnapshot.values.flatten().firstOrNull { it.id == eventId }
    }

    /** The live event currently running in [channelId], if any. */
    fun getActiveEventForChannel(guildId: Long?, channelId: Long?): GuildScheduledEvent? {
        if (guildId == null || channelId == null) return null
        return getGuildScheduledEvents(guildId).firstOrNull { event ->
            event.channelId == channelId && event.status == GuildScheduledEventStatus.ACTIVE
        }
    }

    fun getMeGuildScheduledEventIds(guildId: Long): Set<Long> =
        meGuildScheduledEventIdsSnapshot[guildId].orEmpty()

    fun isMeRsvpedToEvent(guildId: Long, guildScheduledEventId: Long): Boolean =
        guildScheduledEventId in getMeGuildScheduledEventIds(guildId)

    fun getGuildScheduledEventUsers(eventId: Long): Map<Long, GuildScheduledEventUser> =
        guildScheduledEventUsersSnapshot[eventId].orEmpty()

    fun getIsFetchingGuildScheduledEventUsers(): Boolean = isFetchingGuildScheduledEventUsers

    fun getIsGuildScheduledEventUsersError(): Boolean = isGuildScheduledEventUsersError

    @JvmOverloads
    fun observeGuildScheduledEvents(
        guildId: Long,
        filterInaccessible: Boolean = true,
    ): Observable<List<GuildScheduledEvent>> =
        observationDeck.connectRx(this) { getGuildScheduledEvents(guildId, filterInaccessible) }

    fun observeGuildScheduledEvent(eventId: Long?, guildId: Long?): Observable<GuildScheduledEvent?> =
        observationDeck.connectRx(this) {
            if (eventId != null) findEventFromStore(eventId, guildId) else null
        }

    fun observeGuildScheduledEventUsers(eventId: Long): Observable<Map<Long, GuildScheduledEventUser>> =
        observationDeck.connectRx(this) { getGuildScheduledEventUsers(eventId) }

    fun observeIsMeRsvpedToEvent(guildId: Long, eventId: Long): Observable<Boolean> =
        observationDeck.connectRx(this) { isMeRsvpedToEvent(guildId, eventId) }.distinctUntilChanged()

    // endregion

    // region fetches

    /**
     * Re-reads the guild's events with real attendee counts, at most once every
     * [FETCH_GUILD_EVENTS_THRESHOLD]. Skipped entirely when the guild has no events, because the
     * only reason to want counts is to render events that already exist.
     */
    fun fetchGuildScheduledEventUserCounts(guildId: Long) {
        if (guildScheduledEventsSnapshot[guildId].isNullOrEmpty()) return
        val lastFetch = guildScheduledEventsFetchTimestamps[guildId]
        if (lastFetch != null && clock.currentTimeMillis() - lastFetch < FETCH_GUILD_EVENTS_THRESHOLD) return
        val api = restApi() ?: return
        api.getGuildScheduledEvents(guildId, true)
            .restSubscribeOn(false)
            .appSubscribe(StoreGuildScheduledEvents::class.java) { events ->
                dispatcher.schedule {
                    guildScheduledEvents[guildId] = events.toMutableList()
                    guildScheduledEventsFetchTimestamps[guildId] = clock.currentTimeMillis()
                    markChanged()
                }
            }
    }

    /** The current user's RSVPs in [guildId]. Once per session, and only if events are known. */
    fun fetchMeGuildScheduledEvents(guildId: Long) {
        if (guildScheduledEventsSnapshot[guildId].isNullOrEmpty()) return
        if (guildId in meGuildScheduledEventsFetches) return
        val api = restApi() ?: return
        api.getMeGuildScheduledEvents(guildId)
            .restSubscribeOn(false)
            .appSubscribe(StoreGuildScheduledEvents::class.java) { eventUsers ->
                dispatcher.schedule {
                    meGuildScheduledEventIds[guildId] =
                        eventUsers.mapTo(HashSet()) { it.guildScheduledEventId }
                    meGuildScheduledEventsFetches.add(guildId)
                    markChanged()
                }
            }
    }

    /**
     * The attendee list of one event. Fetched once per `(guild, event)`; the users and members it
     * returns are pushed into [StoreUser]/[StoreGuilds] so the rest of the app can resolve them.
     */
    fun fetchGuildScheduledEventUsers(guildId: Long, eventId: Long) = dispatcher.schedule {
        if (eventId in guildScheduledEventUsersFetches[guildId].orEmpty()) return@schedule
        isFetchingGuildScheduledEventUsers = true
        isGuildScheduledEventUsersError = false
        markChanged()
        val api = restApi() ?: return@schedule handleFetchRsvpUsersFailure()
        api.getGuildScheduledEventUsers(guildId, eventId, RSVP_USER_PAGE_SIZE, true, true)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGuildScheduledEvents::class.java,
                errorHandler = { dispatcher.schedule { handleFetchRsvpUsersFailure() } },
            ) { apiUsers ->
                dispatcher.schedule { handleGuildScheduledEventUsersFetch(guildId, eventId, apiUsers) }
            }
    }

    @StoreThread
    internal fun handleGuildScheduledEventUsersFetch(
        guildId: Long,
        eventId: Long,
        apiUsers: List<ApiGuildScheduledEventUser>,
    ) {
        userStore.handleGuildScheduledEventUsersFetch(apiUsers.mapNotNull { it.user })
        guildsStore.handleGuildScheduledEventUsersFetch(guildId, apiUsers.mapNotNull { it.toGuildMember(guildId) })

        val users = guildScheduledEventUsers.getOrPut(eventId, ::HashMap)
        apiUsers.mapNotNull { GuildScheduledEventUser.from(it, guildId) }
            .forEach { eventUser -> users[eventUser.user.id] = eventUser }

        guildScheduledEventUsersFetches.getOrPut(guildId, ::HashSet).add(eventId)
        isFetchingGuildScheduledEventUsers = false
        isGuildScheduledEventUsersError = false
        markChanged()
    }

    @StoreThread
    internal fun handleFetchRsvpUsersFailure() {
        isFetchingGuildScheduledEventUsers = false
        isGuildScheduledEventUsersError = true
        markChanged()
    }

    /**
     * Marks the guild's events read up to the newest one. `ackType = 1` is the scheduled-event
     * feature; the ack is skipped when the newest event is not newer than the last acked id.
     */
    fun ackGuildEvents(guildId: Long) {
        val mostRecent = guildScheduledEventsSnapshot[guildId]?.maxByOrNull { it.id } ?: return
        val lastAcked = lastAckedGuildScheduledEventIds[guildId]
        if (lastAcked != null && lastAcked >= mostRecent.id) return
        val api = restApi() ?: return
        api.postGuildFeatureAck(
            guildId,
            GUILD_FEATURE_ACK_SCHEDULED_EVENTS,
            mostRecent.id,
            RestAPIParams.GuildFeatureAck(false, 0),
        )
            .restSubscribeOn(false)
            .appSubscribe(errorTag = "REST: guildFeatureAck") {
                dispatcher.schedule { lastAckedGuildScheduledEventIds[guildId] = mostRecent.id }
            }
    }

    // endregion

    // region gateway

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        guildScheduledEventsFetchTimestamps.clear()
        meGuildScheduledEventsFetches.clear()
        guildScheduledEventUsersFetches.clear()
        guildScheduledEvents.clear()
        meGuildScheduledEventIds.clear()
        payload.guilds?.forEach { guild ->
            guild.guildScheduledEvents?.forEach(::processGuildScheduledEventCreateOrUpdate)
        }
        markChanged()
    }

    @StoreThread
    fun handleGuildCreate(guild: Guild) {
        guild.guildScheduledEvents?.forEach(::processGuildScheduledEventCreateOrUpdate)
    }

    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        guildScheduledEvents.remove(guildId)
        guildScheduledEventsFetchTimestamps.remove(guildId)
        meGuildScheduledEventIds.remove(guildId)
        meGuildScheduledEventsFetches.remove(guildId)
        guildScheduledEventUsersFetches.remove(guildId)
        markChanged()
    }

    @StoreThread
    fun handleGuildScheduledEventCreate(guildScheduledEvent: GuildScheduledEvent) =
        processGuildScheduledEventCreateOrUpdate(guildScheduledEvent)

    @StoreThread
    fun handleGuildScheduledEventUpdate(guildScheduledEvent: GuildScheduledEvent) =
        processGuildScheduledEventCreateOrUpdate(guildScheduledEvent)

    @StoreThread
    fun handleGuildScheduledEventDelete(guildScheduledEvent: GuildScheduledEvent) {
        val events = guildScheduledEvents[guildScheduledEvent.guildId] ?: return
        val index = events.indexOfFirst { it.id == guildScheduledEvent.id }
        if (index != -1) events.removeAt(index)
        removeMeRsvpForEvent(guildScheduledEvent.guildId, guildScheduledEvent.id)
        markChanged()
    }

    @StoreThread
    fun handleGuildScheduledEventUserAdd(update: GuildScheduledEventUserUpdate) {
        val event = findEventFromStore(update.guildScheduledEventId) ?: return
        processRsvpCreate(event, update.userId)
    }

    @StoreThread
    fun handleGuildScheduledEventUserRemove(update: GuildScheduledEventUserUpdate) {
        val event = findEventFromStore(update.guildScheduledEventId) ?: return
        processRsvpDelete(event, update.userId)
    }

    /**
     * Adds an event the user reached through an invite link, so a guild they have not joined can
     * still render its event card. The `userRsvp` field on the invite payload is the only place an
     * RSVP arrives outside `GET /users/@me/scheduled-events`.
     */
    fun addGuildScheduledEventFromInvite(guildScheduledEvent: GuildScheduledEvent) {
        val guildId = guildScheduledEvent.guildId
        val events = guildScheduledEvents[guildId]
        val index = events?.indexOfFirst { it.id == guildScheduledEvent.id } ?: -1
        when {
            events == null -> guildScheduledEvents[guildId] = mutableListOf(guildScheduledEvent)
            index == -1 -> events.add(guildScheduledEvent)
            else -> events[index] = guildScheduledEvent
        }
        if (guildScheduledEvent.userRsvp != null) addMeRsvpForEvent(guildId, guildScheduledEvent.id)
        markChanged()
    }

    /** Bulk RSVP seeding, as `(guildId, eventId)` pairs. */
    @StoreThread
    fun addMeRsvpsForEvent(events: List<Pair<Long, Long>>) {
        events.forEach { (guildId, eventId) -> addMeRsvpForEvent(guildId, eventId) }
        markChanged()
    }

    // endregion

    /**
     * Optimistically flips the current user's RSVP and reverses it if the request fails.
     *
     * Two guards, and they answer different questions. [rsvpsAwaitingNetwork] blocks a second
     * toggle while a request is outstanding. [rsvpsAwaitingSnapshot] blocks a toggle issued against
     * a *stale snapshot* — a double tap within one dispatch cycle, where the second tap has not yet
     * seen the first one's result — and is cleared by [snapshotData].
     */
    fun toggleMeRsvpForEvent(guildScheduledEvent: GuildScheduledEvent) {
        val storeEvent = findEventFromStore(guildScheduledEvent.id, guildScheduledEvent.guildId)
            ?: guildScheduledEvent
        val eventId = storeEvent.id
        if (eventId in rsvpsAwaitingNetwork || eventId in rsvpsAwaitingSnapshot) return
        rsvpsAwaitingNetwork.add(eventId)

        dispatcher.schedule {
            val api = restApi()
            if (api == null) {
                rsvpsAwaitingNetwork.remove(eventId)
                return@schedule
            }
            val meId = userStore.getMeInternal().id
            if (isMeRsvpedToEvent(storeEvent.guildId, eventId)) {
                processRsvpDelete(storeEvent, meId)
                api.deleteGuildScheduledEventRsvp(storeEvent.guildId, eventId)
                    .restSubscribeOn(false)
                    .appSubscribe(
                        StoreGuildScheduledEvents::class.java,
                        errorHandler = {
                            dispatcher.schedule {
                                processRsvpCreate(findEventFromStore(eventId, storeEvent.guildId) ?: storeEvent, meId)
                                rsvpsAwaitingNetwork.remove(eventId)
                            }
                        },
                    ) { dispatcher.schedule { rsvpsAwaitingNetwork.remove(eventId) } }
            } else {
                processRsvpCreate(storeEvent, meId)
                api.createGuildScheduledEventRsvp(storeEvent.guildId, eventId)
                    .restSubscribeOn(false)
                    .appSubscribe(
                        StoreGuildScheduledEvents::class.java,
                        errorHandler = {
                            dispatcher.schedule {
                                processRsvpDelete(findEventFromStore(eventId, storeEvent.guildId) ?: storeEvent, meId)
                                rsvpsAwaitingNetwork.remove(eventId)
                            }
                        },
                    ) { dispatcher.schedule { rsvpsAwaitingNetwork.remove(eventId) } }
            }
        }
    }

    // region internals

    /**
     * Insert-or-replace, preserving the previous `userCount` when the incoming payload omits it —
     * which `GUILD_SCHEDULED_EVENT_UPDATE` always does.
     */
    @StoreThread
    private fun processGuildScheduledEventCreateOrUpdate(guildScheduledEvent: GuildScheduledEvent) {
        val guildId = guildScheduledEvent.guildId
        val events = guildScheduledEvents[guildId]
        val index = events?.indexOfFirst { it.id == guildScheduledEvent.id } ?: -1
        when {
            events == null -> guildScheduledEvents[guildId] = mutableListOf(guildScheduledEvent)
            index == -1 -> events.add(guildScheduledEvent)
            else -> events[index] = if (guildScheduledEvent.userCount == null) {
                guildScheduledEvent.copy(userCount = events[index].userCount)
            } else {
                guildScheduledEvent
            }
        }
        markChanged()
    }

    @StoreThread
    private fun processRsvpCreate(guildScheduledEvent: GuildScheduledEvent, userId: Long) {
        val eventId = guildScheduledEvent.id
        if (userId == userStore.getMeInternal().id &&
            isMeRsvpedToEvent(guildScheduledEvent.guildId, eventId)
        ) {
            return
        }
        rsvpsAwaitingSnapshot.add(eventId)
        addUserRsvpForEvent(userId, guildScheduledEvent.guildId, eventId)

        val updated = guildScheduledEvent.copy(userCount = (guildScheduledEvent.userCount ?: 0) + 1)
        val events = guildScheduledEvents[guildScheduledEvent.guildId]
        if (events == null) {
            guildScheduledEvents[guildScheduledEvent.guildId] = mutableListOf(updated)
        } else {
            val index = events.indexOfFirst { it.id == eventId }
            if (index != -1) events[index] = updated else events.add(updated)
        }
        markChanged()
    }

    @StoreThread
    private fun processRsvpDelete(guildScheduledEvent: GuildScheduledEvent, userId: Long) {
        val eventId = guildScheduledEvent.id
        if (userId == userStore.getMeInternal().id &&
            !isMeRsvpedToEvent(guildScheduledEvent.guildId, eventId)
        ) {
            return
        }
        rsvpsAwaitingSnapshot.add(eventId)
        removeUserRsvpForEvent(userId, guildScheduledEvent.guildId, eventId)

        val events = guildScheduledEvents[guildScheduledEvent.guildId]
        val index = events?.indexOfFirst { it.id == eventId } ?: -1
        if (events != null && index != -1) {
            val count = ((guildScheduledEvent.userCount ?: 0) - 1).coerceAtLeast(0)
            events[index] = guildScheduledEvent.copy(userCount = count)
        }
        markChanged()
    }

    @StoreThread
    private fun addMeRsvpForEvent(guildId: Long, guildScheduledEventId: Long) {
        meGuildScheduledEventIds.getOrPut(guildId, ::HashSet).add(guildScheduledEventId)
    }

    @StoreThread
    private fun removeMeRsvpForEvent(guildId: Long, guildScheduledEventId: Long) {
        meGuildScheduledEventIds[guildId]?.remove(guildScheduledEventId)
    }

    @StoreThread
    private fun addUserRsvpForEvent(userId: Long, guildId: Long, guildScheduledEventId: Long) {
        if (userId == userStore.getMeInternal().id && !isMeRsvpedToEvent(guildId, guildScheduledEventId)) {
            addMeRsvpForEvent(guildId, guildScheduledEventId)
        }
        val users = guildScheduledEventUsers.getOrPut(guildScheduledEventId, ::HashMap)
        val user = userStore.getUsers(listOf(userId))[userId] ?: return
        val member = guildsStore.getMember(guildId, userId) ?: return
        users[userId] = GuildScheduledEventUser(user, member, guildScheduledEventId)
    }

    @StoreThread
    private fun removeUserRsvpForEvent(userId: Long, guildId: Long, guildScheduledEventId: Long) {
        if (userId == userStore.getMeInternal().id && isMeRsvpedToEvent(guildId, guildScheduledEventId)) {
            removeMeRsvpForEvent(guildId, guildScheduledEventId)
        }
        guildScheduledEventUsers[guildScheduledEventId]?.remove(userId)
    }

    @StoreThread
    internal fun reset() {
        guildScheduledEvents.clear()
        meGuildScheduledEventIds.clear()
        guildScheduledEventUsers.clear()
        lastAckedGuildScheduledEventIds.clear()
        guildScheduledEventsFetchTimestamps.clear()
        meGuildScheduledEventsFetches.clear()
        guildScheduledEventUsersFetches.clear()
        rsvpsAwaitingNetwork.clear()
        isFetchingGuildScheduledEventUsers = false
        isGuildScheduledEventUsersError = false
        markChanged()
    }

    // endregion

    @StoreThread
    override fun snapshotData() {
        guildScheduledEventsSnapshot = guildScheduledEvents.mapValues { ArrayList(it.value) }
        meGuildScheduledEventIdsSnapshot = meGuildScheduledEventIds.mapValues { HashSet(it.value) }
        guildScheduledEventUsersSnapshot = guildScheduledEventUsers.mapValues { HashMap(it.value) }
        rsvpsAwaitingSnapshot.clear()
    }

    companion object {
        /** 30 minutes. Attendee counts are cheap to be slightly stale. */
        const val FETCH_GUILD_EVENTS_THRESHOLD = 1_800_000L

        private const val RSVP_USER_PAGE_SIZE = 100

        /** `ackType` for the scheduled-event feature in `POST /guilds/{id}/ack/{type}/{id}`. */
        private const val GUILD_FEATURE_ACK_SCHEDULED_EVENTS = 1
    }
}
