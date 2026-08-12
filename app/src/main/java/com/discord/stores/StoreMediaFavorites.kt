package com.discord.stores

import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.frecency.FrecencyTracker
import com.discord.utilities.media.ExpressionFrecencyTracker
import com.discord.utilities.textprocessing.node.EmojiNode
import discord_protos.discord_users.v1.FrecencyUserSettingsOuterClass.FrecencyUserSettings
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass
import rx.Observable

/** Favourite emoji and GIFs, and emoji frecency, as held in the account's frecency proto. */
class StoreMediaFavorites(
    private val observationDeck: ObservationDeck,
    private val dispatcher: Dispatcher,
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

    private val emojiFrecency = ExpressionFrecencyTracker()
    private val emojiReactionFrecency = ExpressionFrecencyTracker()
    private var emojiKeyToLocal: (String) -> String = { it }
    private var emojiKeyToServer: (String) -> String = { it }

    /** Serialised only when a type-2 write is already going out. */
    val beforeSend = object : UserSettingsProtoWriter.BeforeSend<FrecencyUserSettings.Builder>() {
        override fun writeInto(builder: FrecencyUserSettings.Builder) {
            builder.emojiFrecencyBuilder.clearEmojis()
                .putAllEmojis(emojiFrecency.toProtoEntries().mapValues { it.value.toProto() })
            builder.emojiReactionFrecencyBuilder.clearEmojis()
                .putAllEmojis(emojiReactionFrecency.toProtoEntries().mapValues { it.value.toProto() })
        }
    }

    fun installEmojiResolvers(fromServer: (String) -> String, toServer: (String) -> String) {
        emojiKeyToLocal = fromServer
        emojiKeyToServer = toServer
        dispatcher.schedule { markChanged() }
    }

    @JvmOverloads
    fun getFavorites(types: Set<KClass<out Favorite>> = Favorite.AllTypes): Set<Favorite> =
        favoritesOf(FrecencySettings.favoriteEmojis.get(), FrecencySettings.favoriteGifs.get())
            .filterTo(LinkedHashSet()) { it::class in types }

    @JvmOverloads
    fun observeFavorites(types: Set<KClass<out Favorite>> = Favorite.AllTypes): Observable<Set<Favorite>> =
        Observable.combineLatest(
            FrecencySettings.favoriteEmojis.observe(),
            FrecencySettings.favoriteGifs.observe(),
        ) { emojis, gifs ->
            favoritesOf(emojis, gifs).filterTo(LinkedHashSet()) { it::class in types } as Set<Favorite>
        }.distinctUntilChanged()

    fun observeFavoriteEmojiKeys(): Observable<Set<String>> = FrecencySettings.favoriteEmojis.observe()
        .map { keys -> keys.mapTo(LinkedHashSet(), emojiKeyToLocal) as Set<String> }
        .distinctUntilChanged()

    fun getFavoriteGifs(): List<Favorite.FavGif> =
        getFavorites(Favorite.GifTypes).filterIsInstance<Favorite.FavGif>().sortedByDescending { it.order }

    fun observeFavoriteGifs(): Observable<List<Favorite.FavGif>> =
        observeFavorites(Favorite.GifTypes).map {
            it.filterIsInstance<Favorite.FavGif>().sortedByDescending(Favorite.FavGif::order)
        }

    fun getEmojiFrecencyKeys(): List<String> = emojiFrecency.getSortedKeys().map(emojiKeyToLocal)

    fun observeEmojiFrecency(): Observable<List<String>> =
        observationDeck.connectRx(this, generator = ::getEmojiFrecencyKeys).distinctUntilChanged()

    /**
     * Emoji ordered by how often they are used to react.
     *
     * Falls back to the message-composition history while this one is empty. The reference does
     * the same thing once, as a stored migration that seeds the new field from the old; done at
     * read instead, an account that has never reacted still opens on something familiar without
     * this client writing invented history into the account's proto.
     */
    fun getReactionEmojiFrecencyKeys(): List<String> =
        emojiReactionFrecency.getSortedKeys().ifEmpty { emojiFrecency.getSortedKeys() }
            .map(emojiKeyToLocal)

    fun observeReactionEmojiFrecency(): Observable<List<String>> =
        observationDeck.connectRx(this, generator = ::getReactionEmojiFrecencyKeys).distinctUntilChanged()

    fun isFavoriteGif(gif: ModelGif): Boolean {
        val key = canonicalGifKey(gif.tenorGifUrl)
        return getFavoriteGifs().any { it.tenorGifUrl == key }
    }

    fun setFavoriteGif(gif: ModelGif, favorite: Boolean) {
        val key = canonicalGifKey(gif.tenorGifUrl)
        FrecencySettings.favoriteGifs.update { current ->
            val gifs = LinkedHashMap(current)
            if (favorite) {
                if (key in gifs) return@update current
                val order = gifs.values.maxOfOrNull { it.order }?.plus(1) ?: 1
                gifs[key] = favoriteGif(gif, order)
            } else if (gifs.remove(key) == null) {
                return@update current
            }
            gifs
        }
    }

    fun addFavorite(favorite: Favorite) = setFavorite(favorite, true)

    fun removeFavorite(favorite: Favorite) = setFavorite(favorite, false)

    private fun setFavorite(favorite: Favorite, enabled: Boolean) = when (favorite) {
        is Favorite.FavGif -> setFavoriteGif(favorite.toModelGif(), enabled)
        is Favorite.FavCustomEmoji -> setFavoriteEmoji(favorite.emojiUniqueId, enabled)
        is Favorite.FavUnicodeEmoji -> setFavoriteEmoji(favorite.emojiUniqueId, enabled)
    }

    /**
     * @param isReaction Reacting scores the emoji in both histories, as in the reference: a
     *   reaction is still a use of that emoji, it is just also a reaction.
     */
    @JvmOverloads
    fun onEmojiUsed(emojiKey: String, isReaction: Boolean = false) {
        val serverKey = emojiKeyToServer(emojiKey)
        emojiFrecency.track(serverKey)
        if (isReaction) emojiReactionFrecency.track(serverKey)
        beforeSend.markDirty()
        dispatcher.schedule { markChanged() }
    }

    @StoreThread
    fun handleFrecencySettings(settings: FrecencyUserSettings) {
        emojiFrecency.replace(settings.emojiFrecency.emojisMap.mapValues { it.value.toEntry() })
        emojiReactionFrecency.replace(
            settings.emojiReactionFrecency.emojisMap.mapValues { it.value.toEntry() },
        )
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        emojiFrecency.replace(emptyMap())
        emojiReactionFrecency.replace(emptyMap())
        markChanged()
    }

    private fun setFavoriteEmoji(localKey: String, favorite: Boolean) {
        val serverKey = emojiKeyToServer(localKey)
        FrecencySettings.favoriteEmojis.update { current ->
            when {
                favorite && serverKey !in current -> current + serverKey
                !favorite && serverKey in current -> current - serverKey
                else -> current
            }
        }
    }

    private fun favoritesOf(
        emojis: List<String>,
        gifs: Map<String, FrecencyUserSettings.FavoriteGIF>,
    ): LinkedHashSet<Favorite> {
        val result = LinkedHashSet<Favorite>()
        emojis.forEach { key ->
            val localKey = emojiKeyToLocal(key)
            result += if (key.all(Char::isDigit)) Favorite.FavCustomEmoji(localKey) else Favorite.FavUnicodeEmoji(localKey)
        }
        gifs.forEach { (tenorUrl, gif) ->
            result += Favorite.FavGif(canonicalGifKey(tenorUrl), gif.src, gif.width, gif.height, gif.order)
        }
        return result
    }

    companion object {
        fun canonicalGifKey(url: String): String = runCatching {
            URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        }.getOrDefault(url)

        fun FrecencyUserSettings.FrecencyItem.toEntry() =
            FrecencyTracker.Entry(totalUses, recentUsesList.toList(), frecency, score)

        fun FrecencyTracker.Entry.toProto(): FrecencyUserSettings.FrecencyItem =
            FrecencyUserSettings.FrecencyItem.newBuilder()
                .setTotalUses(totalUses)
                .addAllRecentUses(recentUses)
                .setFrecency(frecency)
                .setScore(score)
                .build()

        private fun favoriteGif(gif: ModelGif, order: Int): FrecencyUserSettings.FavoriteGIF {
            val path = gif.gifImageUrl.substringBefore('?')
            return FrecencyUserSettings.FavoriteGIF.newBuilder()
                .setFormat(
                    if (path.endsWith(".mp4", true) || path.endsWith(".webm", true)) {
                        FrecencyUserSettings.GIFType.GIF_TYPE_VIDEO
                    } else {
                        FrecencyUserSettings.GIFType.GIF_TYPE_IMAGE
                    },
                )
                .setSrc(gif.gifImageUrl)
                .setWidth(gif.width)
                .setHeight(gif.height)
                .setOrder(order)
                .build()
        }
    }
}
