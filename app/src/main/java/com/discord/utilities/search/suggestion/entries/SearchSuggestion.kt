package com.discord.utilities.search.suggestion.entries

/**
 * One row of the suggestion list under the search box.
 *
 * [Category] both groups the list (each run of one category gets a header) and selects the row
 * layout. Ordinal order is `FILTER, MENTIONS_USER, FROM_USER, HAS, BEFORE_DATE, IN_CHANNEL,
 * RECENT_QUERY`, recovered from the enum's static initialiser; the header-label table is indexed by
 * it. `BEFORE_DATE` has no producer in 126.21 — date filters are desktop-only — but it keeps its
 * slot so the ordinals of the categories after it stay correct.
 */
interface SearchSuggestion {
    val category: Category

    enum class Category {
        FILTER,
        MENTIONS_USER,
        FROM_USER,
        HAS,
        BEFORE_DATE,
        IN_CHANNEL,
        RECENT_QUERY,
        AUTHOR_TYPE,
    }
}
