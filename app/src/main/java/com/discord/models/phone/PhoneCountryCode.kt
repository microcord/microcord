package com.discord.models.phone

data class PhoneCountryCode(
    val name: String,
    val alpha2: String,
    val phoneCountryCode: String,
) {
    companion object {
        val DEFAULT_COUNTRY_CODE = PhoneCountryCode("United States", "US", "+1")
        val MISSING_COUNTRY_CODE = PhoneCountryCode("", "", "")
    }
}
