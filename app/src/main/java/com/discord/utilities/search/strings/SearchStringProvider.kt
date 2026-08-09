package com.discord.utilities.search.strings

/**
 * The localised words the search grammar is built from.
 *
 * The parser compiles its regexes from these, so a French client parses `de:` where an English one
 * parses `from:`. Injected rather than read from resources directly so the grammar can be tested
 * without a `Context`.
 */
interface SearchStringProvider {
    val fromFilterString: String
    val inFilterString: String
    val mentionsFilterString: String
    val hasFilterString: String
    val duringFilterString: String
    /** `on:`, the reference's alias for `during:`. */
    val onFilterString: String
    val beforeFilterString: String
    val afterFilterString: String
    val authorTypeFilterString: String
    val linkFromFilterString: String
    val fileTypeFilterString: String
    val fileNameFilterString: String
    val pinnedFilterString: String

    val linkAnswerString: String
    val embedAnswerString: String
    val fileAnswerString: String
    val videoAnswerString: String
    val imageAnswerString: String
    val soundAnswerString: String
    val stickerAnswerString: String
    val pollAnswerString: String
    val forwardAnswerString: String

    val userAuthorTypeAnswerString: String
    val botAuthorTypeAnswerString: String
    val webhookAuthorTypeAnswerString: String
}
