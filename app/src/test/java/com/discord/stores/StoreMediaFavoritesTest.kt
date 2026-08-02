package com.discord.stores

import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.stores.StoreMediaFavorites.Favorite
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.textprocessing.node.EmojiNode
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreMediaFavoritesTest {
    private val directory = Files.createTempDirectory("media-favorites-test").toFile()
    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()

    @Before
    fun setUp() = Persister.init(directory)

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun mutationsPublishSnapshotsPersistAndIgnoreDuplicates() {
        val store = StoreMediaFavorites(deck, dispatcher)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val observed = mutableListOf<Set<Favorite>>()
        store.observeFavorites().subscribe(observed::add)
        val favorite = Favorite.FavCustomEmoji("41")

        store.addFavorite(favorite)
        store.addFavorite(favorite)
        scheduler.triggerActions()

        assertEquals(listOf(emptySet(), setOf(favorite)), observed)
        assertEquals(setOf(favorite), StoreMediaFavorites(deck, dispatcher).getFavorites())

        store.removeFavorite(favorite)
        store.removeFavorite(favorite)
        scheduler.triggerActions()

        assertEquals(listOf(emptySet(), setOf(favorite), emptySet()), observed)
    }

    @Test
    fun typeFilteringKeepsSnapshotOrderAndModelConstructorsUseUniqueIds() {
        val store = StoreMediaFavorites(deck, dispatcher)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val custom = Favorite.FavCustomEmoji(EmojiNode.EmojiIdAndType.Custom(41, false, "blob"))
        val unicode = Favorite.FavUnicodeEmoji(ModelEmojiUnicode(listOf("wave"), "wave-surrogate"))

        store.addFavorite(custom)
        store.addFavorite(unicode)
        scheduler.triggerActions()

        assertEquals("41", custom.emojiUniqueId)
        assertEquals("wave-surrogate", unicode.emojiUniqueId)
        assertEquals(setOf(custom), store.getFavorites(setOf(Favorite.FavCustomEmoji::class)))
        assertEquals(setOf(unicode), store.getFavorites(setOf(Favorite.FavUnicodeEmoji::class)))
        assertEquals(
            store.getFavorites().toList(),
            store.getFavorites(setOf(Favorite.FavUnicodeEmoji::class, Favorite.FavCustomEmoji::class)).toList(),
        )
    }

    @Test
    fun gifFavoritesUseDecodedTenorIdentityPersistAndKeepNewestFirst() {
        val store = StoreMediaFavorites(deck, dispatcher)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val encoded = ModelGif("https://img/1.gif", "https%3A%2F%2Ftenor.com%2F1", 100, 50)
        val sameGif = encoded.copy(tenorGifUrl = "https://tenor.com/1")
        val newer = ModelGif("https://img/2.gif", "https://tenor.com/2", 200, 100)

        store.setFavoriteGif(encoded, true)
        store.setFavoriteGif(sameGif, true)
        store.setFavoriteGif(newer, true)
        scheduler.triggerActions()

        assertEquals(
            listOf("https://tenor.com/2", "https://tenor.com/1"),
            store.getFavoriteGifs().map(Favorite.FavGif::tenorGifUrl),
        )
        assertEquals(
            store.getFavoriteGifs(),
            StoreMediaFavorites(deck, dispatcher).getFavoriteGifs(),
        )
    }
}
