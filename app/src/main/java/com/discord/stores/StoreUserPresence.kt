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
import com.discord.models.domain.ModelSession
import com.discord.models.domain.ModelUserSettings
import com.discord.models.presence.Presence
import com.discord.models.user.MeUser
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.presence.createCustomStatusActivity
import com.discord.utilities.time.Clock
import com.discord.utilities.time.TimeUtils
import rx.Observable

class StoreUserPresence(
    private val clock: Clock,
    val observationDeck: ObservationDeck,
    val stream: StoreStream? = null,
    private val publishLocalPresence: (Presence) -> Unit = {},
    /**
     * `StoreEmoji.getCustomEmojiInternal` — resolves a custom-status emoji id to its name and
     * animated flag. Returning null when the emoji is not in the emoji store is deliberate: the
     * reference drops the emoji rather than publishing one with a missing name, because the name
     * is what the CDN URL and the accessibility label are built from.
     */
    private val resolveCustomEmoji: (Long) -> ActivityEmoji? = { null },
    /**
     * `StoreEmoji.unicodeEmojiSurrogateMap` — maps a shortcode such as `smile` to its surrogate
     * pair. Also null on miss: the stored value is a *name*, not the character, so passing it
     * through unresolved would render the literal text "smile".
     */
    private val resolveUnicodeEmoji: (String) -> ActivityEmoji? = { null },
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

    /**
     * Applies the presences READY omitted: friends first, then one list per supplemental guild.
     *
     * Both arrive deduplicated against READY's `users`, so a presence identifies its subject by id
     * alone and no user object is attached.
     */
    @StoreThread
    fun handleConnectionOpenSupplemental(
        friends: List<ApiPresence>,
        byGuild: Map<Long, List<ApiPresence>>,
    ) {
        friends.forEach { presence ->
            val userId = presence.user?.id ?: presence.userId ?: return@forEach
            handlePresenceUpdate(ME_GUILD_ID, userId, presence.status, presence.clientStatus, presence.activities)
        }
        byGuild.forEach { (guildId, presences) ->
            presences.forEach { presence ->
                val userId = presence.user?.id ?: presence.userId ?: return@forEach
                handlePresenceUpdate(guildId, userId, presence.status, presence.clientStatus, presence.activities)
            }
        }
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
        // When replacing, the removal key is the *new* activity's type, not [type]; the two differ
        // for callers that clear one slot while setting another. Only the first match is dropped,
        // matching the reference's index-based removal.
        val activities = removeActivityInList(activity?.type ?: type, localPresence.activities)
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
            activities = latest.activities
                ?.sortedWith(PresenceUtils.ACTIVITY_COMPARATOR)
                ?.reversed(),
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
        val customStatusSetting = settings?.customStatus
        val localActivities = if (customStatusSetting == null) {
            localPresence.activities
        } else {
            val custom = getCustomStatusActivityFromSetting(customStatusSetting)
            if (custom != null) {
                replaceActivityInList(custom, localPresence.activities)
            } else {
                removeActivityInList(ActivityType.CUSTOM_STATUS, localPresence.activities)
            }
        }
        if (localPresence.status != status || localPresence.activities != localActivities) {
            localPresence = Presence(status, null, localActivities)
            markChanged(LocalPresenceUpdateSource)
        }
        handlePresenceUpdate(ME_GUILD_ID, userId, status, null, remoteActivities)
        // The test is on the *resulting* presence, not the incoming settings: a settings payload
        // carrying a status that resolved to null (expired, or an emoji we cannot resolve) leaves
        // nothing worth republishing to the gateway.
        val resolvedCustomStatus = with(PresenceUtils) { localPresence.customStatusActivity }
        if (!markLocal && resolvedCustomStatus == null) markUnchanged(LocalPresenceUpdateSource)
    }

    /**
     * Drops the first activity of [type]. Only the first, because a client may legitimately hold
     * two activities of the same type (two games) and the reference replaces one at a time.
     */
    private fun removeActivityInList(type: ActivityType, oldActivities: List<Activity>?): MutableList<Activity> {
        val activities = oldActivities?.toMutableList() ?: mutableListOf()
        val index = activities.indexOfFirst { it.type == type }
        if (index != -1) activities.removeAt(index)
        return activities
    }

    private fun replaceActivityInList(newActivity: Activity, oldActivities: List<Activity>?): List<Activity> =
        removeActivityInList(newActivity.type, oldActivities).apply { add(newActivity) }

    /**
     * @return the local `CUSTOM_STATUS` activity for [setting], or null when the status is
     *   cleared or already expired.
     *
     * Expiry is evaluated here rather than by a timer so a status that lapsed while the process
     * was dead is never republished on the next connect.
     */
    private fun getCustomStatusActivityFromSetting(setting: ModelCustomStatusSetting): Activity? {
        if (setting == ModelCustomStatusSetting.CLEAR) return null
        val expiresAt = setting.expiresAt
        if (expiresAt != null && TimeUtils.parseUTCDate(expiresAt) - clock.currentTimeMillis() <= 0L) return null
        // Exclusive branches, not a fallback chain: an unresolvable custom emoji id must not fall
        // through to the (unrelated) name field.
        val emojiId = setting.emojiId
        val emoji = if (emojiId != null) {
            resolveCustomEmoji(emojiId)
        } else {
            setting.emojiName?.let(resolveUnicodeEmoji)
        }
        return createCustomStatusActivity(setting.text, emoji, clock.currentTimeMillis())
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
