package com.discord.stores

import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.textprocessing.node.EmojiNode
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

        companion object {
            val AllTypes: Set<KClass<out Favorite>> by lazy {
                setOf(FavCustomEmoji::class, FavUnicodeEmoji::class)
            }
            val EmojiTypes: Set<KClass<out Favorite>> = setOf(FavCustomEmoji::class, FavUnicodeEmoji::class)
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
    }
}
