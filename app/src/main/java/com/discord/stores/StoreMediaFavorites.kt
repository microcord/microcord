package com.discord.stores

import com.discord.app.AppLog
import com.discord.models.domain.FrecencyUserSettings
import com.discord.models.domain.FrecencyUserSettingsCodec
import com.discord.models.domain.UserSettingsProtoPatch
import com.discord.models.domain.UserSettingsProtoUpdate
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.media.ExpressionFrecencyTracker
import com.discord.utilities.persister.Persister
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.textprocessing.node.EmojiNode
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import retrofit2.HttpException
import rx.Observable
import rx.Subscription

class StoreMediaFavorites(
    private val observationDeck: ObservationDeck,
    private val dispatcher: Dispatcher,
    private val legacyFavoritesCache: Persister<Set<Favorite>> = Persister(FAVORITES_CACHE_KEY, emptySet()),
    private val settingsCache: Persister<FrecencyUserSettings> =
        Persister(SETTINGS_CACHE_KEY, FrecencyUserSettings()),
    private val pendingCache: Persister<PendingPatch> = Persister(PENDING_CACHE_KEY, PendingPatch()),
    private val migrationCache: Persister<Boolean> = Persister(MIGRATION_CACHE_KEY, false),
    private val legacyEmojiFrecencyCache: Persister<ExpressionFrecencyTracker> =
        Persister(EMOJI_FRECENCY_CACHE_KEY, ExpressionFrecencyTracker()),
    private val rnApi: () -> RestAPIInterface? = RestAPI::reactNativeApiOrNull,
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

    data class PendingPatch(
        val settings: FrecencyUserSettings = FrecencyUserSettings(),
        val requiredDataVersion: Int? = null,
        val migration: Boolean = false,
        val active: Boolean = false,
    )

    private var settings = settingsCache.get()
    private var pending = pendingCache.get()
    private var migrationComplete = migrationCache.get()
    private var legacyEmojiEntries = legacyEmojiFrecencyCache.get().toProtoEntries()
    private var emojiFrecency = ExpressionFrecencyTracker()
    private var favoritesSnapshot: Set<Favorite> = emptySet()
    private var loading = false
    private var patching = false
    private var retry: Subscription? = null
    private var emojiKeyToLocal: (String) -> String = { it }
    private var emojiKeyToServer: (String) -> String = { it }

    init {
        if (settings.favoriteGifs == null && settings.favoriteEmojis == null && settings.emojiFrecency == null) {
            settings = legacyFallbackSettings()
        }
        emojiFrecency.replace(settings.emojiFrecency?.emojis ?: legacyEmojiEntries)
        rebuildFavorites()
    }

    fun installEmojiResolvers(fromServer: (String) -> String, toServer: (String) -> String) {
        emojiKeyToLocal = fromServer
        emojiKeyToServer = toServer
        rebuildFavorites()
    }

    @JvmOverloads
    fun getFavorites(types: Set<KClass<out Favorite>> = Favorite.AllTypes): Set<Favorite> =
        favoritesSnapshot.filterTo(LinkedHashSet()) { it::class in types }

    @JvmOverloads
    fun observeFavorites(types: Set<KClass<out Favorite>> = Favorite.AllTypes): Observable<Set<Favorite>> =
        observationDeck.connectRx(this) { getFavorites(types) }.distinctUntilChanged()

    fun observeFavoriteEmojiKeys(): Observable<Set<String>> = observationDeck.connectRx(this) {
        val keys: Set<String> = settings.favoriteEmojis?.emojis.orEmpty().mapTo(LinkedHashSet(), emojiKeyToLocal)
        keys
    }.distinctUntilChanged()

    fun getFavoriteGifs(): List<Favorite.FavGif> =
        getFavorites(Favorite.GifTypes).filterIsInstance<Favorite.FavGif>().sortedByDescending { it.order }

    fun observeFavoriteGifs(): Observable<List<Favorite.FavGif>> =
        observeFavorites(Favorite.GifTypes).map {
            it.filterIsInstance<Favorite.FavGif>().sortedByDescending(Favorite.FavGif::order)
        }

    fun observeEmojiFrecency(): Observable<Map<String, FrecencyUserSettings.FrecencyItem>> =
        observationDeck.connectRx(this) { settings.emojiFrecency?.emojis.orEmpty() }.distinctUntilChanged()

    fun getEmojiFrecencyKeys(): List<String> = emojiFrecency.getSortedKeys().map(emojiKeyToLocal)

    fun isFavoriteGif(gif: ModelGif): Boolean {
        val key = canonicalGifKey(gif.tenorGifUrl)
        return getFavoriteGifs().any { it.tenorGifUrl == key }
    }

    fun setFavoriteGif(gif: ModelGif, favorite: Boolean) = dispatcher.schedule {
        val key = canonicalGifKey(gif.tenorGifUrl)
        val current = settings.favoriteGifs ?: FrecencyUserSettings.FavoriteGifs()
        val gifs = current.gifs.toMutableMap()
        if (favorite) {
            if (key in gifs) return@schedule
            val order = gifs.values.maxOfOrNull(FrecencyUserSettings.FavoriteGif::order)?.plus(1) ?: 1
            gifs[key] = FrecencyUserSettings.FavoriteGif(
                format = if (gif.gifImageUrl.substringBefore('?').endsWith(".mp4", true) ||
                    gif.gifImageUrl.substringBefore('?').endsWith(".webm", true)
                ) FrecencyUserSettings.GifType.VIDEO else FrecencyUserSettings.GifType.IMAGE,
                src = gif.gifImageUrl,
                width = gif.width,
                height = gif.height,
                order = order,
            )
        } else if (gifs.remove(key) == null) {
            return@schedule
        }
        updateAndQueue(FrecencyUserSettings(favoriteGifs = current.copy(gifs = gifs)))
    }

    fun addFavorite(favorite: Favorite) = dispatcher.schedule {
        when (favorite) {
            is Favorite.FavGif -> setFavoriteGif(favorite.toModelGif(), true)
            is Favorite.FavCustomEmoji -> setFavoriteEmoji(favorite.emojiUniqueId, true)
            is Favorite.FavUnicodeEmoji -> setFavoriteEmoji(favorite.emojiUniqueId, true)
        }
    }

    fun removeFavorite(favorite: Favorite) = dispatcher.schedule {
        when (favorite) {
            is Favorite.FavGif -> setFavoriteGif(favorite.toModelGif(), false)
            is Favorite.FavCustomEmoji -> setFavoriteEmoji(favorite.emojiUniqueId, false)
            is Favorite.FavUnicodeEmoji -> setFavoriteEmoji(favorite.emojiUniqueId, false)
        }
    }

    @StoreThread
    private fun setFavoriteEmoji(localKey: String, favorite: Boolean) {
        val serverKey = emojiKeyToServer(localKey)
        val emojis = settings.favoriteEmojis?.emojis.orEmpty().toMutableList()
        if (favorite) {
            if (serverKey in emojis) return
            emojis += serverKey
        } else if (!emojis.remove(serverKey)) {
            return
        }
        updateAndQueue(FrecencyUserSettings(favoriteEmojis = FrecencyUserSettings.FavoriteEmojis(emojis)))
    }

    fun onEmojiUsed(emojiKey: String) = dispatcher.schedule {
        emojiFrecency.track(emojiKeyToServer(emojiKey))
        val update = FrecencyUserSettings(
            emojiFrecency = FrecencyUserSettings.EmojiFrecency(emojiFrecency.toProtoEntries()),
        )
        updateAndQueue(update, delaySeconds = FRECENCY_WRITE_DELAY_SECONDS)
    }

    fun handleConnectionReady(ready: Boolean) {
        if (ready) refresh()
    }

    @StoreThread
    fun handleGatewayUpdate(update: UserSettingsProtoUpdate) {
        if (update.settings.type != FRECENCY_SETTINGS_TYPE) return
        val decoded = decode(update.settings.proto, "gateway") ?: return
        applyRemote(if (update.partial) settings.mergePartial(decoded) else decoded)
    }

    @StoreThread
    fun handlePreLogout() {
        retry?.unsubscribe()
        retry = null
        loading = false
        patching = false
        settings = FrecencyUserSettings()
        pending = PendingPatch()
        migrationComplete = false
        legacyFavoritesCache.clear(persist = true)
        legacyEmojiFrecencyCache.clear(persist = true)
        legacyEmojiEntries = emptyMap()
        emojiFrecency = ExpressionFrecencyTracker()
        rebuildFavorites()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        settingsCache.set(settings, persist = true)
        pendingCache.set(pending, persist = true)
        migrationCache.set(migrationComplete, persist = true)
        legacyEmojiFrecencyCache.set(emojiFrecency, persist = true)
    }

    private fun refresh() {
        if (loading) return
        val api = rnApi() ?: return
        loading = true
        api.getFrecencyUserSettings().restSubscribeOn().subscribe(
            { response -> dispatcher.schedule {
                loading = false
                val remote = decode(response.settings, "GET") ?: return@schedule
                applyRemote(remote, overlayPending = false)
                migrateOrFlush()
            } },
            { dispatcher.schedule { loading = false } },
        )
    }

    @StoreThread
    private fun migrateOrFlush() {
        if (!migrationComplete) {
            val migration = migrationPatch(settings)
            if (migration != FrecencyUserSettings()) {
                queue(migration, settings.versions?.dataVersion, migration = true)
            } else {
                migrationComplete = true
                markChanged()
            }
        }
        if (pending.active) flushPending()
    }

    @StoreThread
    private fun updateAndQueue(update: FrecencyUserSettings, delaySeconds: Long = 0) {
        settings = settings.mergePartial(update)
        settings.emojiFrecency?.emojis?.let(emojiFrecency::replace)
        rebuildFavorites()
        queue(update, pending.requiredDataVersion ?: settings.versions?.dataVersion, delaySeconds = delaySeconds)
        markChanged()
    }

    @StoreThread
    private fun queue(
        update: FrecencyUserSettings,
        requiredDataVersion: Int?,
        migration: Boolean = false,
        delaySeconds: Long = 0,
    ) {
        pending = PendingPatch(
            settings = if (pending.active) pending.settings.mergePartial(update) else update,
            requiredDataVersion = pending.requiredDataVersion ?: requiredDataVersion,
            migration = pending.migration || migration,
            active = true,
        )
        if (delaySeconds == 0L) {
            flushPending()
        } else if (retry == null || retry?.isUnsubscribed == true) {
            retry = Observable.timer(delaySeconds, TimeUnit.SECONDS).subscribe {
                dispatcher.schedule { retry = null; flushPending() }
            }
        }
    }

    @StoreThread
    private fun flushPending() {
        if (patching || !pending.active) return
        val api = rnApi() ?: return
        val sent = pending
        patching = true
        api.patchFrecencyUserSettings(
            UserSettingsProtoPatch(
                settings = FrecencyUserSettingsCodec.encodeBase64(sent.settings),
                requiredDataVersion = sent.requiredDataVersion,
            ),
        ).restSubscribeOn(retry = false).subscribe(
            { response -> dispatcher.schedule {
                patching = false
                val remote = decode(response.settings, "PATCH") ?: return@schedule
                if (pending == sent) pending = PendingPatch()
                if (sent.migration) migrationComplete = true
                applyRemote(remote)
                if (pending.active) {
                    pending = pending.copy(requiredDataVersion = remote.versions?.dataVersion)
                    flushPending()
                }
            } },
            { failure -> dispatcher.schedule { handlePatchFailure(sent, failure) } },
        )
    }

    @StoreThread
    private fun handlePatchFailure(sent: PendingPatch, failure: Throwable) {
        patching = false
        val http = failure as? HttpException
        if (http?.code() == 429) {
            val seconds = http.response()?.headers()?.get("Retry-After")?.toDoubleOrNull() ?: 60.0
            retry?.unsubscribe()
            retry = Observable.timer((seconds * 1000).toLong().coerceAtMost(MAX_RATE_LIMIT_DELAY_MS), TimeUnit.MILLISECONDS)
                .subscribe { dispatcher.schedule { retry = null; flushPending() } }
            return
        }
        if (http?.code() == 400 && invalidSettingsCode(http) == INVALID_USER_SETTINGS_DATA) {
            if (pending == sent) pending = PendingPatch()
            refresh()
        }
        markChanged()
    }

    @StoreThread
    private fun applyRemote(remote: FrecencyUserSettings, overlayPending: Boolean = true) {
        settings = if (overlayPending && pending.active) remote.mergePartial(pending.settings) else remote
        remote.emojiFrecency?.emojis?.let(emojiFrecency::replace)
        if (overlayPending) pending.settings.emojiFrecency?.emojis?.let(emojiFrecency::replace)
        rebuildFavorites()
        markChanged()
    }

    private fun rebuildFavorites() {
        val result = LinkedHashSet<Favorite>()
        settings.favoriteEmojis?.emojis.orEmpty().forEach { key ->
            val localKey = emojiKeyToLocal(key)
            result += if (key.all(Char::isDigit)) Favorite.FavCustomEmoji(localKey) else Favorite.FavUnicodeEmoji(localKey)
        }
        settings.favoriteGifs?.gifs.orEmpty().forEach { (tenorUrl, gif) ->
            result += Favorite.FavGif(canonicalGifKey(tenorUrl), gif.src, gif.width, gif.height, gif.order)
        }
        favoritesSnapshot = result
    }

    private fun legacyFallbackSettings(): FrecencyUserSettings {
        val favorites = legacyFavoritesCache.get()
        val gifs = favorites.filterIsInstance<Favorite.FavGif>().associate { gif ->
            gif.tenorGifUrl to gif.toProto()
        }
        val emojis = favorites.filterIsInstance<FavoriteEmoji>().map(FavoriteEmoji::emojiUniqueId)
        return FrecencyUserSettings(
            favoriteGifs = FrecencyUserSettings.FavoriteGifs(gifs),
            favoriteEmojis = FrecencyUserSettings.FavoriteEmojis(emojis),
            emojiFrecency = FrecencyUserSettings.EmojiFrecency(legacyEmojiEntries),
        )
    }

    private fun migrationPatch(remote: FrecencyUserSettings): FrecencyUserSettings {
        val local = legacyFallbackSettings()
        return safeMigrationPatch(remote, local, emojiKeyToServer)
    }

    private fun Favorite.FavGif.toProto() = FrecencyUserSettings.FavoriteGif(
        format = if (gifImageUrl.substringBefore('?').endsWith(".mp4", true) ||
            gifImageUrl.substringBefore('?').endsWith(".webm", true)
        ) FrecencyUserSettings.GifType.VIDEO else FrecencyUserSettings.GifType.IMAGE,
        src = gifImageUrl,
        width = width,
        height = height,
        order = order,
    )

    private fun decode(encoded: String, source: String): FrecencyUserSettings? =
        runCatching { FrecencyUserSettingsCodec.decodeBase64(encoded) }
            .onSuccess { decoded ->
                AppLog.i(
                    "SettingsProtoV2",
                    "$source decoded fields=2:${decoded.favoriteGifs != null},5:${decoded.favoriteEmojis != null}," +
                        "6:${decoded.emojiFrecency != null} counts=" +
                        "${decoded.favoriteGifs?.gifs?.size ?: 0}/" +
                        "${decoded.favoriteEmojis?.emojis?.size ?: 0}/" +
                        "${decoded.emojiFrecency?.emojis?.size ?: 0} versions=" +
                        "${decoded.versions?.clientVersion ?: 0}/" +
                        "${decoded.versions?.serverVersion ?: 0}/" +
                        "${decoded.versions?.dataVersion ?: 0}",
                )
            }
            .onFailure { AppLog.e("SettingsProtoV2", "$source decode failed", it) }
            .getOrNull()

    // Peeked, not consumed: an error this does not recognise still has to classify downstream.
    private fun invalidSettingsCode(error: HttpException): Int? = runCatching {
        JsonParser.parseString(error.response()?.errorBody()?.source()?.peek()?.readUtf8())
            .asJsonObject["code"].asInt
    }.getOrNull()

    companion object {
        const val FAVORITES_CACHE_KEY = "STORE_FAVORITES"
        const val SETTINGS_CACHE_KEY = "FRECENCY_USER_SETTINGS_V2"
        const val PENDING_CACHE_KEY = "FRECENCY_USER_SETTINGS_PENDING_V2"
        const val MIGRATION_CACHE_KEY = "FRECENCY_USER_SETTINGS_MIGRATED_V2"
        const val EMOJI_FRECENCY_CACHE_KEY = "EMOJI_FRECENTS_V1"
        const val FRECENCY_SETTINGS_TYPE = 2
        const val INVALID_USER_SETTINGS_DATA = 50105
        const val FRECENCY_WRITE_DELAY_SECONDS = 30L
        const val MAX_RATE_LIMIT_DELAY_MS = 30_000L

        fun canonicalGifKey(url: String): String = runCatching {
            URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        }.getOrDefault(url)

        internal fun safeMigrationPatch(
            remote: FrecencyUserSettings,
            local: FrecencyUserSettings,
            emojiKeyToServer: (String) -> String = { it },
        ) = FrecencyUserSettings(
            favoriteGifs = local.favoriteGifs?.takeIf {
                it.gifs.isNotEmpty() && remote.favoriteGifs == null
            },
            favoriteEmojis = local.favoriteEmojis?.takeIf {
                it.emojis.isNotEmpty() && remote.favoriteEmojis == null
            }?.let { it.copy(emojis = it.emojis.map(emojiKeyToServer)) },
            emojiFrecency = local.emojiFrecency?.takeIf {
                it.emojis.isNotEmpty() && remote.emojiFrecency == null
            },
        )
    }
}
