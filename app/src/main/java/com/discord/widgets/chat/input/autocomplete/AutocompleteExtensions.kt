package com.discord.widgets.chat.input.autocomplete

import com.discord.app.AppLog

/**
 * Rewrites every recognised mention in composer text into the form the API expects.
 *
 * The composer holds what the user reads — `@Bob`, `#general`, `:smile:` — while the API wants
 * `<@1234>`, `<#5678>`, `<:smile:9012>`. [inputMentionsMap] is the range→candidate map built by
 * [AutocompleteModelUtils.mapInputToMentions]; this applies it.
 *
 * Ranges are applied **last-first**, and that ordering is the whole trick: a replacement only moves
 * text that follows it, so working backwards leaves every remaining range still valid against the
 * partially-rewritten string. Applying them in ascending order would corrupt the message as soon as
 * two mentions appeared and the replacement differed in length from the typed text.
 *
 * A range that no longer fits is skipped and logged rather than throwing — a stale map must never
 * be able to lose a user's message.
 *
 * `IntRange.last` is the **exclusive** end here: the map is built by
 * [AutocompleteModelUtils.mapInputToMentions] as `index until index + matcher.length`, expressed as
 * `IntRange(index, index + length)`.
 *
 * Reference: `AutocompleteExtensionsKt.replaceAutocompleteDataWithServerValues`.
 */
fun String.replaceAutocompleteDataWithServerValues(
    inputMentionsMap: Map<IntRange, Autocompletable>,
): String {
    var result = this
    for (range in inputMentionsMap.keys.sortedByDescending { it.first }) {
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
