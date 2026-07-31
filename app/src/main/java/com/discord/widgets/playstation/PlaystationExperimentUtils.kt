package com.discord.widgets.playstation

import com.discord.stores.StoreExperiments

/** Bucket 1 gets the upsell and integration; bucket 2 gets only the integration. */
fun canSeePlaystationAccountIntegration(experimentStore: StoreExperiments): Boolean =
    getExperimentBucket(experimentStore) in 1..2

fun canSeePlaystationUpsells(experimentStore: StoreExperiments): Boolean =
    getExperimentBucket(experimentStore) == 1

private fun getExperimentBucket(experimentStore: StoreExperiments): Int? =
    experimentStore.getUserExperiment("2021-12_connected_accounts_playstation", trackExposure = true)?.bucket
