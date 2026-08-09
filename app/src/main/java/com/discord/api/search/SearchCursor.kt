package com.discord.api.search

/**
 * The pagination token of a `messages/search/tabs` page.
 *
 * Treated as opaque: whatever the server returns for one page is echoed back verbatim to ask for
 * the next. [type] must agree with the request's `sort_by` — `timestamp` when sorting by time,
 * `score` when sorting by relevance.
 *
 * The server signals "no further pages" with an *empty object* rather than a null, which is what
 * [isEmpty] tests for.
 */
data class SearchCursor(
    val type: String? = null,
    val timestamp: String? = null,
    val score: Score? = null,
) {
    /** Relevance metadata for the last message of a page; only used by `score` cursors. */
    data class Score(
        val timestamp: String? = null,
        val score: Float? = null,
    )

    val isEmpty: Boolean get() = type == null && timestamp == null && score == null

    companion object {
        const val TYPE_TIMESTAMP = "timestamp"
        const val TYPE_SCORE = "score"
    }
}
