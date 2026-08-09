package com.discord.utilities.search.query

/**
 * The search filters the client understands.
 *
 * Declaration order is the order the suggestion list offers them, which is the reference's mobile
 * filter order: `from, in, has, mentions, during, before, after, authorType`. The remaining entries
 * are parsed but never suggested — [PINNED] is web-only in the reference, and the three text
 * filters are reachable by typing their keyword.
 */
enum class FilterType {
    FROM,
    IN,
    HAS,
    MENTIONS,
    DURING,
    BEFORE,
    AFTER,
    AUTHOR_TYPE,
    LINK_FROM,
    FILE_TYPE,
    FILE_NAME,
    PINNED,
    ;

    companion object {
        /** What the picker offers, in order. */
        val OFFERED = listOf(FROM, IN, HAS, MENTIONS, DURING, BEFORE, AFTER, AUTHOR_TYPE)
    }
}
