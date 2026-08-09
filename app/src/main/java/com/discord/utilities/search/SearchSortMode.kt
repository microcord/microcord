package com.discord.utilities.search

import com.discord.api.search.SearchCursor

/**
 * How results are ordered, and therefore how they page.
 *
 * The reference's `SearchModes`. [cursorType] is not cosmetic: a `messages/search/tabs` cursor is
 * only valid for the sort it was minted under, so changing the sort has to discard the cursor and
 * restart from the first page.
 */
enum class SearchSortMode(val sortBy: String, val sortOrder: String, val cursorType: String) {
    NEWEST("timestamp", "desc", SearchCursor.TYPE_TIMESTAMP),
    OLDEST("timestamp", "asc", SearchCursor.TYPE_TIMESTAMP),
    MOST_RELEVANT("relevance", "desc", SearchCursor.TYPE_SCORE),
    ;

    companion object {
        val DEFAULT = NEWEST
    }
}
