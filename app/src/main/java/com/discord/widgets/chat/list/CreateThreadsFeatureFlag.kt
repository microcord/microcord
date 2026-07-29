package com.discord.widgets.chat.list

import com.discord.api.guild.GuildFeature
import com.discord.models.experiments.domain.Experiment
import com.discord.models.guild.Guild
import com.discord.stores.StoreExperiments
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import rx.Observable

class CreateThreadsFeatureFlag(
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
) {
    fun isEnabled(guildId: Long): Boolean = computeIsEnabled(
        storeExperiments.getGuildExperiment(CREATE_EXPERIMENT, guildId, true),
        storeExperiments.getGuildExperiment(ROLLOUT_EXPERIMENT, guildId, false),
        storeGuilds.getGuild(guildId),
    )

    fun observeEnabled(guildId: Long): Observable<Boolean> = Observable.combineLatest(
        storeExperiments.observeGuildExperiment(CREATE_EXPERIMENT, guildId, true),
        storeExperiments.observeGuildExperiment(ROLLOUT_EXPERIMENT, guildId, false),
        storeGuilds.observeGuild(guildId),
    ) { createExperiment, rolloutExperiment, guild ->
        computeIsEnabled(createExperiment, rolloutExperiment, guild)
    }

    companion object {
        private const val CREATE_EXPERIMENT = "2020-09_threads"
        private const val ROLLOUT_EXPERIMENT = "2021-06_threads_rollout"
        val INSTANCE: CreateThreadsFeatureFlag by lazy(::CreateThreadsFeatureFlag)

        fun isEnabled(guildId: Long): Boolean = INSTANCE.isEnabled(guildId)
        fun observeEnabled(guildId: Long): Observable<Boolean> = INSTANCE.observeEnabled(guildId)

        fun computeIsEnabled(
            createExperiment: Experiment?,
            rolloutExperiment: Experiment?,
            guild: Guild?,
        ): Boolean = guild != null && when {
            guild.hasFeature(GuildFeature.THREADS_ENABLED) -> true
            guild.hasFeature(GuildFeature.COMMUNITY) && rolloutExperiment?.bucket != 2 -> false
            else -> createExperiment?.bucket == 1
        }
    }
}
