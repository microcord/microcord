package com.discord.widgets.servers.creator_monetization_eligibility

import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** The countries Discord can pay creators in. */
class CreatorMonetizationCountryAllowlist(
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val api: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) {
    @Volatile
    private var ipCountry: String? = null
    private val ipCountryRequested = AtomicBoolean(false)

    fun isUserInEligibleCountry(): Boolean {
        val storeCountry = storeUser.getMe().storeCountry?.country
        if (storeCountry != null) return storeCountry.uppercase(Locale.ENGLISH) in ELIGIBLE_COUNTRIES
        fetchIpCountryOnce()
        return ipCountry?.uppercase(Locale.ENGLISH) in ELIGIBLE_COUNTRIES
    }

    private fun fetchIpCountryOnce() {
        if (ipCountry != null || !ipCountryRequested.compareAndSet(false, true)) return
        val api = api() ?: run {
            ipCountryRequested.set(false)
            return
        }
        api.getBillingCountryCode()
            .restSubscribeOn()
            .appSubscribe(
                CreatorMonetizationCountryAllowlist::class.java,
                errorHandler = { ipCountryRequested.set(false) },
            ) { ipCountry = it.countryCode }
    }

    companion object {
        private val ELIGIBLE_COUNTRIES = setOf("US")

        val INSTANCE by lazy(::CreatorMonetizationCountryAllowlist)
    }
}
