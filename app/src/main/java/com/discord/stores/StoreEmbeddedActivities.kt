package com.discord.stores

import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.embeddedactivities.EmbeddedActivityInstance
import com.discord.api.embeddedactivities.EmbeddedActivityShelf
import com.discord.api.activity.ActivityType
import com.discord.api.guild.Guild
import com.discord.models.domain.ModelPayload
import com.discord.models.embeddedactivities.EmbeddedActivity
import com.discord.restapi.EmbeddedActivitiesAPI
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

class StoreEmbeddedActivities(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: Dispatcher? = null,
) : StoreV2() {

    data class ShelfState(
        val shelf: EmbeddedActivityShelf? = null,
        val isFetching: Boolean = false,
        val lastFetchTimestampMs: Long? = null,
        val error: String? = null,
    )

    private val embeddedActivitiesByChannel =
        LinkedHashMap<Long, MutableMap<Long, EmbeddedActivity>>()
    private var embeddedActivitiesByChannelSnapshot: Map<Long, Map<Long, EmbeddedActivity>> =
        emptyMap()
    private val instancesByChannel = LinkedHashMap<Long, MutableMap<Long, EmbeddedActivityInstance>>()
    private var instancesByChannelSnapshot: Map<Long, Map<Long, EmbeddedActivityInstance>> = emptyMap()
    private val shelves = LinkedHashMap<Long, ShelfState>()
    private var shelvesSnapshot: Map<Long, ShelfState> = emptyMap()

    fun getEmbeddedActivities(): Map<Long, Map<Long, EmbeddedActivity>> =
        embeddedActivitiesByChannelSnapshot

    fun observeEmbeddedActivities(): Observable<Map<Long, Map<Long, EmbeddedActivity>>> =
        observationDeck.connectRx(this) { getEmbeddedActivities() }

    fun observeEmbeddedActivitiesForChannel(channelId: Long): Observable<Map<Long, EmbeddedActivity>> =
        observeEmbeddedActivities().map { byChannel -> byChannel[channelId] ?: emptyMap() }

    fun getInstancesForChannel(channelId: Long): Map<Long, EmbeddedActivityInstance> =
        instancesByChannelSnapshot[channelId].orEmpty()

    fun observeInstancesForChannel(channelId: Long): Observable<Map<Long, EmbeddedActivityInstance>> =
        observationDeck.connectRx(this) { getInstancesForChannel(channelId) }

    fun getShelfState(guildId: Long?): ShelfState = shelvesSnapshot[normalizeGuildId(guildId)] ?: ShelfState()

    fun observeShelf(guildId: Long?): Observable<ShelfState> =
        observationDeck.connectRx(this) { getShelfState(guildId) }

    fun shouldFetchShelf(guildId: Long?, force: Boolean = false): Boolean {
        if (force) return true
        val state = getShelfState(guildId)
        return !state.isFetching && now() - (state.lastFetchTimestampMs ?: 0L) > SHELF_TTL_MS
    }

    fun fetchShelf(
        api: EmbeddedActivitiesAPI,
        guildId: Long?,
        force: Boolean = false,
    ): Observable<EmbeddedActivityShelf> {
        getShelfState(guildId).shelf?.takeIf { !shouldFetchShelf(guildId, force) }
            ?.let { return Observable.just(it) }
        val dispatcher = checkNotNull(dispatcher) { "StoreEmbeddedActivities requires a Dispatcher to fetch" }
        dispatcher.schedule { handleShelfFetchStart(guildId) }
        return api.shelf(guildId)
            .doOnNext { shelf -> dispatcher.schedule { handleShelfFetchSuccess(guildId, shelf) } }
            .doOnError { failure -> dispatcher.schedule { handleShelfFetchFailure(guildId, failure) } }
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        embeddedActivitiesByChannel.clear()
        payload.guilds!!.forEach(::handleEmbeddedActivitiesForGuild)
        markChanged()
    }

    @StoreThread
    fun handleGuildCreate(guild: Guild) = handleEmbeddedActivitiesForGuild(guild)

    @StoreThread
    fun handleEmbeddedActivityInboundUpdate(update: EmbeddedActivityInboundUpdate) =
        handleEmbeddedActivityForChannel(
            channelId = update.channelId,
            userIds = update.users,
            apiEmbeddedActivity = update.embeddedActivity,
            guildId = update.guildId,
        )

    @StoreThread
    fun handleEmbeddedActivityUpdateV2(instance: EmbeddedActivityInstance) {
        val channelInstances = instancesByChannel.getOrPut(instance.location.channelId) { LinkedHashMap() }
        val channelActivities = embeddedActivitiesByChannel.getOrPut(instance.location.channelId) { LinkedHashMap() }
        if (instance.participants.isEmpty()) {
            channelInstances.remove(instance.applicationId)
            channelActivities.remove(instance.applicationId)
        } else {
            channelInstances[instance.applicationId] = instance
            val name = shelves.values.asSequence()
                .mapNotNull { it.shelf }
                .flatMap { it.applications.asSequence() }
                .firstOrNull { it.id == instance.applicationId }
                ?.name
            channelActivities[instance.applicationId] = EmbeddedActivity(
                applicationId = instance.applicationId,
                assets = null,
                createdAt = null,
                details = null,
                name = name,
                secrets = null,
                state = null,
                timestamps = null,
                type = ActivityType.PLAYING,
                userIds = instance.participants.map { it.userId },
                url = "https://${instance.applicationId}.$EMBEDDED_ACTIVITY_HOST",
                guildId = instance.location.guildId ?: 0L,
            )
        }
        if (channelInstances.isEmpty()) instancesByChannel.remove(instance.location.channelId)
        if (channelActivities.isEmpty()) embeddedActivitiesByChannel.remove(instance.location.channelId)
        markChanged()
    }

    @StoreThread
    fun handleShelfFetchStart(guildId: Long?) {
        val key = normalizeGuildId(guildId)
        shelves[key] = (shelves[key] ?: ShelfState()).copy(isFetching = true, error = null)
        markChanged()
    }

    @StoreThread
    fun handleShelfFetchSuccess(guildId: Long?, shelf: EmbeddedActivityShelf) {
        shelves[normalizeGuildId(guildId)] = ShelfState(shelf, lastFetchTimestampMs = now())
        markChanged()
    }

    @StoreThread
    fun handleShelfFetchFailure(guildId: Long?, failure: Throwable) {
        val key = normalizeGuildId(guildId)
        shelves[key] = (shelves[key] ?: ShelfState()).copy(
            isFetching = false,
            error = failure.message ?: failure.javaClass.simpleName,
        )
        markChanged()
    }

    @StoreThread
    internal fun handlePreLogout() {
        embeddedActivitiesByChannel.clear()
        instancesByChannel.clear()
        shelves.clear()
        markChanged()
    }

    @StoreThread
    private fun handleEmbeddedActivitiesForGuild(guild: Guild) {
        guild.embeddedActivities.orEmpty().forEach { inGuild ->
            handleEmbeddedActivityForChannel(
                channelId = inGuild.channelId,
                userIds = inGuild.users,
                apiEmbeddedActivity = inGuild.embeddedActivity,
                guildId = guild.id,
            )
        }
    }

    @StoreThread
    private fun handleEmbeddedActivityForChannel(
        channelId: Long,
        userIds: List<Long>,
        apiEmbeddedActivity: com.discord.api.embeddedactivities.EmbeddedActivity,
        guildId: Long,
    ) {
        val url = urlForApplication(apiEmbeddedActivity.applicationId) ?: return
        val activity = EmbeddedActivity.fromApiEmbeddedActivity(
            apiEmbeddedActivity,
            userIds,
            url,
            guildId,
        )
        val forChannel = embeddedActivitiesByChannel[channelId] ?: LinkedHashMap()
        if (userIds.isNotEmpty()) {
            forChannel[activity.applicationId] = activity
        } else {
            forChannel.remove(activity.applicationId)
        }
        embeddedActivitiesByChannel[channelId] = forChannel
        markChanged()
    }

    private fun urlForApplication(applicationId: Long): String? =
        if (EMBEDDED_ACTIVITY_HOST.isEmpty()) null else "https://$applicationId.$EMBEDDED_ACTIVITY_HOST"

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        embeddedActivitiesByChannelSnapshot = embeddedActivitiesByChannel.mapValuesTo(HashMap()) {
            HashMap(it.value)
        }
        instancesByChannelSnapshot = instancesByChannel.mapValuesTo(HashMap()) { HashMap(it.value) }
        shelvesSnapshot = HashMap(shelves)
    }

    private fun normalizeGuildId(guildId: Long?) = guildId?.takeIf { it > 0L } ?: 0L

    companion object {
        private const val EMBEDDED_ACTIVITY_HOST = "discordsays.com"
        private const val SHELF_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
