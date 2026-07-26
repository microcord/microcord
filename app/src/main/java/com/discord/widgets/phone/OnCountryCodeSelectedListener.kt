package com.discord.widgets.phone

import com.discord.models.phone.PhoneCountryCode

/** Reports the row the user tapped in [WidgetPhoneCountryCodeBottomSheet]. */
fun interface OnCountryCodeSelectedListener {
    fun onCountryCodeSelected(countryCode: PhoneCountryCode)
}
