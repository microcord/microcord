package com.discord.i18n

import android.content.Context
import android.view.View
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Discord's translated strings do not use Android's positional format arguments.
 * They carry named `{placeholder}` tokens and `!!hook!!` emphasis markers, so a
 * plain `Resources.getString`/`getQuantityString` leaves the raw token visible to
 * the user (for example `"{members} Members"`).
 *
 * Every screen must therefore render a string through [format] instead of setting
 * the raw resource text. Tokens without a supplied argument are left untouched,
 * matching the reference behaviour of rendering the literal token rather than
 * throwing.
 *
 * Rich inline markup (bold, links, custom hook styling) is not reconstructed here;
 * only the hook markers themselves are removed so the surrounding text renders
 * cleanly.
 */
object FormatUtils {
    /** Matches `{name}`; the token body may not contain whitespace. */
    private val PLACEHOLDER = Regex("""\{(\S+?)}""")

    private const val HOOK_MARKER = "!!"

    @JvmStatic
    fun format(template: CharSequence, args: Map<String, Any?>): String {
        val substituted = if (args.isEmpty()) {
            template.toString()
        } else {
            PLACEHOLDER.replace(template) { match ->
                val name = match.groupValues[1]
                if (args.containsKey(name)) args.getValue(name).toString() else match.value
            }
        }
        return stripHookMarkers(substituted)
    }

    @JvmStatic
    fun format(template: CharSequence, vararg args: Pair<String, Any?>): String =
        format(template, args.toMap())

    private fun stripHookMarkers(text: String): String =
        if (text.contains(HOOK_MARKER)) text.replace(HOOK_MARKER, "") else text
}

fun Context.i18nFormat(@StringRes stringRes: Int, vararg args: Pair<String, Any?>): String =
    FormatUtils.format(getString(stringRes), *args)

fun Context.i18nPlural(
    @PluralsRes pluralRes: Int,
    quantity: Int,
    vararg args: Pair<String, Any?>,
): String = FormatUtils.format(resources.getQuantityString(pluralRes, quantity), *args)

fun View.i18nFormat(@StringRes stringRes: Int, vararg args: Pair<String, Any?>): String =
    context.i18nFormat(stringRes, *args)

fun View.i18nPlural(
    @PluralsRes pluralRes: Int,
    quantity: Int,
    vararg args: Pair<String, Any?>,
): String = context.i18nPlural(pluralRes, quantity, *args)
