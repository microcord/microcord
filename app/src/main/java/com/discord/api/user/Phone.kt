package com.discord.api.user

sealed class Phone {
    data object NoPhoneNumber : Phone()
    data class PhoneNumber(val number: String) : Phone()
}
