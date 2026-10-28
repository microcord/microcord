package com.discord.widgets.chat.input.emoji

import com.discord.api.emoji.GuildEmoji
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.EmojiCategory
import com.discord.models.domain.emoji.EmojiSet
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.guild.Guild
import com.discord.widgets.chat.input.emoji.WidgetEmojiAdapter.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiPickerViewModelTest {

    private val unicode: Map<EmojiCategory, List<Emoji>> = mapOf(
        EmojiCategory.PEOPLE to listOf(unicodeEmoji("grinning"), unicodeEmoji("smile")),
        EmojiCategory.NATURE to listOf(unicodeEmoji("dog")),
    )

    private val guilds: Map<Long, Guild> = mapOf(
        20L to guild(20L, "Zebra", listOf("zed")),
        10L to guild(10L, "Apple", listOf("kek", "pog")),
        30L to guild(30L, "Empty", emptyList()),
    )

    @Test
    fun guildsComeFirstSortedByNameAndGuildsWithNoEmojiAreOmitted() {
        val items = EmojiPickerViewModel.buildItems(unicode, guilds, true, EmojiPickerContextType.Chat)

        val headers = items.filterIsInstance<Item.HeaderItem>()
        assertEquals(
            listOf("Apple", "Zebra"),
            headers.filterIsInstance<Item.HeaderItem.GuildHeaderItem>().map { it.guild.name },
        )
        assertEquals(
            listOf(EmojiCategory.PEOPLE, EmojiCategory.NATURE),
            headers.filterIsInstance<Item.HeaderItem.StandardHeaderItem>().map { it.emojiCategory },
        )
        // Every guild header precedes every Unicode header.
        assertTrue(
            items.indexOfLast { it is Item.HeaderItem.GuildHeaderItem } <
                items.indexOfFirst { it is Item.HeaderItem.StandardHeaderItem },
        )
    }

    @Test
    fun guildContextExcludesOtherGuildsAndAllUnicode() {
        val items = EmojiPickerViewModel.buildItems(
            unicode, guilds, true, EmojiPickerContextType.Guild(10L),
        )

        assertEquals(
            listOf("Apple"),
            items.filterIsInstance<Item.HeaderItem.GuildHeaderItem>().map { it.guild.name },
        )
        assertTrue(items.none { it is Item.HeaderItem.StandardHeaderItem })
        assertEquals(2, items.count { it is Item.EmojiItem })
    }

    @Test
    fun categoryRangesSpanTheirHeaderThroughTheLastEmojiBeforeTheNextHeader() {
        val items = EmojiPickerViewModel.buildItems(unicode, guilds, true, EmojiPickerContextType.Chat)
        val categories = EmojiPickerViewModel.categoriesFor(items, selectedId = null)

        // Apple: header at 0 plus two emoji; Zebra: header at 3 plus one.
        assertEquals(0..2, categories[0].categoryRange)
        assertEquals(3..4, categories[1].categoryRange)
        // Every position in the flat list is owned by exactly one category.
        items.indices.forEach { position ->
            assertEquals(1, categories.count { position in it.categoryRange })
        }
    }

    @Test
    fun theFirstCategoryIsSelectedWhenNothingHasBeenChosen() {
        val items = EmojiPickerViewModel.buildItems(unicode, guilds, true, EmojiPickerContextType.Chat)
        val categories = EmojiPickerViewModel.categoriesFor(items, selectedId = null)

        assertEquals(listOf(true, false, false, false), categories.map { it.isSelected })
    }

    @Test
    fun theUnicodeShortcutTargetsTheFirstUnicodeCategoryOnlyWhenCustomEmojiComeFirst() {
        val withGuilds = EmojiPickerViewModel.ViewState.Results(
            items = EmojiPickerViewModel.buildItems(unicode, guilds, true, EmojiPickerContextType.Chat),
            categories = EmojiPickerViewModel.categoriesFor(
                EmojiPickerViewModel.buildItems(unicode, guilds, true, EmojiPickerContextType.Chat),
                null,
            ),
            isSearching = false,
        )
        assertEquals(
            EmojiCategory.PEOPLE,
            (withGuilds.firstUnicodeCategoryItem as EmojiCategoryItem.StandardItem).emojiCategory,
        )

        val unicodeOnlyItems = EmojiPickerViewModel.buildItems(
            unicode, emptyMap(), true, EmojiPickerContextType.Chat,
        )
        val unicodeOnly = EmojiPickerViewModel.ViewState.Results(
            items = unicodeOnlyItems,
            categories = EmojiPickerViewModel.categoriesFor(unicodeOnlyItems, null),
            isSearching = false,
        )
        assertNull(unicodeOnly.firstUnicodeCategoryItem)
    }

    @Test
    fun searchDropsHeadersAndMatchesAnyNameCaseInsensitively() {
        val items = EmojiPickerViewModel.buildItems(unicode, guilds, true, EmojiPickerContextType.Chat)

        val results = EmojiPickerViewModel.filter(items, "GRIN")
        assertEquals(listOf("grinning"), results.map { (it as Item.EmojiItem).emojiName })
        assertTrue(results.none { it is Item.HeaderItem })

        assertTrue(EmojiPickerViewModel.filter(items, "nothing-matches-this").isEmpty())
    }

    @Test
    fun favoritesAndFrecentsPrecedeGuildAndUnicodeSections() {
        val favorite = unicodeEmoji("favorite")
        val recent = unicodeEmoji("recent")
        val set = EmojiSet(
            unicodeEmojis = unicode,
            customEmojis = emptyMap(),
            emojiIndex = mapOf(favorite.getUniqueId() to favorite, recent.getUniqueId() to recent),
            recentEmojis = listOf(recent),
            favoriteEmoji = setOf(favorite),
        )

        val headers = EmojiPickerViewModel.buildItems(
            set,
            emptyMap(),
            allowEmojisToAnimate = true,
            contextType = EmojiPickerContextType.Chat,
        ).filterIsInstance<Item.HeaderItem.StandardHeaderItem>()

        assertEquals(
            listOf(EmojiCategory.FAVORITE, EmojiCategory.RECENT, EmojiCategory.PEOPLE, EmojiCategory.NATURE),
            headers.map(Item.HeaderItem.StandardHeaderItem::emojiCategory),
        )
    }

    @Test
    fun pickerKeepsLockedCustomEmojiVisible() {
        val locked = ModelEmojiCustom(
            42L, "locked", emptyList(), true, false, 0, null,
            animated = true, usable = false, available = true, guildId = 10L,
        )
        val set = EmojiSet(emptyMap(), mapOf(10L to listOf(locked)), mapOf("42" to locked), emptyList(), emptySet())

        val items = EmojiPickerViewModel.buildItems(set, guilds, true, EmojiPickerContextType.Chat)
        assertTrue(items.filterIsInstance<Item.EmojiItem>().any { it.emoji == locked })
    }

    @Test
    fun genuineOnlyDropsLockedCustomEmojiForReactions() {
        val locked = ModelEmojiCustom(
            42L, "locked", emptyList(), true, false, 0, null,
            animated = false, usable = false, available = true, guildId = 10L,
        )
        val owned = ModelEmojiCustom(
            43L, "owned", emptyList(), true, false, 0, null,
            animated = false, usable = true, available = true, guildId = 10L,
        )
        val set = EmojiSet(emptyMap(), mapOf(10L to listOf(locked, owned)), emptyMap(), listOf(locked), emptySet())

        val emojis = EmojiPickerViewModel.buildItems(set, guilds, true, EmojiPickerContextType.Chat, genuineOnly = true)
            .filterIsInstance<Item.EmojiItem>().map { it.emoji }
        assertEquals(listOf<Emoji>(owned), emojis)
    }

    @Test
    fun favoritesAreNotRepeatedInRecent() {
        val favorite = unicodeEmoji("favorite")
        val set = EmojiSet(
            unicodeEmojis = mapOf(EmojiCategory.PEOPLE to listOf(favorite)),
            customEmojis = emptyMap(),
            emojiIndex = mapOf(favorite.getUniqueId() to favorite),
            recentEmojis = listOf(favorite),
            favoriteEmoji = setOf(favorite),
        )

        val items = EmojiPickerViewModel.buildItems(set, emptyMap(), true, EmojiPickerContextType.Chat)
        assertEquals(1, items.filterIsInstance<Item.EmojiItem>().count { it.emoji == favorite })
    }

    private fun unicodeEmoji(name: String) = ModelEmojiUnicode(names = listOf(name), surrogates = name)

    private fun guild(id: Long, name: String, emojiNames: List<String>) = Guild(
        id = id,
        name = name,
        emojis = emojiNames.mapIndexed { index, emojiName ->
            GuildEmoji(
                id = id * 100 + index,
                name = emojiName,
                roles = null,
                requireColons = true,
                managed = false,
                animated = false,
                available = true,
            )
        },
    )
}
