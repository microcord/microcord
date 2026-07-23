package com.discord.utilities.phone

object PhoneUtils {
    private val partialPhone = Regex("^[-() \\d]+$")

    fun isValidPhoneFragment(input: String) = partialPhone.matches(input)

    fun isLikelyToContainPhoneNumber(input: String) = input.length >= 3 && isValidPhoneFragment(input)
}
