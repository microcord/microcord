package com.discord.stores

import com.discord.models.domain.FrecencyUserSettings
import com.discord.models.domain.FrecencyUserSettingsCodec
import com.discord.models.domain.UserSettingsProtoEnvelope
import com.discord.models.domain.UserSettingsProtoPatch
import com.discord.models.domain.UserSettingsProtoUpdate
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.decodeBase64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler
import java.nio.file.Files

class FrecencySettingsSyncTest {
    private val directory = Files.createTempDirectory("frecency-settings-test").toFile()
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
    fun sanitizedFixtureDecodesAndEncodesWithExactFieldNumbers() {
        val decoded = FrecencyUserSettingsCodec.decodeBase64(FIXTURE_BASE64)

        assertEquals(FrecencyUserSettings.Versions(11, 0, 42), decoded.versions)
        assertEquals(listOf("42", "wave"), decoded.favoriteEmojis?.emojis)
        assertEquals(
            FrecencyUserSettings.FrecencyItem(2, listOf(1000), 200, 100),
            decoded.emojiFrecency?.emojis?.get("wave"),
        )
        assertEquals(FIXTURE_BASE64, FrecencyUserSettingsCodec.encodeBase64(decoded))
    }

    @Test
    fun liveShapeFixtureDecodesOwnedFieldsAndSkipsNewerTopLevelFields() {
        assertEquals(listOf(10, 5, 8, 11, 24, 243, 76), LIVE_SHAPE_FIXTURE_BASE64.decodeBase64()!!.toByteArray()
            .take(7).map { it.toInt() and 0xff })
        val decoded = FrecencyUserSettingsCodec.decodeBase64(LIVE_SHAPE_FIXTURE_BASE64)

        assertEquals(FrecencyUserSettings.Versions(11, 0, 9843), decoded.versions)
        assertEquals(1, decoded.favoriteGifs?.gifs?.size)
        assertEquals(2, decoded.favoriteEmojis?.emojis?.size)
        assertEquals(1, decoded.emojiFrecency?.emojis?.size)
        assertEquals(listOf(1000L, 2000L), decoded.emojiFrecency?.emojis?.values?.single()?.recentUses)
    }

    @Test
    fun partialGatewayUpdatePreservesUnsentTopLevelFields() {
        val store = store()
        register(store)
        val gifs = FrecencyUserSettings.FavoriteGifs(
            mapOf("https://tenor/desktop" to FrecencyUserSettings.FavoriteGif(src = "https://img/desktop.gif")),
        )
        gateway(store, FrecencyUserSettings(favoriteGifs = gifs), partial = false)
        gateway(
            store,
            FrecencyUserSettings(favoriteEmojis = FrecencyUserSettings.FavoriteEmojis(listOf("42"))),
            partial = true,
        )

        assertEquals(listOf("https://tenor/desktop"), store.getFavoriteGifs().map { it.tenorGifUrl })
        assertEquals(setOf("42"), store.getFavorites(StoreMediaFavorites.Favorite.EmojiTypes)
            .map { (it as StoreMediaFavorites.FavoriteEmoji).emojiUniqueId }.toSet())
    }

    @Test
    fun remoteDesktopFavoritesAppearWithUnicodeIdentityResolution() {
        val store = store()
        register(store)
        store.installEmojiResolvers(fromServer = { if (it == "wave") "wave-surrogate" else it }, toServer = { it })
        gateway(
            store,
            FrecencyUserSettings(
                favoriteGifs = FrecencyUserSettings.FavoriteGifs(
                    mapOf("https://tenor/desktop" to FrecencyUserSettings.FavoriteGif(
                        src = "https://img/desktop.gif",
                        width = 100,
                        height = 50,
                        order = 7,
                    )),
                ),
                favoriteEmojis = FrecencyUserSettings.FavoriteEmojis(listOf("42", "wave")),
            ),
            partial = false,
        )

        assertEquals(listOf("https://tenor/desktop"), store.getFavoriteGifs().map { it.tenorGifUrl })
        assertEquals(
            setOf("42", "wave-surrogate"),
            store.getFavorites(StoreMediaFavorites.Favorite.EmojiTypes)
                .map { (it as StoreMediaFavorites.FavoriteEmoji).emojiUniqueId }.toSet(),
        )
    }

