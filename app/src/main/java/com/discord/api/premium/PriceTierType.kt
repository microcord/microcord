package com.discord.api.premium

/** Which price-tier table a SKU's price ids are drawn from. */
enum class PriceTierType(val apiValue: Int) {
    UNKNOWN(-1),
    GUILD_ROLE_SUBSCRIPTIONS(1),
}
