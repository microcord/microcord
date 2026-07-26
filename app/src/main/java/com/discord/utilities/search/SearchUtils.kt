package com.discord.utilities.search

/** Small text helpers shared by search and by the quick switcher. */
object SearchUtils {

    /**
     * Whether every character of [query] appears in [target], in order.
     *
     * A space in the query also matches a hyphen, so "voice chat" matches "voice-chat".
     */
    fun fuzzyMatch(query: String, target: String): Boolean {
        if (query.length > target.length) return false
        if (query.length == target.length) return query == target

        var targetIndex = 0
        query.forEach { queryChar ->
            var matched = false
            while (targetIndex < target.length) {
                val targetChar = target[targetIndex]
                targetIndex++
                if (targetChar == queryChar || (queryChar == ' ' && targetChar == '-')) {
                    matched = true
                    break
                }
            }
            if (!matched) return false
        }
        return true
    }

    /** Splits free text into the distinct words a search should look for, minus punctuation. */
    fun getQueriesFromSearchText(searchText: String): Set<String> {
        if (searchText.isEmpty()) return emptySet()
        return PUNCTUATION.replace(searchText.trim(), "")
            .let { WHITESPACE.replace(it, " ") }
            .split(' ')
            .toSet()
    }

    private val PUNCTUATION = Regex("""([!.;,\-—–?"'])""")
    private val WHITESPACE = Regex("""(\n|\t|\s)""")
}
