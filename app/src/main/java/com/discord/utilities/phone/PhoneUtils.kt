package com.discord.utilities.phone

import android.content.Context
import com.discord.models.phone.PhoneCountryCode
import java.util.Locale

/**
 * Phone-number helpers shared by the phone inputs and the country-code picker.
 *
 * The reference does **not** use libphonenumber: `javap -c` of the reference `PhoneUtils` shows the
 * literal `^[-() \d]+$` regex below and the dex2jar contains no `com/google/i18n/phonenumbers`
 * classes at all. No parsing/formatting dependency is needed for parity.
 */
object PhoneUtils {
    // Kept verbatim from the reference (`ldc "^[-() \\d]+$"`). `\d` is escaped for Kotlin, not for
    // the regex engine; there is no bare `}` or `]` here, so ICU accepts it on device.
    private val partialPhone = Regex("^[-() \\d]+$")

    fun isValidPhoneFragment(input: String) = partialPhone.matches(input)

    fun isLikelyToContainPhoneNumber(input: String) = input.length >= 3 && isValidPhoneFragment(input)

    /**
     * The localised display name for [data], falling back to its English `name`.
     *
     * REFERENCE BUG, REPRODUCED DELIBERATELY. The reference looks the string up as
     * `country_name_<phoneCountryCode.lowercase()>` — verified at bytecode level:
     * `invokevirtual PhoneCountryCode.getPhoneCountryCode()` followed by `toLowerCase(locale)` and
     * `Resources.getIdentifier("country_name_" + …, "string", packageName)`. A dialling code such as
     * `+1` can never be a resource name, so `getIdentifier` always returns `0` and every row falls
     * back to the English `name`. The 256 recovered `country_name_xx` strings are keyed on the
     * **alpha-2** code and are therefore dead in the reference.
     *
     * Changing the key to [PhoneCountryCode.alpha2] would light all 256 translations up; it is left
     * as-is for parity and recorded in `docs/parity-audit/followups.md` for an explicit decision.
     */
    fun getTranslatedStringForCountry(data: PhoneCountryCode, context: Context): String {
        val key = "country_name_" + data.phoneCountryCode.lowercase(Locale.getDefault())
        val identifier = context.resources.getIdentifier(key, "string", context.packageName)
        return if (identifier == 0) data.name else context.getString(identifier)
    }
}
