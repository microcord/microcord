package com.discord.widgets.chat.input.models

import com.discord.widgets.chat.input.autocomplete.LeadingIdentifier

/**
 * The word under the caret, interpreted as an autocomplete query.
 *
 * [token] is the raw text including the leading character; [formattedToken] is the same text with
 * the leading character removed, which is what actually gets matched. [startIndex] is where the
 * token begins in the composer, so a picked candidate can be spliced back in.
 *
 * [isAtStart] exists because a slash command is only a command when it opens the message.
 */
data class MentionToken(
    val leadingIdentifier: LeadingIdentifier,
    val token: String,
    val isAtStart: Boolean,
    val startIndex: Int,
) {
    /**
     * Reference quirk preserved: for [LeadingIdentifier.NONE] the identifier is `null`, and the
     * reference stringifies it before stripping — so a token containing the literal text `null`
     * loses its first occurrence. Harmless in practice (command option values are matched on
     * [token] elsewhere) but it is observable, so it is reproduced rather than silently corrected.
     */
    val formattedToken: String =
        token.replaceFirst(leadingIdentifier.identifier.toString(), "")
}
