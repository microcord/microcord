package com.discord.widgets.chat.input.autocomplete

import com.discord.models.domain.emoji.Emoji

class EmojiUpsellPlaceholder(
    val lockedTotal: Int,
    val lockedFirstThree: List<Emoji>,
    val allowEmojiToAnimate: Boolean,
) : Autocompletable() {
    override fun getInputReplacement() = ""
    override fun getInputTextMatchers(): List<String> = emptyList()

    companion object {
        const val EMOJI_PREVIEW_COUNT = 3
    }
}
