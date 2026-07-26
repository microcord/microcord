package com.discord.api.friendsuggestions

/** How discoverable the user has made themselves in other people's friend suggestions. */
enum class AllowedInSuggestionsType(val apiValue: Int) {
    DISABLED(-1),
    MUTUAL_CONTACT_INFO_ONLY(1),
    ANYONE_WITH_CONTACT_INFO(2),
}
