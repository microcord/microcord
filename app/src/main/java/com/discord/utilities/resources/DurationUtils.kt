@file:JvmName("DurationUtils")

package com.discord.utilities.resources

import android.content.Context
import android.widget.TextView
import com.discord.R
import com.discord.i18n.FormatUtils

/**
 * Renders a duration as a localized phrase ("30 mins", "7 days").
 *
 * Reference: `com.discord.utilities.resources.DurationUtilsKt`. Not to be confused with
 * `utilities/duration/DurationUtils`, which renders a live countdown (`1d 4h 23m 6s`); this one
 * names a single unit.
 */

/** `quantity` [unit]s, pluralised and wrapped by the unit's own string. */
fun Context.formatDuration(unit: DurationUnit, quantity: Int): CharSequence =
    FormatUtils.format(
        getString(unit.stringRes),
        unit.placeholder to getI18nPluralString(
            unit.quantityPluralRes,
            quantity,
            unit.placeholder to quantity,
        ),
    )

/**
 * The invite-expiry dropdown label for [inviteExpirationDurationSeconds].
 *
 * Only the six values the API offers are accepted, plus `0` for "never"; anything else is a
 * programming error and throws, as in the reference.
 */
fun Context.formatInviteExpireAfterString(inviteExpirationDurationSeconds: Int): CharSequence =
    when (inviteExpirationDurationSeconds) {
        // `no_user_limit` ("No Limit") is deliberate — the same string doubles as the
        // never-expires label in the reference.
        0 -> getString(R.string.no_user_limit)
        1800 -> formatDuration(DurationUnit.MINS, 30)
        3600 -> formatDuration(DurationUnit.HOURS, 1)
        21600 -> formatDuration(DurationUnit.HOURS, 6)
        43200 -> formatDuration(DurationUnit.HOURS, 12)
        86400 -> formatDuration(DurationUnit.DAYS, 1)
        604800 -> formatDuration(DurationUnit.DAYS, 7)
        else -> throw IllegalArgumentException(
            "Invalid expiration duration $inviteExpirationDurationSeconds",
        )
    }

/** [formatDuration] straight into a [TextView]. */
fun TextView.setDurationText(unit: DurationUnit, quantity: Int) {
    text = context.formatDuration(unit, quantity)
}
