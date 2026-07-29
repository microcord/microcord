package com.discord.widgets.chat.list

import com.discord.api.guild.GuildFeature
import com.discord.models.experiments.domain.Experiment
import com.discord.models.guild.Guild
import com.discord.stores.StoreExperiments
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import rx.Observable

class TextInVoiceFeatureFlag(
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
) {
    fun isEnabled(guildId: Long?): Boolean = guildId != null && isEnabled(
        storeExperiments.getGuildExperiment(EXPERIMENT_NAME, guildId, true),
        storeGuilds.getGuild(guildId),
    )

    fun observeEnabled(guildId: Long?): Observable<Boolean> = if (guildId == null) {
        Observable.just(false)
    } else {
        Observable.combineLatest(
            storeExperiments.observeGuildExperiment(EXPERIMENT_NAME, guildId, true),
            storeGuilds.observeGuild(guildId),
        ) { experiment, guild -> isEnabled(experiment, guild) }
    }

    private fun isEnabled(experiment: Experiment?, guild: Guild?): Boolean =
        experiment?.bucket == 1 || guild?.hasFeature(GuildFeature.TEXT_IN_VOICE_ENABLED) == true

    companion object {
        private const val EXPERIMENT_NAME = "2022-03_text_in_voice"
        val INSTANCE: TextInVoiceFeatureFlag by lazy(::TextInVoiceFeatureFlag)

        fun isEnabled(guildId: Long?): Boolean = INSTANCE.isEnabled(guildId)
        fun observeEnabled(guildId: Long?): Observable<Boolean> = INSTANCE.observeEnabled(guildId)
    }
}
