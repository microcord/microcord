package com.discord.widgets.chat.input

/**
 * The characters that open an autocomplete session, and the word-splitting rule the chat input
 * uses to decide which word the caret is currently inside.
 *
 * Reference: `com.discord.widgets.chat.input.MentionUtilsKt`.
 */
const val MENTIONS_CHAR: Char = '@'
const val CHANNELS_CHAR: Char = '#'
const val EMOJIS_AND_STICKERS_CHAR: Char = ':'
const val SLASH_CHAR: Char = '/'

/**
 * Every character that can start an autocomplete token. Declared as a set so
 * [com.discord.widgets.chat.input.autocomplete.AutocompleteModelUtils] can test membership without
 * a chain of comparisons.
 */
val DEFAULT_LEADING_IDENTIFIERS: Set<Char> =
    hashSetOf(MENTIONS_CHAR, EMOJIS_AND_STICKERS_CHAR, CHANNELS_CHAR, SLASH_CHAR)

/**
 * Returns the whitespace-delimited word that [cursorPosition] sits inside, together with the index
 * that word starts at, or `null to -1` when there is no such word.
 *
 * Two rules are worth spelling out because they are what makes the autocomplete feel right:
 *
 * * A caret sitting at the very end of the text *immediately after whitespace* yields no token —
 *   the user has finished a word, so no popup should appear.
 * * The token is delimited by both `' '` and `'\n'`, taking whichever delimiter is nearest to the
 *   left of the caret. Everything else (punctuation, emoji) stays inside the token.
 *
 * The caret is exclusive: the returned word ends at [cursorPosition], not at the end of the word.
 * Typing in the middle of `@foobar` therefore autocompletes on the prefix under the caret.
 */
fun getSelectedToken(content: CharSequence, cursorPosition: Int): Pair<String?, Int> {
    if (content.isEmpty() || cursorPosition < 0 || cursorPosition > content.length) return null to -1

    val atEnd = cursorPosition == content.length
    val precededByWhitespace = cursorPosition == 0 || content[cursorPosition - 1].isWhitespace()
    if (atEnd && precededByWhitespace) return null to -1

    val start = maxOf(
        content.reverseIndexOf(' ', cursorPosition),
        content.reverseIndexOf('\n', cursorPosition),
    ) + 1
    val token = content.subSequence(start, cursorPosition).toString()
    return if (token.isNotEmpty()) token to start else null to -1
}

/** Last index of [char] strictly before [endPosition], or -1. */
private fun CharSequence.reverseIndexOf(char: Char, endPosition: Int): Int {
    var index = endPosition - 1
    while (index >= 0) {
        if (this[index] == char) return index
        index--
    }
    return -1
}
