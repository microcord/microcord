@file:JvmName("GuildInviteUiHelper")

package com.discord.widgets.guilds.invite

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppToast
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.domain.ModelInvite
import com.discord.models.experiments.domain.Experiment
import com.discord.models.invite.InviteUtils
import kotlin.math.roundToInt

/**
 * The copy/share actions and the human-readable settings strings shared by every invite surface.
 *
 * Analytics are **not** wired: the reference routes `inviteCopied` / `inviteShareClicked` through
 * `StoreAnalytics`, which this project neither constructs nor publishes on `StoreStream`. The
 * calls are marked below rather than approximated, so the copy/share behaviour itself is real.
 */

/** Extra carrying the analytics source through a screen intent. */
const val EXTRA_ORIGIN_SOURCE = "com.discord.intent.ORIGIN_SOURCE"

fun copyLinkClick(context: Context, invite: ModelInvite?, mostRecentIntent: Intent) =
    copyLinkClick(context, invite, mostRecentIntent.getStringExtra(EXTRA_ORIGIN_SOURCE).orEmpty())

@Suppress("UNUSED_PARAMETER")
fun copyLinkClick(context: Context, invite: ModelInvite?, analyticsSource: String) {
    // Reference: AnalyticsTracker.inviteCopied(invite, analyticsSource) — needs StoreAnalytics.
    copyLink(context, getInviteLink(invite, context.resources))
}

@Suppress("UNUSED_PARAMETER")
fun copyLinkClick(
    context: Context,
    guildInvite: GuildInvite,
    channel: Channel?,
    analyticsSource: String,
) {
    // Reference: AnalyticsTracker.inviteCopied(guildInvite, channel, analyticsSource).
    copyLink(context, guildInvite.toLink())
}

fun shareLinkClick(context: Context?, invite: ModelInvite) {
    // Reference: AnalyticsTracker.inviteShareClicked(invite) — needs StoreAnalytics.
    context ?: return
    shareLink(context, getInviteLink(invite, context.resources))
}

@Suppress("UNUSED_PARAMETER")
fun shareLinkClick(context: Context?, guildInvite: GuildInvite, channel: Channel?) {
    // Reference: AnalyticsTracker.inviteShareClicked(guildInvite, channel).
    context ?: return
    shareLink(context, guildInvite.toLink())
}

private fun copyLink(context: Context, inviteLink: String) =
    AppToast.copyToClipboard(context, inviteLink, R.string.invite_link_copied)

private fun shareLink(context: Context, inviteLink: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, inviteLink)
    val title = context.getString(R.string.tip_instant_invite_title3)
    context.startActivity(Intent.createChooser(send, title))
}

/**
 * `https://discord.gg/<code>` for a resolved invite, or the bare host while the link is still
 * being generated — never an empty string, because the label is always visible.
 */
private fun getInviteLink(inviteModel: ModelInvite?, resources: Resources): String =
    inviteModel?.toLink(resources, InviteUtils.HOST_INVITE) ?: InviteUtils.HOST_INVITE

/** "Invite links expire every 7 days by default", gated on the invite-expiration experiment. */
fun getInviteLinkText(context: Context, experiment: Experiment?): CharSequence {
    val days = if (experiment?.bucket == 1) 7 else 1
    return context.i18nFormat(
        R.string.invite_links_expire_after_default,
        "days" to context.i18nPlural(R.plurals.duration_days_days, days, "days" to days),
    )
}

/** The one-line summary under the invite link when only the expiry is configurable. */
fun getInviteSettingsText(context: Context, expirationDurationMs: Long): CharSequence =
    if (expirationDurationMs <= 0L) {
        context.getString(R.string.max_age_never_description_mobile)
    } else {
        context.i18nFormat(
            R.string.invite_expired_subtext_mobile,
            "maxAge" to humanizeDurationRounded(context, expirationDurationMs),
        )
    }

/** The two-line summary — expiry plus use count — shown by the invite-settings surfaces. */
fun getInviteSettingsText(context: Context, expirationDurationMs: Long, maxUses: Int): CharSequence {
    val usesText = if (maxUses == 0) {
        context.getString(R.string.max_uses_description_unlimited_uses)
    } else {
        context.i18nPlural(
            R.plurals.max_uses_description_mobile_maxUses,
            maxUses,
            "maxUses" to maxUses,
        )
    }
    return if (expirationDurationMs <= 0L) {
        context.i18nFormat(
            R.string.invite_settings_description_no_expiration,
            "maxAge" to context.getString(R.string.max_age_never_description_mobile),
            "maxUses" to usesText,
        )
    } else {
        context.i18nFormat(
            R.string.invite_settings_expired_description,
            "maxAge" to humanizeDurationRounded(context, expirationDurationMs),
            "maxUses" to usesText,
        )
    }
}

