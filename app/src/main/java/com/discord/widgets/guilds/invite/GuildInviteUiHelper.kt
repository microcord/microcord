@file:JvmName("GuildInviteFormattingKt")

package com.discord.widgets.guilds.invite

import android.content.Context
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.domain.ModelInvite
import kotlin.math.roundToInt

/** Extra carrying the analytics source through a screen intent. */
const val EXTRA_ORIGIN_SOURCE = "com.discord.intent.ORIGIN_SOURCE"

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
