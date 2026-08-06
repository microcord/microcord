package com.discord.stores

import com.discord.api.guild.preview.GuildPreview
import com.discord.api.guild.profile.GuildProfile
import com.discord.api.guild.GuildFeature
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * Public profiles of guilds the user is not in.
 *
 * `GET /guilds/{id}/profile` supplies the current RN guild-tag profile. The fetch is triggered by
 * observation rather than by an explicit call, so a screen only has to subscribe.
 */
class StoreGuildProfiles(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
) : StoreV2() {

    enum class FetchStates { FETCHING, FAILED, SUCCEEDED }

    data class GuildProfileData(
        val fetchState: FetchStates,
        val data: GuildPreview?,
        val isPrivate: Boolean = false,
    )

    private val guildProfilesState = HashMap<Long, GuildProfileData>()
    private var guildProfilesStateSnapshot: Map<Long, GuildProfileData> = emptyMap()

    private var api: RestAPIInterface? = null
    fun bindApi(api: RestAPIInterface) {
        this.api = api
    }

    fun getGuildProfile(guildId: Long): GuildProfileData? = guildProfilesStateSnapshot[guildId]

    /**
     * Emits the preview state for [guildId], starting a fetch if there is not one already or if
     * the previous attempt failed. A failed profile is retried on every new subscription, which
     * is what makes reopening the sheet act as a retry.
     */
    fun observeGuildProfile(guildId: Long): Observable<GuildProfileData?> {
        dispatcher.schedule { fetchIfNonexisting(guildId) }
        return observationDeck.connectRx(this) { guildProfilesStateSnapshot[guildId] }.distinctUntilChanged()
    }

    @StoreThread
    internal fun fetchIfNonexisting(guildId: Long) {
        val existing = guildProfilesState[guildId]
        if (existing == null || existing.fetchState == FetchStates.FAILED) fetchGuildProfile(guildId)
    }

    @StoreThread
    private fun fetchGuildProfile(guildId: Long) = dispatcher.schedule {
        if (guildProfilesState[guildId]?.fetchState == FetchStates.FETCHING) return@schedule
        val api = api ?: return@schedule
        handleGuildProfileFetchStart(guildId)
        api.getGuildProfile(guildId)
            .map(::toGuildPreview)
            .restSubscribeOn()
            .appSubscribe(
                StoreGuildProfiles::class.java,
                errorHandler = { error ->
                    dispatcher.schedule {
                        if (error.statusCode == 403) handleGuildProfileFetchPrivate(guildId)
                        else handleGuildProfileFetchFailed(guildId)
                    }
                },
            ) { preview -> dispatcher.schedule { handleGuildProfileFetchSuccess(preview) } }
    }

    @StoreThread
    internal fun handleGuildProfileFetchStart(guildId: Long) {
        guildProfilesState[guildId] = GuildProfileData(FetchStates.FETCHING, null)
        markChanged()
    }

    @StoreThread
    internal fun handleGuildProfileFetchFailed(guildId: Long) {
        guildProfilesState[guildId] = GuildProfileData(FetchStates.FAILED, null)
        markChanged()
    }

    @StoreThread
    internal fun handleGuildProfileFetchPrivate(guildId: Long) {
        guildProfilesState[guildId] = GuildProfileData(FetchStates.SUCCEEDED, null, isPrivate = true)
        markChanged()
    }

    @StoreThread
    internal fun handleGuildProfileFetchSuccess(guildPreview: GuildPreview) {
        guildProfilesState[guildPreview.id] = GuildProfileData(FetchStates.SUCCEEDED, guildPreview)
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        guildProfilesStateSnapshot = HashMap(guildProfilesState)
    }

    private fun toGuildPreview(profile: GuildProfile) = GuildPreview(
        id = profile.id,
        name = profile.name,
        description = profile.description,
        splash = null,
        banner = null,
        icon = profile.iconHash,
        approximatePresenceCount = profile.onlineCount,
        approximateMemberCount = profile.memberCount,
        emojis = emptyList(),
        features = profile.features.mapNotNull { runCatching { GuildFeature.valueOf(it) }.getOrNull() },
        featurableInDirectory = "DISCOVERABLE" in profile.features,
    )
}
