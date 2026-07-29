package com.discord.widgets.chat.list

import com.discord.api.guild.GuildFeature
import com.discord.models.experiments.domain.Experiment
import com.discord.models.guild.Guild
import com.discord.stores.StoreExperiments
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import rx.Observable

class NewThreadsPermissionsFeatureFlag(
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
) {
    fun isEnabled(guildId: Long): Boolean = isEnabled(
        storeExperiments.getGuildExperiment(EXPERIMENT_NAME, guildId, true),
        storeGuilds.getGuild(guildId),
    )

    fun observeEnabled(guildId: Long): Observable<Boolean> = Observable.combineLatest(
        storeExperiments.observeGuildExperiment(EXPERIMENT_NAME, guildId, true),
        storeGuilds.observeGuild(guildId),
    ) { experiment, guild -> isEnabled(experiment, guild) }

    private fun isEnabled(experiment: Experiment?, guild: Guild?): Boolean =
        experiment?.bucket == 1 || guild?.hasFeature(GuildFeature.NEW_THREAD_PERMISSIONS) == true

    companion object {
        private const val EXPERIMENT_NAME = "2021-08_threads_permissions"
        val INSTANCE: NewThreadsPermissionsFeatureFlag by lazy(::NewThreadsPermissionsFeatureFlag)

        fun isEnabled(guildId: Long): Boolean = INSTANCE.isEnabled(guildId)
        fun observeEnabled(guildId: Long): Observable<Boolean> = INSTANCE.observeEnabled(guildId)
    }
}
