package com.discord.models.experiments.domain

/**
 * A resolved experiment assignment for the current user.
 *
 * [trackExposure] is invoked by the caller at the moment the experiment actually changes what the
 * user sees, so exposure analytics are not fired for experiments that were merely read.
 */
data class Experiment(
    val revision: Int,
    val bucket: Int,
    val population: Int,
    val isOverride: Boolean,
    val trackExposure: () -> Unit,
)

/** Where an experiment is being evaluated; guild/channel scoped experiments need the ids. */
data class ExperimentContext(
    val shouldTrigger: Boolean = false,
    val guildId: Long? = null,
    val channelId: Long? = null,
)
