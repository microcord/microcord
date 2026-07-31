package com.discord.widgets.chat.input.models

import com.discord.api.sticker.Sticker
import com.discord.models.commands.ApplicationCommandOption
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.EmojiAutocompletable
import com.discord.widgets.chat.input.autocomplete.LeadingIdentifier

data class AutocompleteInputSelectionModel(
    val autocompleteToken: MentionToken?,
    val filteredAutocompletables: Map<LeadingIdentifier, List<Autocompletable>>,
    val inputSelectionModel: InputSelectionModel,
    val showErrorsForOptions: Set<ApplicationCommandOption>,
    val stickerMatches: List<Sticker>,
) {
    fun getAutocompleteType(): String = when (autocompleteToken?.leadingIdentifier) {
        LeadingIdentifier.APP_COMMAND -> "COMMANDS"
        LeadingIdentifier.EMOJI_AND_STICKERS -> "EMOJIS_AND_STICKERS"
        LeadingIdentifier.CHANNELS -> "CHANNELS"
        LeadingIdentifier.MENTION -> "MENTIONS"
        else -> "CHOICE"
    }

    fun getEmojiNumCount(): Int =
        filteredAutocompletables[LeadingIdentifier.EMOJI_AND_STICKERS].orEmpty().count { it is EmojiAutocompletable }

    fun getStickerNumCount(): Int = stickerMatches.size
}
