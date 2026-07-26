package com.discord.utilities.textprocessing

/**
 * Parser state carried through a message parse.
 *
 * The only thing message parsing has to remember is whether it is already inside a block quote,
 * because quotes do not nest.
 */
class MessageParseState private constructor(
    override val isInQuote: Boolean,
) : Rules.BlockQuoteState<MessageParseState> {

    override fun newBlockQuoteState(isInQuote: Boolean): MessageParseState = MessageParseState(isInQuote)

    companion object {
        @JvmStatic
        val initialState: MessageParseState = MessageParseState(false)
    }
}
