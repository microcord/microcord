package com.discord.widgets.servers.creator_monetization_eligibility

import com.discord.stores.StoreExperiments
import com.discord.stores.StoreStream

class CreatorMonetizationCountryAllowlist(
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
) {
    fun isUserInEligibleCountry(): Boolean =
        storeExperiments.getUserExperiment(EXPERIMENT_NAME, trackExposure = false)?.bucket == ELIGIBLE_BUCKET

    companion object {
        private const val EXPERIMENT_NAME = "2022-04_creator_monetization_country_allowlist"
        private const val ELIGIBLE_BUCKET = 1

        val INSTANCE by lazy(::CreatorMonetizationCountryAllowlist)
    }
}
