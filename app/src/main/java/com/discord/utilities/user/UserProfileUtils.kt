package com.discord.utilities.user

import android.content.Context
import com.discord.api.user.UserProfile
import com.discord.utilities.time.TimeUtils
import java.util.Date

/** Human-readable "Nitro since" date, or `null` when the user has no Nitro. */
fun UserProfile.getPremiumSince(context: Context): String? =
    premiumSince?.let { TimeUtils.getReadableTimeString(context, it) }

/** Human-readable "boosting since" date, or `null` when the user boosts nothing. */
fun UserProfile.getBoostingSince(context: Context): String? =
    premiumGuildSince?.let { TimeUtils.getReadableTimeString(context, it) }

/**
 * How many whole months the user has been boosting, which selects the boost badge tier.
 * `null` when they are not a booster at all.
 */
val UserProfile.guildBoostMonthsSubscribed: Int?
    get() = premiumGuildSince?.let {
        TimeUtils.getMonthsBetweenDates(Date(TimeUtils.parseUTCDate(it)), Date())
    }

val UserProfile.isGuildBooster: Boolean
    get() = !premiumGuildSince.isNullOrEmpty()

/** [UserProfile.mutualGuilds] indexed by guild id, preserving the server's ordering. */
val UserProfile.mutualGuildsById: Map<Long, UserProfile.GuildReference>
    get() = mutualGuilds.associateByTo(LinkedHashMap()) { it.id }
