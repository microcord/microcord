package com.discord.widgets.chat.input.autocomplete

import com.discord.utilities.collections.MultiListIterator

/**
 * One row the autocomplete popup can offer, and the rules for matching it against typed text.
 *
 * Three distinct string sets exist per candidate, and conflating them is the usual source of bugs:
 *
 * * [getInputTextMatchers] — the canonical typed forms, e.g. `@name#0001`, `#general`, `:smile:`.
 *   These are matched both while typing (autocomplete) and when scanning finished input for
 *   mentions to pillify.
 * * [getAutocompleteTextMatchers] — extra forms offered *only while typing*, e.g. a guild
 *   nickname. They must not pillify finished text, because two members can share a nickname.
 * * [getHiddenInputTextMatchers] — extra forms recognised *only in finished text*, e.g. a Unicode
 *   emoji that does not require colons. They must not appear as autocomplete matches, or typing
 *   a bare `a` would offer every emoji whose name contains one.
 *
 * [iterateAutocompleteMatchers] and [iterateTextMatchers] are the two combinations, iterated
 * lazily via [MultiListIterator] because the user list can be tens of thousands of entries.
 */
sealed class Autocompletable {

    /** What replaces the token in the composer when this row is picked. */
    abstract fun getInputReplacement(): String

    /** Canonical typed forms. Never empty. */
    abstract fun getInputTextMatchers(): List<String>

    open fun getAutocompleteTextMatchers(): List<String>? = null

    open fun getHiddenInputTextMatchers(): List<String>? = null

    open fun leadingIdentifier(): LeadingIdentifier = LeadingIdentifier.NONE

    fun iterateAutocompleteMatchers(): Iterator<String> {
        val extra = getAutocompleteTextMatchers()
        return if (extra.isNullOrEmpty()) getInputTextMatchers().iterator()
        else MultiListIterator(getInputTextMatchers(), extra)
    }

    fun iterateTextMatchers(): Iterator<String> {
        val extra = getHiddenInputTextMatchers()
        return if (extra.isNullOrEmpty()) getInputTextMatchers().iterator()
        else MultiListIterator(getInputTextMatchers(), extra)
    }

    fun matchesText(text: String): Boolean =
        getInputTextMatchers().contains(text) || getHiddenInputTextMatchers()?.contains(text) == true
}
