package com.discord.widgets.hubs

/**
 * Initials of a guild name, used as the avatar fallback when a listed hub has no icon.
 *
 * Port of `com.discord.utilities.guilds.GuildUtilsKt.computeShortName`: drop the possessive `'s `,
 * keep the first letter of each word, then drop the whitespace between them.
 * `models/guild/Guild.shortName` only performs the first of the three steps, so it cannot be reused.
 *
 * The directories wave ported the same function into `com.discord.widgets.directories`. Both are
 * placeholders for `com.discord.utilities.guilds.GuildUtils`, which does not exist yet; converge them
 * there rather than importing one package's copy into the other.
 */
internal fun computeGuildInitials(name: String): String {
    val withoutPossessive = name.replace(POSSESSIVE, " ")
    val initials = WORD.replace(withoutPossessive) { it.groupValues[1] }
    return initials.replace(WHITESPACE, "")
}

private val POSSESSIVE = Regex("'s ")
private val WORD = Regex("(\\w)(\\w*)")
private val WHITESPACE = Regex("\\s")
