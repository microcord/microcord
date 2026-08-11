package com.discord.stores

import android.content.SharedPreferences
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.stores.StoreMediaFavorites.Favorite
import com.discord.utilities.textprocessing.node.EmojiNode
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Favourites live in the frecency proto now, so these drive the real store graph rather than a
 * standalone store: the writes go through `FrecencySettings`, which resolves off [StoreStream].
 */
class StoreMediaFavoritesTest {
    private val store get() = StoreStream.getMediaFavorites()

    @Before
    fun reset() {
        store.getFavorites().forEach(store::removeFavorite)
        await { store.getFavorites().isEmpty() }
    }

    @Test
    fun mutationsPublishSnapshotsAndIgnoreDuplicates() {
        val observed = mutableListOf<Set<Favorite>>()
        val subscription = store.observeFavorites().subscribe(observed::add)
        val favorite = Favorite.FavCustomEmoji("41")

        store.addFavorite(favorite)
        store.addFavorite(favorite)
        await { store.getFavorites() == setOf<Favorite>(favorite) }

        store.removeFavorite(favorite)
        store.removeFavorite(favorite)
        await { store.getFavorites().isEmpty() }

        assertEquals(listOf(emptySet(), setOf(favorite), emptySet()), observed)
        subscription.unsubscribe()
    }

    @Test
    fun typeFilteringKeepsSnapshotOrderAndModelConstructorsUseUniqueIds() {
        val custom = Favorite.FavCustomEmoji(EmojiNode.EmojiIdAndType.Custom(41, false, "blob"))
        val unicode = Favorite.FavUnicodeEmoji(ModelEmojiUnicode(listOf("wave"), "wave-surrogate"))

        store.addFavorite(custom)
        store.addFavorite(unicode)
        await { store.getFavorites().size == 2 }

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
    fun gifFavoritesUseDecodedTenorIdentityAndKeepNewestFirst() {
        val encoded = ModelGif("https://img/1.gif", "https%3A%2F%2Ftenor.com%2F1", 100, 50)
        val sameGif = encoded.copy(tenorGifUrl = "https://tenor.com/1")
        val newer = ModelGif("https://img/2.gif", "https://tenor.com/2", 200, 100)

        store.setFavoriteGif(encoded, true)
        store.setFavoriteGif(sameGif, true)
        store.setFavoriteGif(newer, true)
        await { store.getFavoriteGifs().size == 2 }

        assertEquals(
            listOf("https://tenor.com/2", "https://tenor.com/1"),
            store.getFavoriteGifs().map(Favorite.FavGif::tenorGifUrl),
        )
    }

    private fun await(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("store never settled")
    }

    companion object {
        private val directory = Files.createTempDirectory("media-favorites-test")
        private val scheduler = storeTestScheduler("media-favorites-store-thread")
        private lateinit var stream: StoreStream

        @JvmStatic
        @BeforeClass
        fun initialize() {
            stream = StoreStream.initializeForTesting(
                directory.toFile(),
                memoryPreferences(),
                memoryPreferences(),
                scheduler,
            )
        }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            directory.toFile().deleteRecursively()
        }

        private fun memoryPreferences(): SharedPreferences {
            val values = linkedMapOf<String, Any?>()
            lateinit var editor: Any
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet" ->
                        editor.also { values[args[0] as String] = args[1] }
                    "remove" -> editor.also { values.remove(args[0] as String) }
                    "clear" -> editor.also { values.clear() }
                    "commit" -> true
                    "apply" -> null
                    else -> editor
                }
            }
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getAll" -> values.toMap()
                    "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" -> values[args[0]] ?: args[1]
                    "contains" -> values.containsKey(args[0])
                    "edit" -> editor
                    else -> null
                }
            } as SharedPreferences
        }
    }
}
