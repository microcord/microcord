package com.discord.utilities.resources

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.discord.R

/**
 * A unit a duration can be rendered in, with the two resources needed to say it.
 *
 * [stringRes] is the wrapper (`"{days}"`) and [quantityPluralRes] is the pluralised body
 * (`"1 day"` / `"{days} days"`) — the wrapper exists so a locale can put punctuation or a
 * different word order around the pluralised part.
 *
 * `MINS` and `MINUTES` are both present and both mean minutes; they differ only in whether the
 * translation abbreviates.
 *
 * Reference: `com.discord.utilities.resources.DurationUnit`. Declaration order matches the
 * reference's `$VALUES` (`SECONDS, MINUTES, MINS, HOURS, DAYS, WEEKS, MONTHS`), so ordinals are
 * stable against any persisted value.
 *
 * **Addition:** [placeholder] is not in the reference. Discord's own renderer substitutes named
 * `{token}` placeholders positionally, so it never needed the token's name; this project's
 * `com.discord.i18n.FormatUtils` matches by name, so each unit has to declare the token its
 * strings use.
 */
enum class DurationUnit(
    @StringRes internal val stringRes: Int,
    @PluralsRes internal val quantityPluralRes: Int,
    internal val placeholder: String,
) {
    SECONDS(R.string.duration_seconds, R.plurals.duration_seconds_seconds, "seconds"),
    MINUTES(R.string.duration_minutes, R.plurals.duration_minutes_minutes, "minutes"),
    MINS(R.string.duration_mins, R.plurals.duration_mins_mins, "mins"),
    HOURS(R.string.duration_hours, R.plurals.duration_hours_hours, "hours"),
    DAYS(R.string.duration_days, R.plurals.duration_days_days, "days"),
    WEEKS(R.string.duration_weeks, R.plurals.duration_weeks_weeks, "weeks"),
    MONTHS(R.string.duration_months, R.plurals.duration_months_months, "months"),
}
