package com.discord.widgets.chat.input.emoji

import android.content.Context
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.EmojiSet
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import rx.Observable

/**
 * An [EmojiSet] assembled from the bundled catalog plus whatever guilds the user is in.
 *
 * This is what `StoreEmoji` would produce, minus the two things only a store can know: which emoji
 * the user has favourited and which they have used recently. It exists so that `:name:`
 * autocomplete works today — without it the emoji bucket is empty and typing `:sm` offers nothing.
 *
 * **Replace, do not extend.** When `StoreEmoji` lands, point
 * [com.discord.widgets.chat.input.autocomplete.ChatInputAutocompletables.emojiSetSource] at it and
 * delete this file; the differences are exactly the missing `recentEmojis`/`favoriteEmoji` lists
 * and the fact that this recomputes name disambiguation on every guild change instead of
 * maintaining it incrementally.
 */
object LocalEmojiSet {

    fun observe(context: Context, guildId: Long): Observable<EmojiSet> = Observable.combineLatest(
        EmojiCatalog.observe(context),
        StoreStream.getGuilds().observeGuilds(),
    ) { unicode, guilds -> build(unicode, guilds, guildId) }

    /**
     * [preferredGuildId] is the guild whose emoji win a name collision, because that is the guild
     * the user is typing in. Everyone else is suffixed `name-2`, `name-3`… exactly as
     * [ModelEmojiCustom.disambiguationIndex] describes, so that `:kek:` in this guild always means
     * this guild's `:kek:`.
     */
    private fun build(
        unicodeEmojis: Map<com.discord.models.domain.emoji.EmojiCategory, List<Emoji>>,
        guilds: Map<Long, Guild>,
        preferredGuildId: Long,
    ): EmojiSet {
        val customByGuild = LinkedHashMap<Long, List<Emoji>>()
        val index = LinkedHashMap<String, Emoji>()
        val usedNames = HashMap<String, Int>()

        val ordered = guilds.values.sortedWith(
            compareByDescending<Guild> { it.id == preferredGuildId }.thenBy { it.name.lowercase() },
        )

        for (guild in ordered) {
            val emojis = guild.emojis.filter { it.available != false }.map { guildEmoji ->
                val base = ModelEmojiCustom(guildEmoji, guild.id)
                val seen = usedNames[base.name] ?: 0
                usedNames[base.name] = seen + 1
                if (seen == 0) base else ModelEmojiCustom(base, seen, true)
            }
            if (emojis.isNotEmpty()) customByGuild[guild.id] = emojis
            emojis.forEach { index[it.firstName] = it }
        }

        // Custom emoji win a name collision with a Unicode one: `:shrug:` typed in a guild that
        // uploaded its own `shrug` means that upload. (`putIfAbsent` is API 24; minSdk is 21.)
        unicodeEmojis.values.forEach { emojis ->
            emojis.forEach { emoji ->
                emoji.names.forEach { name -> if (!index.containsKey(name)) index[name] = emoji }
            }
        }

        return EmojiSet(
            unicodeEmojis = unicodeEmojis,
            customEmojis = customByGuild,
            emojiIndex = index,
            recentEmojis = emptyList(),
            favoriteEmoji = emptySet(),
        )
    }
}
