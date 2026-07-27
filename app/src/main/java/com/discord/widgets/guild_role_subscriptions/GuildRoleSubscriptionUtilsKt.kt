@file:JvmName("GuildRoleSubscriptionUtilsKt")

package com.discord.widgets.guild_role_subscriptions

import android.content.Context
import com.discord.R
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionFreeTrialIntervalTypes
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionGroupListing
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionTierListing
import com.discord.api.guildrolesubscription.SubscriptionTrialInterval
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.i18n.i18nPlural
import com.discord.models.domain.ModelSubscription
import com.discord.utilities.permissions.PermissionUtils

/**
 * "7 days" / "1 month" — the human label for a free-trial length.
 *
 * A day-based trial whose length is a whole number of weeks is rendered in weeks, because that is
 * how the creator picked it (the picker offers 1 day and 7 days).
 */
fun SubscriptionTrialInterval.getFormattedLabel(context: Context): CharSequence = when (interval) {
    GuildRoleSubscriptionFreeTrialIntervalTypes.MONTHLY ->
        context.i18nPlural(R.plurals.duration_months_capitalize_months, intervalCount, "months" to intervalCount)
    GuildRoleSubscriptionFreeTrialIntervalTypes.YEARLY ->
        context.i18nPlural(R.plurals.duration_years_capitalize_years, intervalCount, "years" to intervalCount)
    GuildRoleSubscriptionFreeTrialIntervalTypes.DAILY ->
        if (intervalCount > 0 && intervalCount % DAYS_PER_WEEK == 0) {
            val weeks = intervalCount / DAYS_PER_WEEK
            context.i18nPlural(R.plurals.duration_weeks_capitalize_weeks, weeks, "weeks" to weeks)
        } else {
            context.i18nPlural(R.plurals.duration_days_capitalize_days, intervalCount, "days" to intervalCount)
        }
}

private const val DAYS_PER_WEEK = 7

/**
 * Whether the plan should be treated as gating the whole server, inferred from the guild rather
 * than from the stored flag.
 *
 * The server only honours the flag once a tier is actually published, so an unpublished storefront
 * answers `false`. Once one is published, the truth is whether `@everyone` has lost
 * `VIEW_CHANNEL` — that is what "the whole server is behind the paywall" means in practice, and it
 * can be changed from the roles screen behind the listing's back.
 *
 * `null` means "no tiers loaded yet", which callers treat as "leave the stored flag alone".
 */
fun GuildRoleSubscriptionGroupListing.getFullServerGatingOverwrite(
    guildRoles: Map<Long, GuildRole>?,
): Boolean? {
    val hasPublishedTier = subscriptionListings?.any(GuildRoleSubscriptionTierListing::published)
        ?: return null
    if (!hasPublishedTier) return null
    // The @everyone role's id is the guild id.
    val everyoneRole = guildRoles?.get(guildId)
    return !PermissionUtils.canRole(Permission.VIEW_CHANNEL, everyoneRole, null)
}

/** The tier's price in US cents. A tier always has exactly one plan; 0 while it is still a draft. */
fun GuildRoleSubscriptionTierListing.getPrice(): Int = subscriptionPlans.firstOrNull()?.price ?: 0

/** Whether any of [userSubscriptions] pays for one of this storefront's tiers. */
fun GuildRoleSubscriptionGroupListing.hasUserActiveSubscriptionFor(
    userSubscriptions: List<ModelSubscription>,
): Boolean {
    val subscribedPlanIds = userSubscriptions
        .mapNotNull { subscription -> subscription.items.firstOrNull()?.planId }
        .toSet()
    return subscriptionListings.orEmpty().any { tier ->
        tier.subscriptionPlans.firstOrNull()?.id?.let(subscribedPlanIds::contains) == true
    }
}
