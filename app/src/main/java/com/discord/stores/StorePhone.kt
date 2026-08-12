package com.discord.stores

import android.content.Context
import com.discord.models.domain.ModelPayload
import com.discord.models.phone.PhoneCountryCode
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.persister.Persister
import com.google.gson.Gson
import java.io.InputStreamReader
import rx.Observable

/**
 * The phone-number country code offered by every phone input in the app.
 *
 * Two values are tracked separately on purpose: the *default* comes from `READY.country_code`
 * (server-side geo-IP) and changes whenever the account reconnects, while the *selection* is what
 * the user picked and survives restarts. The selection wins once it exists, which is why
 * [MISSING_COUNTRY_CODE][PhoneCountryCode.Companion.MISSING_COUNTRY_CODE] is used as the sentinel
 * rather than `null` — an explicitly chosen country must not be overwritten by a later READY.
 */
class StorePhone(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {
    /**
     * Populated by [init] from `assets/data/countries.json`. Reading it before `init` is a wiring
     * bug, matching the reference's `lateinit`.
     */
    var countryCodes: List<PhoneCountryCode> = emptyList()
        private set

    private var defaultCountryCode: PhoneCountryCode = PhoneCountryCode.DEFAULT_COUNTRY_CODE
    private var selectedCountryCode: PhoneCountryCode = PhoneCountryCode.MISSING_COUNTRY_CODE
    private val selectedCountryCodeCache =
        Persister(CACHE_KEY_SELECTED_COUNTRY_CODE, PhoneCountryCode.MISSING_COUNTRY_CODE, deviceScoped = true)

    fun getCountryCode(): PhoneCountryCode =
        if (selectedCountryCode == PhoneCountryCode.MISSING_COUNTRY_CODE) {
            defaultCountryCode
        } else {
            selectedCountryCode
        }

    fun observeCountryCode(): Observable<PhoneCountryCode> =
        observationDeck.connectRx(this) { getCountryCode() }.distinctUntilChanged()

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        payload.countryCode?.let(::updateDefaultCountryCode)
    }

    override fun init(context: Context) {
        super.init(context)
        countryCodes = loadCountryCodesFromDisk(context)
        selectedCountryCode = selectedCountryCodeCache.get()
        markChanged()
    }

    override fun snapshotData() {
        selectedCountryCodeCache.set(selectedCountryCode)
    }

    fun updateDefaultCountryCode(alpha2: String) = dispatcher.schedule {
        countryCodes.firstOrNull { it.alpha2 == alpha2 }?.let {
            defaultCountryCode = it
            markChanged()
        }
    }

    fun updateSelectedCountryCode(newSelectedCountryCode: PhoneCountryCode) = dispatcher.schedule {
        selectedCountryCode = newSelectedCountryCode
        markChanged()
    }

    private fun loadCountryCodesFromDisk(context: Context): List<PhoneCountryCode> =
        InputStreamReader(context.assets.open(COUNTRIES_ASSET), Charsets.UTF_8).use { reader ->
            Gson().fromJson(reader, Array<PhoneCountryCode>::class.java).toList()
        }

    companion object {
        private const val COUNTRIES_ASSET = "data/countries.json"
        private const val CACHE_KEY_SELECTED_COUNTRY_CODE = "CACHE_KEY_PHONE_COUNTRY_CODE_V2"
    }
}
