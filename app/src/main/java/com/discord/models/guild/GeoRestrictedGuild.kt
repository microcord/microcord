package com.discord.models.guild

/**
 * A guild the user is in but cannot reach from their current region.
 *
 * It never becomes a [Guild]: no channels, roles or members are sent for it, and READY drops any
 * cached record. All that arrives is enough to draw a row and name it in the dialog explaining why
 * it cannot be opened.
 */
data class GeoRestrictedGuild(
    val id: Long,
    val name: String,
    val icon: String?,
) {
    val shortName: String = Guild.toShortName(name)

    fun hasIcon() = !icon.isNullOrEmpty()
}
