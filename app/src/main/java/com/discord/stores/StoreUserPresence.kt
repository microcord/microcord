package com.discord.stores

import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityEmoji
import com.discord.api.activity.ActivityType
import com.discord.api.guild.Guild
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.ClientStatuses
import com.discord.api.presence.Presence as ApiPresence
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelMuteConfig
import com.discord.models.domain.ModelSession
import com.discord.models.domain.ModelUserSettings
import com.discord.models.presence.Presence
import com.discord.models.user.MeUser
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.time.Clock
import rx.Observable

class StoreUserPresence(
    private val clock: Clock,
    val observationDeck: ObservationDeck,
    val stream: StoreStream? = null,
    private val publishLocalPresence: (Presence) -> Unit = {},
    private val resolveCustomEmoji: (Long) -> ActivityEmoji? = { ActivityEmoji(it.toString(), null, false) },
) : StoreV2() {
    data class TimestampedPresence(val presence: Presence, val timestamp: Long)

    private val presences = LinkedHashMap<Long, Presence>()
    private val userGuildPresences = HashMap<Long, MutableMap<Long, TimestampedPresence>>()
    private var presencesSnapshot: Map<Long, Presence> = emptyMap()
    private var meUser: MeUser? = null
    private var localPresence = Presence(
        ClientStatus.ONLINE,
        ClientStatuses(ClientStatus.OFFLINE, ClientStatus.OFFLINE, ClientStatus.ONLINE),
        null,
    )
    private var localPresenceSnapshot = localPresence.copy()

    fun getPresences(): Map<Long, Presence> = presencesSnapshot
    @StoreThread fun getPresencesInternal(): Map<Long, Presence> = presences
    fun getLocalPresence(): Presence = localPresenceSnapshot
    @StoreThread internal fun getLocalPresenceInternal(): Presence = localPresence

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        StoreThreadScheduler.assertCurrentThread()
        userGuildPresences.clear()
        presences.clear()
        meUser = MeUser(requireNotNull(payload.me))
        payload.guilds.orEmpty().forEach(::handleGuildAdd)
        payload.presences.orEmpty().forEach { presence ->
            presence.user?.let { handlePresenceUpdate(ME_GUILD_ID, it.id, presence.status, presence.clientStatus, presence.activities) }
        }
        updateSelfPresence(payload.userSettings, payload.sessions, false)
        markChanged()
    }

    @StoreThread fun handleGuildAdd(guild: Guild) = guild.presences.orEmpty().forEach { presence ->
        val userId = presence.user?.id ?: presence.userId
        if (userId != null) handlePresenceUpdate(guild.id, userId, presence.status, presence.clientStatus, presence.activities)
    }
    @StoreThread fun handleGuildRemove(guild: Guild) = clearPresences(guild.id)
    @StoreThread fun handleGuildMemberRemove(userId: Long, guildId: Long) {
        StoreThreadScheduler.assertCurrentThread()
        if (userGuildPresences[userId]?.remove(guildId) != null) flattenPresence(userId)
    }
    @StoreThread fun handlePresenceReplace(replacements: List<ApiPresence>) {
        StoreThreadScheduler.assertCurrentThread()
        clearPresences(ME_GUILD_ID)
        replacements.forEach { presence ->
            val userId = presence.user?.id ?: presence.userId
            if (userId != null) handlePresenceUpdate(
                ME_GUILD_ID,
                userId,
                presence.status,
                presence.clientStatus ?: offlineClientStatuses(),
                presence.activities,
            )
        }
    }
    @StoreThread fun handlePresenceUpdate(guildId: Long, presence: ApiPresence) {
        val userId = presence.user?.id ?: presence.userId ?: return
        handlePresenceUpdate(guildId, userId, presence.status, presence.clientStatus, presence.activities)
    }
    @StoreThread fun handlePresenceUpdate(
        guildId: Long,
        userId: Long,
        status: ClientStatus,
        clientStatuses: ClientStatuses?,
        activities: List<Activity>?,
    ) {
        StoreThreadScheduler.assertCurrentThread()
        val normalizedGuildId = guildId.takeUnless { it == 0L } ?: ME_GUILD_ID
        if (meUser?.id == userId && normalizedGuildId != ME_GUILD_ID) return
        userGuildPresences.getOrPut(userId, ::HashMap)[normalizedGuildId] =
            TimestampedPresence(Presence(status, clientStatuses, activities), clock.currentTimeMillis())
        flattenPresence(userId)
    }
    @StoreThread fun handleSessionsReplace(sessions: List<ModelSession>) = updateSelfPresence(null, sessions, true)
    @StoreThread fun handleUserSettingsUpdate(settings: ModelUserSettings) = updateSelfPresence(settings, null, true)
    @StoreThread fun handleThreadMemberListUpdate(guildId: Long, presences: List<ApiPresence>) =
        presences.forEach { handlePresenceUpdate(guildId, it) }
    @StoreThread fun handleThreadMembersUpdate(guildId: Long, presences: List<ApiPresence>) =
        handleThreadMemberListUpdate(guildId, presences)

    @JvmOverloads
    @StoreThread
    fun updateActivity(type: ActivityType, activity: Activity?, force: Boolean = false) {
        StoreThreadScheduler.assertCurrentThread()
        val existing = localPresence.activities.orEmpty().firstOrNull { it.type == activity?.type }
        if (!force && existing == activity) return
        val activities = localPresence.activities.orEmpty().filterNot { it.type == type }.toMutableList()
        activity?.let(activities::add)
        localPresence = Presence(localPresence.status, null, activities.filterNot { it.type == ActivityType.UNKNOWN })
        markChanged(LocalPresenceUpdateSource)
        meUser?.id?.let { userId ->
            if (presences[userId] != localPresence) {
                presences[userId] = localPresence
                markChanged()
            }
        }
    }

    fun getApplicationActivity(userId: Long, applicationId: Long): Activity? =
        presencesSnapshot[userId]?.activities?.firstOrNull { it.applicationId == applicationId }
    fun observeAllPresences(): Observable<Map<Long, Presence>> = observationDeck.connectRx(this) { presencesSnapshot }
    fun observePresenceForUser(userId: Long): Observable<Presence?> =
        observeAllPresences().map { it[userId] }.distinctUntilChanged()
    fun observePresencesForUsers(userIds: Collection<Long>): Observable<Map<Long, Presence>> {
        val ids = userIds.toSet()
        return observeAllPresences().map { it.filterKeys(ids::contains) }.distinctUntilChanged()
    }
    fun observeApplicationActivity(userId: Long, applicationId: Long): Observable<Activity?> =
        observePresenceForUser(userId).map { presence -> presence?.activities?.firstOrNull { it.applicationId == applicationId } }
            .distinctUntilChanged()
    fun observeLocalPresence(): Observable<Presence> =
        observationDeck.connectRx(LocalPresenceUpdateSource) { localPresenceSnapshot }.distinctUntilChanged()

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        presences.clear()
        userGuildPresences.clear()
        meUser = null
        localPresence = Presence(
            ClientStatus.ONLINE,
            ClientStatuses(ClientStatus.OFFLINE, ClientStatus.OFFLINE, ClientStatus.ONLINE),
            null,
        )
        markChanged(LocalPresenceUpdateSource)
    }

    override fun snapshotData() {
        presencesSnapshot = LinkedHashMap(presences)
        localPresenceSnapshot = localPresence.copy(activities = localPresence.activities?.toList())
        if (LocalPresenceUpdateSource in updateSources) publishLocalPresence(localPresence)
    }

    @StoreThread
    private fun clearPresences(guildId: Long) {
        StoreThreadScheduler.assertCurrentThread()
        userGuildPresences.filterValues { it.remove(guildId) != null }.keys.toList().forEach(::flattenPresence)
    }

    @StoreThread
    private fun flattenPresence(userId: Long) {
        val latest = userGuildPresences[userId]?.values?.maxByOrNull(TimestampedPresence::timestamp)?.presence
        if (latest == null || latest.status == ClientStatus.OFFLINE) {
            userGuildPresences.remove(userId)
            if (presences.remove(userId) != null) markChanged()
            return
        }
        val flattened = latest.copy(
            clientStatuses = latest.clientStatuses ?: offlineClientStatuses(),
            activities = latest.activities?.sortedWith(
                compareBy<Activity> { it.type.ordinal }.thenBy { it.applicationId != null }.thenBy { it.createdAt },
            )?.reversed(),
        )
        if (presences.put(userId, flattened) != flattened) markChanged()
    }

    @StoreThread
    private fun updateSelfPresence(settings: ModelUserSettings?, sessions: List<ModelSession>?, markLocal: Boolean) {
        StoreThreadScheduler.assertCurrentThread()
        val userId = meUser?.id ?: return
        val status = settings?.status ?: localPresence.status
        val remoteActivities = sessions?.firstOrNull(ModelSession::active)?.activities
            ?: presences[userId]?.activities
            ?: localPresence.activities
        val localActivities = settings?.customStatus?.let(::customStatusActivity)
            ?.let { custom -> localPresence.activities.orEmpty().filterNot { it.type == ActivityType.CUSTOM_STATUS } + custom }
            ?: if (settings?.customStatus != null) {
                localPresence.activities.orEmpty().filterNot { it.type == ActivityType.CUSTOM_STATUS }
            } else {
                localPresence.activities
            }
        if (localPresence.status != status || localPresence.activities != localActivities) {
            localPresence = Presence(status, null, localActivities)
            markChanged(LocalPresenceUpdateSource)
        }
        handlePresenceUpdate(ME_GUILD_ID, userId, status, null, remoteActivities)
        if (!markLocal && settings?.customStatus == null) markUnchanged(LocalPresenceUpdateSource)
    }

    private fun customStatusActivity(setting: ModelCustomStatusSetting): Activity? {
        if (setting == ModelCustomStatusSetting.CLEAR) return null
        if (setting.expiresAt?.let { ModelMuteConfig(it).endTimeMs ?: 0L }?.let { it <= clock.currentTimeMillis() } == true) return null
        val emoji = setting.emojiId?.let(resolveCustomEmoji)
            ?: setting.emojiName?.let { ActivityEmoji(null, it, false) }
        return Activity(
            name = "Custom Status",
            type = ActivityType.CUSTOM_STATUS,
            url = null,
            createdAt = clock.currentTimeMillis(),
            timestamps = null,
            applicationId = null,
            details = null,
            state = setting.text,
            emoji = emoji,
            party = null,
            assets = null,
            flags = null,
            syncId = null,
            sessionId = null,
            metadata = null,
            platform = null,
            supportedPlatforms = null,
            buttons = null,
        )
    }

    companion object {
        const val ME_GUILD_ID = Long.MAX_VALUE
        @JvmField val LocalPresenceUpdateSource = object : ObservationDeck.UpdateSource {}
        private fun offlineClientStatuses() = ClientStatuses(
            ClientStatus.OFFLINE,
            ClientStatus.OFFLINE,
            ClientStatus.OFFLINE,
        )
    }
}
