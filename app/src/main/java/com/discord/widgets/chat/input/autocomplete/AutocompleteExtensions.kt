package com.discord.widgets.chat.input.autocomplete

import com.discord.app.AppLog

/**
 * Rewrites every recognised mention in composer text into the form the API expects.
 *
 * The composer holds what the user reads — `@Bob`, `#general`, `:smile:` — while the API wants
 * `<@1234>`, `<#5678>`, `<:smile:9012>`. [inputMentionsMap] is the range→candidate map built by
 * [AutocompleteModelUtils.mapInputToMentions]; this applies it.
 *
 * Ranges are applied in ascending start order, and each replacement shifts the text under the
 * later ones — which is exactly why the reference bounds-checks every range against the *current*
 * string rather than the original. A range that no longer fits is skipped and logged instead of
 * throwing: a stale map must never be able to lose a user's message.
 *
 * Reference: `AutocompleteExtensionsKt.replaceAutocompleteDataWithServerValues`.
 */
fun String.replaceAutocompleteDataWithServerValues(
    inputMentionsMap: Map<IntRange, Autocompletable>,
): String {
    var result = this
    for (range in inputMentionsMap.keys.sortedBy { it.first }) {
        val autocompletable = inputMentionsMap[range] ?: continue
        val replacement = autocompletable.getInputReplacement()
        if (range.first <= result.length && range.last <= result.length) {
            result = result.replaceRange(range.first, range.last, replacement)
        } else {
            AppLog.e(
                "Invalid mention position to insert $replacement into $result " +
                    "at [${range.first} - ${range.last}]. Original input: $this",
                IndexOutOfBoundsException(
                    "first ${range.first}, last ${range.last}, s.length() ${result.length}",
                ),
            )
        }
    }
    return result
}

/**
 * Collapses the per-identifier candidate buckets into one list, preserving bucket order.
 *
 * Reference: `InputAutocompletablesKt.flatten`.
 */
fun Map<LeadingIdentifier, List<Autocompletable>>.flatten(): List<Autocompletable> =
    values.flatten()
