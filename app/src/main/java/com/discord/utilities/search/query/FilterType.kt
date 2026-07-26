package com.discord.utilities.search.query

/**
 * The four search filters the mobile client understands.
 *
 * Ordinal order is load-bearing — `UserNode.updateQuery` and `FilterSuggestion` both dispatch on it
 * — and is `FROM, MENTIONS, HAS, IN`. (The reference's decompiled source lists them alphabetically;
 * the real order comes from the enum's static initialiser.)
 */
enum class FilterType {
    FROM,
    MENTIONS,
    HAS,
    IN,
}
