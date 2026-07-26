package com.discord.stores

import com.discord.api.sticker.GuildStickersUpdate
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerType
import com.discord.api.guildhash.GuildHashes
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelSku
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.models.sticker.dto.ModelStickerPackStoreListing
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.discord.widgets.stickers.StickerStore.StickerPackState
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class StickerStoresTest {

    private val directory = Files.createTempDirectory("sticker-stores-test").toFile()
    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()
    private var now = START_TIME_MS
    private val clock = Clock { now }

    @Before fun setUp() = Persister.init(directory)
    @After fun tearDown() { directory.deleteRecursively() }

    // region StoreStickers

    @Test fun aLoadedPackWithAStoreListingSurvivesTheDirectoryResponse() {
        val store = newStickerStore()
        register(store)
        val listing = storeListing()

        dispatcher.schedule {
            store.handleNewLoadedStickerPacks(listOf(pack(PACK_ID, storeListing = listing)))
            // The directory omits store listings; overwriting would lose the purchase state.
            store.handleNewEnabledStickerDirectory(listOf(pack(PACK_ID, storeListing = null)))
        }
        scheduler.triggerActions()

        val state = store.getStickerPack(PACK_ID) as StickerPackState.Loaded
        assertEquals(listing, state.stickerPack.storeListing)
        assertEquals(listOf(PACK_ID), store.getEnabledStickerPacks().map(ModelStickerPack::id))
    }

    @Test fun aPackWithoutAListingIsStillReplacedBySubsequentLoads() {
        val store = newStickerStore()
        register(store)

        dispatcher.schedule {
            store.handleNewLoadedStickerPacks(listOf(pack(PACK_ID, name = "old", storeListing = null)))
            store.handleNewLoadedStickerPacks(listOf(pack(PACK_ID, name = "new", storeListing = null)))
        }
        scheduler.triggerActions()

        assertEquals("new", (store.getStickerPack(PACK_ID) as StickerPackState.Loaded).stickerPack.name)
    }

    @Test fun packStatesProgressFromUnknownThroughLoadingToLoaded() {
        val store = newStickerStore()
        register(store)
        assertNull(store.getStickerPack(PACK_ID))

        dispatcher.schedule { store.handleNewLoadingStickerPacks(listOf(PACK_ID)) }
        scheduler.triggerActions()
        assertEquals(StickerPackState.Loading, store.getStickerPack(PACK_ID))

        dispatcher.schedule { store.handleNewLoadedStickerPacks(listOf(pack(PACK_ID))) }
        scheduler.triggerActions()
        assertTrue(store.getStickerPack(PACK_ID) is StickerPackState.Loaded)
    }

    @Test fun stickersAreFlattenedFromLoadedPacksAndEnabledPacksSeparately() {
        val store = newStickerStore()
        register(store)

        dispatcher.schedule {
            store.handleNewLoadedStickerPacks(listOf(pack(OTHER_PACK_ID, stickerIds = listOf(20L))))
            store.handleNewEnabledStickerDirectory(listOf(pack(PACK_ID, stickerIds = listOf(10L))))
        }
        scheduler.triggerActions()

        // Everything the client knows about...
        assertEquals(setOf(10L, 20L), store.getStickers().keys)
        // ...versus only what this account may actually send.
        assertEquals(listOf(10L), store.getEnabledStickers().map(Sticker::id))
    }

    @Test fun usingAStickerPromotesItAndDeletingItForgetsIt() {
        val store = newStickerStore()
        register(store)
        val observed = mutableListOf<List<Long>>()
        store.observeFrequentlyUsedStickerIds().subscribe { observed += it }

        store.onStickerUsed(sticker(10L))
        scheduler.triggerActions()
        assertEquals(listOf(10L), observed.last())

        store.handleDeletedStickers(listOf(10L))
        scheduler.triggerActions()
        assertEquals(emptyList<Long>(), observed.last())
    }

    @Test fun theEnabledDirectoryFetchIsThrottledToOneCallPerFiveMinutes() {
        var apiCalls = 0
        val store = newStickerStore(api = { apiCalls++; null })

        // Nothing has been fetched (last fetch = 0) but the five-minute window has not elapsed
        // since the epoch this clock reports, so the call is suppressed.
        now = FETCH_WINDOW_MS - 1
        store.fetchEnabledStickerDirectory()
        assertEquals(0, apiCalls)

        now = FETCH_WINDOW_MS + 1
        store.fetchEnabledStickerDirectory()
        assertEquals(1, apiCalls)
    }

    @Test fun handleFetchedStickerOnlyChasesStandardStickers() {
        var apiCalls = 0
        val store = newStickerStore(api = { apiCalls++; null })
        register(store)

        store.handleFetchedSticker(sticker(10L, type = StickerType.GUILD, guildId = GUILD_ID))
        store.handleFetchedSticker(null)
        scheduler.triggerActions()
        assertEquals(0, apiCalls)

        store.handleFetchedSticker(sticker(11L, type = StickerType.STANDARD, packId = PACK_ID))
        scheduler.triggerActions()
        assertEquals(1, apiCalls)
        // The pack is optimistically marked loading before the request goes out.
        assertEquals(StickerPackState.Loading, store.getStickerPack(PACK_ID))
    }

    // endregion

    // region StoreGuildStickers

    @Test fun readyPopulatesGuildStickersAndLookupSearchesEveryGuild() {
        val store = newGuildStickerStore()
        register(store)

        dispatcher.schedule { store.handleConnectionOpen(readyPayload()) }
        scheduler.triggerActions()

        assertEquals(setOf(100L, 101L), store.getStickersForGuild(GUILD_ID).keys)
        assertEquals(100L, store.getGuildSticker(100L)?.id)
        assertNull(store.getGuildSticker(999L))
        assertEquals(2, store.getAllGuildStickersFlattened().size)
    }

    @Test fun aStickerUpdateReportsExactlyTheStickersThatDisappeared() {
        val store = newGuildStickerStore()
        register(store)
        dispatcher.schedule { store.handleConnectionOpen(readyPayload()) }
        scheduler.triggerActions()

        dispatcher.schedule {
            store.handleStickerUpdate(
                GuildStickersUpdate(
                    GUILD_ID,
                    listOf(sticker(100L, type = StickerType.GUILD, guildId = GUILD_ID)),
                    GuildHashes(null, null, null, 0),
                ),
            )
        }
        scheduler.triggerActions()

        assertEquals(listOf(101L), deletedStickerIds)
        assertEquals(setOf(100L), store.getStickersForGuild(GUILD_ID).keys)
    }

    @Test fun emptyingAGuildsStickersRemovesTheGuildEntirely() {
        val store = newGuildStickerStore()
        register(store)
        dispatcher.schedule { store.handleConnectionOpen(readyPayload()) }
        scheduler.triggerActions()

        dispatcher.schedule {
            store.handleStickerUpdate(
                GuildStickersUpdate(GUILD_ID, emptyList(), GuildHashes(null, null, null, 0)),
            )
        }
        scheduler.triggerActions()

        assertEquals(emptyMap<Long, Map<Long, Sticker>>(), store.getAllGuildStickers())
        assertEquals(listOf(100L, 101L), deletedStickerIds)
    }

    @Test fun aStickerFetchedFromAGuildWeAreNotInIsRemembered() {
        val store = newGuildStickerStore()
        register(store)

        store.handleFetchedSticker(sticker(500L, type = StickerType.GUILD, guildId = FOREIGN_GUILD_ID))
        // A standard sticker belongs to StoreStickers, not here.
        store.handleFetchedSticker(sticker(501L, type = StickerType.STANDARD, packId = PACK_ID))
        scheduler.triggerActions()

        assertEquals(setOf(500L), store.getStickersForGuild(FOREIGN_GUILD_ID).keys)
        assertNull(store.getGuildSticker(501L))
    }

    // endregion

    // region fixtures

    private val deletedStickerIds = mutableListOf<Long>()

    private fun newStickerStore(api: () -> RestAPIInterface? = { null }) =
        StoreStickers(dispatcher, api, deck, clock)

    private fun newGuildStickerStore() =
        StoreGuildStickers(dispatcher, deck) { deletedStickerIds += it }

    private fun register(vararg stores: StoreV2) =
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(stores.toList(), deck))

    private fun pack(
        id: Long,
        name: String = "pack-$id",
        stickerIds: List<Long> = listOf(id * 10),
        storeListing: ModelStickerPackStoreListing? = null,
    ) = ModelStickerPack(
        id = id,
        stickers = stickerIds.map { sticker(it, packId = id) },
        name = name,
        storeListing = storeListing,
        skuId = id,
        coverStickerId = null,
        description = null,
        bannerAssetId = null,
    )

    private fun storeListing() = ModelStickerPackStoreListing(
        sku = ModelSku(
            applicationId = 0,
            name = "sku",
            id = 1,
            application = null,
            premium = false,
            type = 0,
            price = null,
            flags = 0,
            externalSkuStrategies = null,
        ),
        id = 1L,
        description = "listing",
        unpublishedAt = null,
        thumbnail = null,
    )

    private fun sticker(
        id: Long,
        type: StickerType = StickerType.STANDARD,
        packId: Long? = null,
        guildId: Long? = null,
    ) = Sticker(
        id = id,
        packId = packId,
        guildId = guildId,
        name = "sticker-$id",
        description = "",
        formatType = StickerFormatType.PNG,
        tags = "",
        type = type,
    )

    private fun readyPayload(): ModelPayload = gson.fromJson(
        """
        {
          "user": {"id": $ME_ID, "username": "me", "discriminator": "0000"},
          "private_channels": [],
          "guilds": [{
            "id": $GUILD_ID, "name": "Guild", "owner_id": 9, "unavailable": false, "features": [],
            "members": [{
              "guild_id": $GUILD_ID,
              "user": {"id": $ME_ID, "username": "me", "discriminator": "0000"},
              "roles": [], "pending": false
            }],
            "stickers": [
              {"id": 100, "guild_id": $GUILD_ID, "name": "a", "description": "", "format_type": 1, "tags": "", "type": 2},
              {"id": 101, "guild_id": $GUILD_ID, "name": "b", "description": "", "format_type": 1, "tags": "", "type": 2}
            ]
          }]
        }
        """.trimIndent(),
        ModelPayload::class.java,
    )

    // endregion

    companion object {
        private const val ME_ID = 100L
        private const val GUILD_ID = 10L
        private const val FOREIGN_GUILD_ID = 77L
        private const val PACK_ID = 1L
        private const val OTHER_PACK_ID = 2L
        private const val FETCH_WINDOW_MS = 300_000L
        private const val START_TIME_MS = 1_700_000_000_000L

        private val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
    }
}
