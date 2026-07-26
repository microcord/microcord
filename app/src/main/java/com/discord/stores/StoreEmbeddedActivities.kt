package com.discord.stores

import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.guild.Guild
import com.discord.models.domain.ModelPayload
import com.discord.models.embeddedactivities.EmbeddedActivity
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * Which embedded activities ("Activities", formerly Project Lightspeed) are running in which voice
 * channel, and who is in them.
 *
 * Keyed channel → application → activity, because a channel can host at most one instance of a
 * given application but several different applications at once.
 *
 * Two rules are worth stating because they are easy to get wrong:
 *
 * - **An empty participant list is a removal, not an update.** `EMBEDDED_ACTIVITY_UPDATE` announces
 *   the end of an activity by sending it with no users, so the entry is deleted rather than stored
 *   with an empty pile.
 * - **An application with no launch URL is dropped entirely.** [urlForApplication] is the only
 *   thing that can veto an activity, and it does so before any state is written.
 */
class StoreEmbeddedActivities(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    /**
     * Host that serves embedded-activity front-ends, as `https://<applicationId>.<host>`.
     *
     * This is service infrastructure, in the same category as the CDN host, not a credential: the
     * activity front-end for a Discord application is only reachable there. An empty host disables
     * every activity tile, which is what makes this injectable at all — tests use it, and a build
     * pointed at a different backend can repoint it.
     */
    private val embeddedActivityHost: String = EMBEDDED_ACTIVITY_HOST,
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
        payload.guilds.orEmpty().forEach(::handleEmbeddedActivitiesForGuild)
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
        if (embeddedActivityHost.isEmpty()) null else "https://$applicationId.$embeddedActivityHost"

    @StoreThread
    override fun snapshotData() {
        embeddedActivitiesByChannelSnapshot = embeddedActivitiesByChannel
            .mapValues { (_, byApplication) -> HashMap(byApplication) }
    }

    companion object {
        /** Reference `StoreEmbeddedActivities.getUrlForApplication`'s `EMBEDDED_ACTIVITY_HOST`. */
        const val EMBEDDED_ACTIVITY_HOST: String = "discordsays.com"
    }
}