/**
 * The label on one of the "expires after" radio buttons.
 *
 * Only the seven values in [ModelInvite.Settings.EXPIRES_AFTER_ARRAY] are legal; anything else is
 * a programming error and throws, exactly as the reference does. "Never" reuses the generic
 * `no_user_limit` string — that is the reference's own choice of resource, not a substitution.
 *
 * Reference: `com.discord.utilities.resources.DurationUtilsKt.formatInviteExpireAfterString`.
 * **Belongs in `utilities/resources/`** once that package exists.
 */
fun formatInviteExpireAfterString(context: Context, inviteExpirationDurationSeconds: Int): CharSequence =
    when (inviteExpirationDurationSeconds) {
        ModelInvite.Settings.NEVER -> context.getString(R.string.no_user_limit)
        ModelInvite.Settings.HALF_HOUR -> context.formatDuration(DurationUnit.MINS, 30)
        ModelInvite.Settings.ONE_HOUR -> context.formatDuration(DurationUnit.HOURS, 1)
        ModelInvite.Settings.SIX_HOURS -> context.formatDuration(DurationUnit.HOURS, 6)
        ModelInvite.Settings.TWELVE_HOURS -> context.formatDuration(DurationUnit.HOURS, 12)
        ModelInvite.Settings.ONE_DAY -> context.formatDuration(DurationUnit.DAYS, 1)
        ModelInvite.Settings.SEVEN_DAYS -> context.formatDuration(DurationUnit.DAYS, 7)
        else -> throw IllegalArgumentException(
            "Invalid expiration duration $inviteExpirationDurationSeconds",
        )
    }

/**
 * The unit half of a duration label. Only the units the invite surfaces use are declared; the
 * reference enum also has `MINUTES`, `WEEKS` and `MONTHS`, which nothing here reaches.
 *
 * Reference: `com.discord.utilities.resources.DurationUnit`.
 */
internal enum class DurationUnit(
    val stringRes: Int,
    val quantityPluralRes: Int,
    /** Both the wrapper string and its plural use the unit's own name as the placeholder key. */
    val placeholder: String,
) {
    SECONDS(R.string.duration_seconds, R.plurals.duration_seconds_seconds, "seconds"),
    MINS(R.string.duration_mins, R.plurals.duration_mins_mins, "mins"),
    HOURS(R.string.duration_hours, R.plurals.duration_hours_hours, "hours"),
    DAYS(R.string.duration_days, R.plurals.duration_days_days, "days"),
}

internal fun Context.formatDuration(unit: DurationUnit, quantity: Int): CharSequence =
    i18nFormat(
        unit.stringRes,
        unit.placeholder to
            i18nPlural(unit.quantityPluralRes, quantity, unit.placeholder to quantity),
    )

/**
 * "a few seconds" / "3 minutes" / "2 hours" / "5 days", rounded to the nearest whole unit.
 *
 * The thresholds are the reference's and are deliberately not the obvious ones: a duration is
 * shown in seconds up to **54 s**, in minutes up to **54 min**, and in hours up to **21.6 h** — in
 * each case 90 % of the next unit, so the rounded number never reads as the boundary value.
 *
 * Reference: `com.discord.utilities.duration.DurationUtilsKt.humanizeDurationRounded`.
 * **Belongs in `utilities/duration/DurationUtils.kt`.**
 */
fun humanizeDurationRounded(context: Context, durationMillis: Long): CharSequence = when {
    durationMillis <= 30_000L -> context.getString(R.string.humanize_duration_a_few_seconds)
    durationMillis <= 54_000L -> context.humanized(
        R.string.humanize_duration_seconds,
        R.plurals.humanize_duration_seconds_time,
        (durationMillis / 1_000f).roundToInt(),
    )
    durationMillis <= 3_240_000L -> context.humanized(
        R.string.humanize_duration_minutes,
        R.plurals.humanize_duration_minutes_time,
        (durationMillis / 60_000f).roundToInt(),
    )
    durationMillis <= 77_760_000L -> context.humanized(
        R.string.humanize_duration_hours,
        R.plurals.humanize_duration_hours_time,
        (durationMillis / 3_600_000f).roundToInt(),
    )
    else -> context.humanized(
        R.string.humanize_duration_days,
        R.plurals.humanize_duration_days_time,
        (durationMillis / 86_400_000f).roundToInt(),
    )
}

/** The `humanize_duration_*` strings are all `{time}` wrappers around a `{#}` plural. */
private fun Context.humanized(stringRes: Int, pluralRes: Int, quantity: Int): CharSequence =
    i18nFormat(stringRes, "time" to i18nPlural(pluralRes, quantity, "#" to quantity))
