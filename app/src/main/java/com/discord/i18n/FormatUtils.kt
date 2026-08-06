package com.discord.i18n

import android.content.Context
import android.view.View
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

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
    // Both braces are escaped: Android's ICU regex engine rejects a bare `}` with
    // PatternSyntaxException, where the JVM tolerates it. A JVM-only unit test cannot catch this.
    private val PLACEHOLDER = Regex("""\{(\S+?)\}""")

    private const val HOOK_MARKER = "!!"

    /** `$[label](hookName)`; there is no span to hang here, so only the label survives. */
    private val HOOK_LINK = Regex("""\$\[([^\[\]]*)\]\(([^()\s]+)\)""")

    @JvmStatic
    fun format(template: CharSequence, args: Map<String, Any?>): String =
        stripHookMarkers(substitute(template, args))

    /**
     * Substitutes [args] but leaves the `!!…!!` emphasis markers in place.
     *
     * [format] drops them because it returns plain text and has nowhere to put the emphasis. A
     * caller that renders spans wants them: they are what says *which* run the screen should style,
     * and searching the finished sentence for the substituted value instead would also match an
     * occurrence that happens to appear elsewhere in it.
     */
    @JvmStatic
    fun substitute(template: CharSequence, args: Map<String, Any?>): String =
        if (args.isEmpty()) {
            template.toString()
        } else {
            PLACEHOLDER.replace(template) { match ->
                args[match.groupValues[1]]?.toString() ?: match.value
            }
        }

    @JvmStatic
    fun format(template: CharSequence, vararg args: Pair<String, Any?>): String =
        format(template, args.toMap())

    internal fun formatOrdered(template: CharSequence, args: Array<out Any?>): String {
        if (args.isEmpty()) return stripHookMarkers(template.toString())
        val positional = PLACEHOLDER.replace(template, "%s")
        return stripHookMarkers(String.format(Locale.getDefault(), positional, *args))
    }

    private fun stripHookMarkers(text: String): String {
        // Unwrap before stripping: the label itself is usually `!!{token}!!`.
        val unwrapped = if (text.contains("\$[")) {
            HOOK_LINK.replace(text) { it.groupValues[1] }
        } else {
            text
        }
        return if (unwrapped.contains(HOOK_MARKER)) unwrapped.replace(HOOK_MARKER, "") else unwrapped
    }
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
