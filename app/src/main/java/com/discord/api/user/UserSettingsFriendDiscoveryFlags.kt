package com.discord.api.user

/** Bit flags controlling how other people are allowed to find this account. */
object UserSettingsFriendDiscoveryFlags {
    const val NONE = 0
    const val FIND_BY_PHONE = 2
    const val FIND_BY_EMAIL = 4
}
