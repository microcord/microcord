@file:JvmName("GuildUtils")

package com.discord.utilities.guilds

import com.discord.api.guildmember.GuildMember
import com.discord.models.guild.Guild
import com.discord.utilities.premium.PremiumUtils

/**
 * Initials of a guild name — the avatar fallback for a server with no icon.
 *
 * Three steps, in order: drop the possessive `'s ` so "Mantik's Server" reads `MS` and not `MSS`,
 * keep the first character of every word, then remove the whitespace that separated them.
 *
 * `\w` is Unicode-unaware here exactly as in the reference, so a name written in a non-Latin script
 * yields an empty string and the caller falls back to the default icon.
 */
fun computeShortName(name: String): String =
    name.replace(POSSESSIVE, " ")
        .replace(WORD) { it.groupValues[1] }
        .replace(WHITESPACE, "")

// Regexes are declared with every metacharacter escaped: Android's ICU engine rejects bare
// closing delimiters that the JVM's engine tolerates.
private val POSSESSIVE = Regex("'s ")
private val WORD = Regex("(\\w)(\\w*)")
private val WHITESPACE = Regex("\\s")

/** Indexes a member list by user id, preserving encounter order. */
fun List<GuildMember>.asMap(): Map<Long, GuildMember> = associateBy { it.user.id }

/** Upload ceiling this guild's powerups grant, in bytes. */
fun getMaxFileSize(guild: Guild): Long = PremiumUtils.getGuildMaxFileSize(guild)
