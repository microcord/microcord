package com.discord.widgets.chat.input.autocomplete.sources

import com.discord.api.channel.Channel
import com.discord.models.domain.emoji.EmojiSet
import com.discord.stores.StoreUserSettings
import com.discord.utilities.rx.computationLatest
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.AutocompletableComparator
import com.discord.widgets.chat.input.autocomplete.EmojiAutocompletable
import com.discord.widgets.chat.input.autocomplete.LeadingIdentifier
import java.util.TreeSet
import rx.Observable

/**
 * `:emoji:` candidates for a channel.
 *
 * The reference reads its [EmojiSet] from `StoreEmoji`, which this project does not have yet. The
 * store is therefore injected as [emojiSetSource] rather than reached for statically: when
 * `StoreEmoji` lands, `ChatInputAutocompletables` passes
 * `storeEmoji.getEmojiSet(guildId, channelId, …)` and nothing else changes. Until then the default
 * yields an empty set, so emoji autocomplete offers nothing rather than crashing.
 *
 * Unavailable custom emoji (a guild that lost its boost level) are dropped; locked-but-available
 * ones are kept, because typing them is how the Nitro upsell is reached.
 */
class EmojiAutocompletableSource(
    private val userSettingsStore: StoreUserSettings,
    private val emojiSetSource: (guildId: Long, channelId: Long) -> Observable<EmojiSet> =
        { _, _ -> Observable.empty() },
) {

    fun observeEmojiAutocompletables(
        channel: Channel,
    ): Observable<Map<LeadingIdentifier, TreeSet<Autocompletable>>> =
        Observable.combineLatest(
            emojiSetSource(channel.guildId, channel.id).computationLatest(),
            userSettingsStore.observeIsAnimatedEmojisEnabled(true).computationLatest(),
        ) { emojiSet, animationsEnabled -> createFromEmojiSet(emojiSet, animationsEnabled) }
            .computationLatest()
            .distinctUntilChanged()

    private fun createFromEmojiSet(
        emojiSet: EmojiSet,
        animationsEnabled: Boolean,
    ): Map<LeadingIdentifier, TreeSet<Autocompletable>> {
        val result = TreeSet<Autocompletable>(AutocompletableComparator())
        for (categoryEmojis in emojiSet.unicodeEmojis.values) {
            for (emoji in categoryEmojis) {
                result.add(EmojiAutocompletable(emoji, animationsEnabled))
            }
        }
        for (guildEmojis in emojiSet.customEmojis.values) {
            for (emoji in guildEmojis) {
                if (!emoji.isAvailable()) continue
                result.add(EmojiAutocompletable(emoji, animationsEnabled))
            }
        }
        return mapOf(LeadingIdentifier.EMOJI_AND_STICKERS to result)
    }
}
