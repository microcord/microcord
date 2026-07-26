package com.discord.app

import android.content.Context
import androidx.annotation.StringRes

/**
 * Resolves a string resource that uses Discord's `{name}` placeholder syntax.
 *
 * The recovered `strings.xml` keeps the upstream i18n format rather than Android's positional
 * `%1$s`, so `Context.getString(id, args)` leaves the placeholders untouched. Values are
 * substituted in the order the placeholders appear; a placeholder with no matching value is left
 * as-is rather than rendered as `null`.
 */
internal fun Context.getNamedFormatString(@StringRes stringResId: Int, vararg values: Any?): String {
    val template = getString(stringResId)
    var index = 0
    return NAMED_PLACEHOLDER.replace(template) { match ->
        val value = values.getOrNull(index++)
        value?.toString() ?: match.value
    }
}

private val NAMED_PLACEHOLDER = Regex("""\{[A-Za-z0-9_]+}""")
