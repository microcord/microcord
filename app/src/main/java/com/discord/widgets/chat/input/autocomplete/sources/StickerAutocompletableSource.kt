package com.discord.widgets.chat.input.autocomplete.sources

import com.discord.stores.StoreStream
import com.discord.stores.UserSettings
import com.discord.widgets.chat.input.AutocompleteStickerItem
import com.discord.widgets.chat.input.autocomplete.LeadingIdentifier
import com.discord.widgets.chat.input.models.MentionToken
import java.util.Locale

/** `:name:` sticker suggestions, shown in their own row beside the text candidates. */
object StickerAutocompletableSource {

    private const val MAX_RESULTS = 10

    fun findMatches(token: MentionToken?): List<AutocompleteStickerItem> {
        if (token == null || token.leadingIdentifier != LeadingIdentifier.EMOJI_AND_STICKERS) return emptyList()
        if (!UserSettings.includeStickersInAutocomplete.get()) return emptyList()
        val query = token.formattedToken.trim(':').lowercase(Locale.ROOT)
        if (query.isEmpty()) return emptyList()

        val stickers = StoreStream.getStickers().getStickers().values
        return stickers.asSequence()
            .filter { it.name.lowercase(Locale.ROOT).contains(query) }
            .sortedWith(compareBy({ !it.name.lowercase(Locale.ROOT).startsWith(query) }, { it.name.lowercase(Locale.ROOT) }))
            .take(MAX_RESULTS)
            .map { AutocompleteStickerItem(it) }
            .toList()
    }
}