    @Test
    fun gatewayUpdatePublishesThroughObservationDeck() {
        val store = store()
        register(store)
        val counts = mutableListOf<Int>()
        val subscription = store.observeFavoriteEmojiKeys().map(Set<String>::size).subscribe(counts::add)

        gateway(
            store,
            FrecencyUserSettings(favoriteEmojis = FrecencyUserSettings.FavoriteEmojis(listOf("42", "wave"))),
            partial = false,
        )

        assertEquals(listOf(0, 2), counts)
        subscription.unsubscribe()
    }

    @Test
    fun rnApiPatchUsesVersionGuardAndSettingsOnlyBody() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"settings\":\"$FIXTURE_BASE64\",\"out_of_date\":false}"))
        val api = RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java)

        api.patchFrecencyUserSettings(UserSettingsProtoPatch(FIXTURE_BASE64, 42)).toBlocking().single()

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v9/users/@me/settings-proto/2", request.path)
        assertEquals(
            "{\"settings\":\"$FIXTURE_BASE64\",\"required_data_version\":42}",
            request.body.readUtf8(),
        )
        server.shutdown()
    }

    @Test
    fun migrationNeverReplacesNonEmptyServerFields() {
        val remote = FrecencyUserSettings(
            favoriteEmojis = FrecencyUserSettings.FavoriteEmojis(),
            favoriteGifs = FrecencyUserSettings.FavoriteGifs(),
            emojiFrecency = FrecencyUserSettings.EmojiFrecency(),
        )
        val local = FrecencyUserSettings(
            favoriteEmojis = FrecencyUserSettings.FavoriteEmojis(listOf("local")),
            favoriteGifs = FrecencyUserSettings.FavoriteGifs(
                mapOf("local" to FrecencyUserSettings.FavoriteGif(src = "local")),
            ),
            emojiFrecency = FrecencyUserSettings.EmojiFrecency(
                mapOf("local" to FrecencyUserSettings.FrecencyItem(totalUses = 1)),
            ),
        )

        val patch = StoreMediaFavorites.safeMigrationPatch(remote, local)

        assertNull(patch.favoriteEmojis)
        assertNull(patch.favoriteGifs)
        assertNull(patch.emojiFrecency)
    }

    @Test
    fun cachedServerSettingsRemainAvailableOffline() {
        Persister(StoreMediaFavorites.SETTINGS_CACHE_KEY, FrecencyUserSettings()).set(
            FrecencyUserSettings(
                favoriteEmojis = FrecencyUserSettings.FavoriteEmojis(listOf("42")),
                favoriteGifs = FrecencyUserSettings.FavoriteGifs(
                    mapOf("https://tenor/cached" to FrecencyUserSettings.FavoriteGif(src = "https://img/cached.gif")),
                ),
            ),
            persist = true,
        )

        val offline = store()

        assertEquals(listOf("https://tenor/cached"), offline.getFavoriteGifs().map { it.tenorGifUrl })
        assertEquals(1, offline.getFavorites(StoreMediaFavorites.Favorite.EmojiTypes).size)
    }

    private fun store() = StoreMediaFavorites(deck, dispatcher, rnApi = { null })

    private fun register(store: StoreMediaFavorites) {
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
    }

    private fun gateway(store: StoreMediaFavorites, settings: FrecencyUserSettings, partial: Boolean) {
        dispatcher.schedule {
            store.handleGatewayUpdate(
                UserSettingsProtoUpdate(
                    UserSettingsProtoEnvelope(FrecencyUserSettingsCodec.encodeBase64(settings), 2),
                    partial,
                ),
            )
        }
        scheduler.triggerActions()
    }

    private companion object {
        const val FIXTURE_BASE64 = "CgQICxgqKgoKAjQyCgR3YXZlMhUKEwoEd2F2ZRILCAISAugHGMgBIGQ="
        const val LIVE_SHAPE_FIXTURE_BASE64 =
            "CgUICxjzTBJNCkkKHWh0dHBzOi8vc2FmZS5pbnZhbGlkL2Zhdm9yaXRlEigIARIeaHR0cHM6Ly9zYWZlLmludmFsaWQvaW1hZ2UuZ2lmGHggUCgHEAEaACIAKgoKAjQyCgR3YXZlMhcKFQoEd2F2ZRINCAISBOgH0A8YyAEgZDoAQgBKAFoAYgBqAA=="
    }
}
