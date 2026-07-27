@file:JvmName("StringResourceUtils")

package com.discord.utilities.resources

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.discord.i18n.FormatUtils
import com.discord.utilities.string.format

/**
 * Plural helpers that render through Discord's `{name}` placeholder syntax.
 *
 * `Resources.getQuantityString(id, quantity, args)` cannot be used directly: the translated
 * strings carry named tokens rather than `%1$s`, so the positional overload would leave `{days}`
 * on screen. Everything here resolves the plural form first and then substitutes through
 * [FormatUtils].
 *
 * Reference: `com.discord.utilities.resources.StringResourceUtilsKt`.
 */

/** The plural form for [quantity], with named [args] substituted. */
fun Context.getI18nPluralString(
    @PluralsRes resId: Int,
    quantity: Int,
    vararg args: Pair<String, Any?>,
): CharSequence = FormatUtils.format(resources.getQuantityString(resId, quantity), *args)

/**
 * [getI18nPluralString] with a dedicated string for zero.
 *
 * Android's plural rules have no `zero` category in English, so a count of nothing has to be a
 * separate resource ("No members" rather than "0 members").
 */
fun Resources.getQuantityString(
    @PluralsRes id: Int,
    @StringRes zeroCountStringId: Int,
    quantity: Int,
    vararg args: Pair<String, Any?>,
): CharSequence =
    if (quantity == 0) {
        FormatUtils.format(getString(zeroCountStringId))
    } else {
        FormatUtils.format(getQuantityString(id, quantity), *args)
    }

/**
 * [getI18nPluralString] with locale-aware number formatting for numeric arguments.
 *
 * Any `Int` argument is replaced by [quantity] rendered through the user's locale, so a count of
 * 1,234 reads `1.234` in de-DE rather than `1234`. The reference substitutes the *quantity* for
 * every numeric argument regardless of the argument's own value; that is reproduced here.
 */
fun Resources.getQuantityString(
    context: Context,
    @PluralsRes resId: Int,
    quantity: Int,
    vararg args: Pair<String, Any?>,
): CharSequence = FormatUtils.format(
    getQuantityString(resId, quantity),
    *args.map { (name, value) ->
        name to if (value is Int) quantity.format(context) else value
    }.toTypedArray(),
)
