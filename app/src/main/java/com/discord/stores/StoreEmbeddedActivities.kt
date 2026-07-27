package com.discord.stores

import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.guild.Guild
import com.discord.models.domain.ModelPayload
import com.discord.models.embeddedactivities.EmbeddedActivity
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

class StoreEmbeddedActivities(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    private val embeddedActivitiesByChannel =
        LinkedHashMap<Long, MutableMap<Long, EmbeddedActivity>>()
    private var embeddedActivitiesByChannelSnapshot: Map<Long, Map<Long, EmbeddedActivity>> =
        emptyMap()

    fun getEmbeddedActivities(): Map<Long, Map<Long, EmbeddedActivity>> =
        embeddedActivitiesByChannelSnapshot

    fun observeEmbeddedActivities(): Observable<Map<Long, Map<Long, EmbeddedActivity>>> =
        observationDeck.connectRx(this) { getEmbeddedActivities() }

    fun observeEmbeddedActivitiesForChannel(channelId: Long): Observable<Map<Long, EmbeddedActivity>> =
        observeEmbeddedActivities().map { byChannel -> byChannel[channelId] ?: emptyMap() }

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
    internal fun handlePreLogout() {
        embeddedActivitiesByChannel.clear()
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
    }

    companion object {
        private const val EMBEDDED_ACTIVITY_HOST = "discordsays.com"
    }
}
