package com.discord.utilities.textprocessing

/**
 * Parser state carried through a message parse.
 *
 * [isInQuote] stops quotes nesting. [listLevel] and [allowHeaders] exist because the list rule
 * re-parses each item's content: the level picks the bullet glyph and indent, and headers are
 * suppressed inside an item, as the reference does.
 */
class MessageParseState private constructor(
    override val isInQuote: Boolean,
    override val listLevel: Int = 0,
    override val allowHeaders: Boolean = true,
) : Rules.BlockQuoteState<MessageParseState>, Rules.ListState<MessageParseState> {

    override fun newBlockQuoteState(isInQuote: Boolean): MessageParseState =
        MessageParseState(isInQuote, listLevel, allowHeaders)

    /** The state an item's content is parsed with: one level deeper, and no headers. */
    override fun newListItemState(): MessageParseState =
        MessageParseState(isInQuote, listLevel + 1, allowHeaders = false)

    companion object {
        @JvmStatic
        val initialState: MessageParseState = MessageParseState(false)

        /** The reference stops matching bullets once items are this deep. */
        const val MAX_LIST_NESTING_LEVEL = 10
    }
}
