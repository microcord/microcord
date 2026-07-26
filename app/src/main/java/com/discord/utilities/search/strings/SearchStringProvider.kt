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

    val linkAnswerString: String
    val embedAnswerString: String
    val fileAnswerString: String
    val videoAnswerString: String
    val imageAnswerString: String
    val soundAnswerString: String
    val stickerAnswerString: String
}
