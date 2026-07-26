package com.discord.widgets.phone

import android.content.Context
import com.discord.app.AppLog
import com.discord.models.phone.PhoneCountryCode
import com.discord.stores.StorePhone
import com.google.gson.Gson
import java.io.InputStreamReader

/**
 * Access point for the phone-country-code state the picker needs.
 *
 * The reference reads `StoreStream.getPhone()` directly. In this reconstruction `StorePhone` exists
 * (`com.discord.stores.StorePhone`) but `StoreStream` **neither constructs nor publishes it** — a
 * repo-wide search finds no reference to the class outside its own file — so there is no
 * `StoreStream.getPhone()` to call and no `Dispatcher` reachable from the widget layer to build one.
 *
 * This object is the seam that closes that gap:
 *
 *  - [store] is `null` until `StoreStream` installs it. Install it in `StoreStream.init` alongside
 *    the other stores, then delete this object and inline `StoreStream.getPhone()`.
 *  - [list] falls back to reading `assets/data/countries.json` — the *same* asset `StorePhone.init`
 *    loads — so the picker still shows the real country list while the store is unwired. The
 *    duplication is deliberate and disappears with this file.
 *  - [select] returns `false` when there is no store, which is how the sheet knows to hand the
 *    choice back through its own listener instead of relying on the store observable.
 */
object PhoneCountryCodes {

    private const val COUNTRIES_ASSET = "data/countries.json"

    /** Installed by `StoreStream` once `StorePhone` is part of the store graph. */
    @Volatile
    @JvmStatic
    var store: StorePhone? = null

    /**
     * Every selectable country, in the asset's own order (alphabetical by English name).
     *
     * Falls back to the raw asset when the store is unwired, and to an empty list when even that
     * fails — the sheet renders its empty state rather than showing a blank list with no
     * explanation.
     */
    fun list(context: Context): List<PhoneCountryCode> {
        store?.countryCodes?.takeIf { it.isNotEmpty() }?.let { return it }
        return loadFromAssets(context)
    }

    /** The country currently in effect, used to seed inputs before the user picks one. */
    fun current(): PhoneCountryCode =
        store?.getCountryCode() ?: PhoneCountryCode.DEFAULT_COUNTRY_CODE

    /** Records the user's choice. Returns `false` when [store] is not installed yet. */
    fun select(countryCode: PhoneCountryCode): Boolean {
        val store = this.store ?: return false
        store.updateSelectedCountryCode(countryCode)
        return true
    }

    private fun loadFromAssets(context: Context): List<PhoneCountryCode> = try {
        InputStreamReader(context.assets.open(COUNTRIES_ASSET), Charsets.UTF_8).use { reader ->
            Gson().fromJson(reader, Array<PhoneCountryCode>::class.java).orEmpty().toList()
        }
    } catch (failure: Exception) {
        AppLog.e("Could not read $COUNTRIES_ASSET for the country-code picker", failure)
        emptyList()
    }
}
