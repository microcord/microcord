package com.discord.api.billing

/** `GET /users/@me/billing/country-code`: the country the request came from. */
data class BillingCountryCode(val countryCode: String?)
