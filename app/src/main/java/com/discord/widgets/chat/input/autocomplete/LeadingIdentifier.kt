package com.discord.widgets.chat.input.autocomplete

import com.discord.widgets.chat.input.CHANNELS_CHAR
import com.discord.widgets.chat.input.EMOJIS_AND_STICKERS_CHAR
import com.discord.widgets.chat.input.MENTIONS_CHAR
import com.discord.widgets.chat.input.SLASH_CHAR

/**
 * The character an autocomplete token opens with, and therefore which bucket of
 * [Autocompletable]s that token can match.
 *
 * [NONE] is a real member, not an absence: a command *option value* is autocompleted without any
 * leading character at all, so the bucket has to be addressable.
 *
 * Declaration order matches the reference (`LeadingIdentifier`), which matters because the enum
 * is used as a `LinkedHashMap` key and the map's iteration order reaches the UI.
 */
enum class LeadingIdentifier(val identifier: Char?) {
    APP_COMMAND(SLASH_CHAR),
    EMOJI_AND_STICKERS(EMOJIS_AND_STICKERS_CHAR),
    CHANNELS(CHANNELS_CHAR),
    MENTION(MENTIONS_CHAR),
    NONE(null),
    ;

    companion object {
        @JvmStatic
        fun fromChar(identifier: Char?): LeadingIdentifier = when (identifier) {
            SLASH_CHAR -> APP_COMMAND
            EMOJIS_AND_STICKERS_CHAR -> EMOJI_AND_STICKERS
            CHANNELS_CHAR -> CHANNELS
            MENTIONS_CHAR -> MENTION
            else -> NONE
        }
    }
}
