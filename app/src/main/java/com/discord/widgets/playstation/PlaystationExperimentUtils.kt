package com.discord.widgets.playstation

import com.discord.stores.StoreExperiments

private const val PLAYSTATION_EXPERIMENT = "2021-12_connected_accounts_playstation"

/** Bucket 1 gets the upsell *and* the integration; bucket 2 gets the integration only. */
fun canSeePlaystationAccountIntegration(experimentStore: StoreExperiments): Boolean =
    getExperimentBucket(experimentStore).let { it == 1 || it == 2 }

fun canSeePlaystationUpsells(experimentStore: StoreExperiments): Boolean =
    getExperimentBucket(experimentStore) == 1

private fun getExperimentBucket(experimentStore: StoreExperiments): Int? =
    experimentStore.getUserExperiment(PLAYSTATION_EXPERIMENT, trackExposure = true)?.bucket
