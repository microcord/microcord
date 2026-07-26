package com.discord.widgets.guildcommunicationdisabled.start

import com.discord.stores.StoreExperiments
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * The two experiment gates that decide whether member time-outs are offered at all:
 * `2021-11_guild_communication_disabled_guilds` (per guild) and
 * `2021-11_guild_communication_disabled_users` (per viewer). Both must be in bucket 1.
 *
 * Both gates track exposure, so simply *asking* whether the control may be shown is itself the
 * analytics event — which is why the answer is not cached and is recomputed on every store update.
 */
class GuildCommunicationDisabledGuildsFeatureFlag(
    private val guildsStore: StoreGuilds = StoreStream.getGuilds(),
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) {

    /** True when [guildId] is a guild we know about and its guild-level experiment is enabled. */
    fun canGuildAccessCommunicationDisabled(guildId: Long): Boolean {
        guildsStore.getGuild(guildId) ?: return false
        return storeExperiments
            .getGuildExperiment(GUILD_EXPERIMENT, guildId, trackExposure = true)?.bucket == 1
    }

    /** True when the *viewer* is enrolled in the user-level experiment. */
    fun canUserAccessDisableCommunication(): Boolean =
        storeExperiments.getUserExperiment(USER_EXPERIMENT, trackExposure = true)?.bucket == 1

    fun observeCanGuildAccessCommunicationDisabled(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(guildsStore, storeExperiments) {
            canGuildAccessCommunicationDisabled(guildId) && canUserAccessDisableCommunication()
        }.distinctUntilChanged()

    companion object {
        private const val GUILD_EXPERIMENT = "2021-11_guild_communication_disabled_guilds"
        private const val USER_EXPERIMENT = "2021-11_guild_communication_disabled_users"

        /**
         * The reference keeps one process-wide instance behind a `lazy` delegate on its companion.
         * Reproduced because callers reach for it from menu builders that have no injection point.
         */
        val INSTANCE: GuildCommunicationDisabledGuildsFeatureFlag by lazy {
            GuildCommunicationDisabledGuildsFeatureFlag()
        }
    }
}
