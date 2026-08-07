package com.discord.widgets.chat.input.autocomplete

import com.discord.models.domain.emoji.Emoji

data class EmojiAutocompletable(val emoji: Emoji, val animationsEnabled: Boolean) : Autocompletable() {
    private val textMatchers = emoji.names.map { ":$it:" }

    override fun getInputReplacement() = emoji.getChatInputText()
    override fun getInputTextMatchers() = textMatchers
    override fun getHiddenInputTextMatchers() = if (!emoji.requiresColons()) emoji.names else null
    override fun leadingIdentifier() =
        if (emoji.requiresColons()) LeadingIdentifier.EMOJI_AND_STICKERS else LeadingIdentifier.NONE
}
