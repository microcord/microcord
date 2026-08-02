package com.discord.stores

import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.textprocessing.node.EmojiNode
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass
import rx.Observable

class StoreMediaFavorites(
    private val observationDeck: ObservationDeck,
    private val dispatcher: Dispatcher,
    private val persister: Persister<Set<Favorite>> = Persister(FAVORITES_CACHE_KEY, emptySet()),
) : StoreV2() {

    sealed class Favorite {
        data class FavCustomEmoji(override val emojiUniqueId: String) : Favorite(), FavoriteEmoji {
            constructor(custom: EmojiNode.EmojiIdAndType.Custom) : this(custom.id.toString())
        }

        data class FavUnicodeEmoji(override val emojiUniqueId: String) : Favorite(), FavoriteEmoji {
            constructor(unicode: ModelEmojiUnicode) : this(unicode.getUniqueId())
        }

        data class FavGif(
            val tenorGifUrl: String,
            val gifImageUrl: String,
            val width: Int,
            val height: Int,
            val order: Int,
        ) : Favorite() {
            constructor(gif: ModelGif, order: Int) : this(
                canonicalGifKey(gif.tenorGifUrl),
                gif.gifImageUrl,
                gif.width,
                gif.height,
                order,
            )

            fun toModelGif() = ModelGif(gifImageUrl, tenorGifUrl, width, height)
        }

        companion object {
            val AllTypes: Set<KClass<out Favorite>> by lazy {
                setOf(FavCustomEmoji::class, FavUnicodeEmoji::class, FavGif::class)
            }
            val EmojiTypes: Set<KClass<out Favorite>> = setOf(FavCustomEmoji::class, FavUnicodeEmoji::class)
            val GifTypes: Set<KClass<out Favorite>> = setOf(FavGif::class)
        }
    }

    interface FavoriteEmoji {
        val emojiUniqueId: String
    }

    private var favoritesSnapshot: Set<Favorite> = persister.get()
    private val favorites = favoritesSnapshot.toMutableSet()

    @JvmOverloads
    fun getFavorites(types: Set<KClass<out Favorite>> = Favorite.AllTypes): Set<Favorite> =
        favoritesSnapshot.filterTo(LinkedHashSet()) { it::class in types }

    @JvmOverloads
    fun observeFavorites(types: Set<KClass<out Favorite>> = Favorite.AllTypes): Observable<Set<Favorite>> =
        observationDeck.connectRx(this) { getFavorites(types) }.distinctUntilChanged()

    fun getFavoriteGifs(): List<Favorite.FavGif> =
        getFavorites(Favorite.GifTypes).filterIsInstance<Favorite.FavGif>().sortedByDescending { it.order }

    fun observeFavoriteGifs(): Observable<List<Favorite.FavGif>> =
        observeFavorites(Favorite.GifTypes).map {
            it.filterIsInstance<Favorite.FavGif>().sortedByDescending(Favorite.FavGif::order)
        }

    fun isFavoriteGif(gif: ModelGif): Boolean {
        val key = canonicalGifKey(gif.tenorGifUrl)
        return getFavoriteGifs().any { it.tenorGifUrl == key }
    }

    fun setFavoriteGif(gif: ModelGif, favorite: Boolean) = dispatcher.schedule {
        val key = canonicalGifKey(gif.tenorGifUrl)
        val existing = favorites.filterIsInstance<Favorite.FavGif>().firstOrNull { it.tenorGifUrl == key }
        if (existing != null && !favorite) {
            favorites.remove(existing)
            markChanged()
        } else if (existing == null && favorite) {
            val order = favorites.filterIsInstance<Favorite.FavGif>().maxOfOrNull(Favorite.FavGif::order)?.plus(1) ?: 1
            favorites.add(Favorite.FavGif(gif, order))
            markChanged()
        }
    }

    fun addFavorite(favorite: Favorite) = dispatcher.schedule {
        if (favorites.add(favorite)) markChanged()
    }

    fun removeFavorite(favorite: Favorite) = dispatcher.schedule {
        if (favorites.remove(favorite)) markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        favoritesSnapshot = HashSet(favorites)
        persister.set(favoritesSnapshot, persist = true)
    }

    companion object {
        const val FAVORITES_CACHE_KEY = "STORE_FAVORITES"

        fun canonicalGifKey(url: String): String = runCatching {
            URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        }.getOrDefault(url)
    }
}
