package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import rx.plugins.RxJavaHooks
import rx.schedulers.TestScheduler

/**
 * Covers the two-level selection model and the reactive re-validation pipeline.
 *
 * The pipeline hops through `computationLatest()`, so the computation scheduler is redirected onto
 * the same [TestScheduler] the dispatcher uses; draining it then runs the whole subscribe →
 * resolve → dispatch → snapshot round trip deterministically.
 */
class StoreChannelsSelectedTest {

    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()

    private lateinit var channels: StoreChannels
    private lateinit var guildSelected: StoreGuildSelected
    private lateinit var channelsSelected: StoreChannelsSelected

    @Before
    fun setUp() {
        RxJavaHooks.setOnComputationScheduler { scheduler }
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        channels = StoreChannels(dispatcher, deck, isAuthenticated = { true })
        val permissions = StorePermissions(users, channels, guilds, deck)
        guildSelected = StoreGuildSelected(dispatcher, deck)
        channelsSelected = StoreChannelsSelected(channels, permissions, guildSelected, dispatcher, deck)
        dispatcher.registerDispatchHandlers(
            StoreV2DispatchHandler(
                listOf(users, guilds, channels, permissions, guildSelected, channelsSelected),
                deck,
            ),
        )
    }

    @After
    fun tearDown() = RxJavaHooks.reset()

    // -- the state model ------------------------------------------------------------------------

    @Test
    fun everyResolvedStateHasItsOwnIdSoASingleObservableCanCarryAllOfThem() {
        val channel = channel(55, guildId = 0, type = Channel.DM)
        assertEquals(55L, StoreChannelsSelected.ResolvedSelectedChannel.Channel(channel).id)
        assertEquals(
            StoreChannelsSelected.ID_THREAD_DRAFT,
            StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft(channel).id,
        )
        assertEquals(
            StoreChannelsSelected.ID_UNSELECTED,
            StoreChannelsSelected.ResolvedSelectedChannel.Unselected.id,
        )
        assertEquals(
            StoreChannelsSelected.ID_UNAVAILABLE,
            StoreChannelsSelected.ResolvedSelectedChannel.Unavailable.id,
        )
        assertEquals(
            StoreChannelsSelected.ID_UNINITIALIZED,
            StoreChannelsSelected.ResolvedSelectedChannel.Uninitialized.id,
        )
    }

    @Test
    fun aThreadDraftResolvesToItsParentChannelAndExposesNoSelectedChannel() {
        val parent = channel(10, guildId = 20)
        val draft = StoreChannelsSelected.UserChannelSelection.ThreadDraft(10, 99, "Chat")
            .resolveWithChannel(parent)

        assertEquals(StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft(parent, 99, "Chat"), draft)
        assertEquals(parent, draft.channelOrParent)
        assertNull(draft.maybeChannel)
        assertEquals(StoreChannelsSelected.ID_THREAD_DRAFT, draft.id)
    }

    @Test
    fun peekingAChannelIsReportedAsAPeekViewOpen() {
        val channel = channel(55, guildId = 20)
        val peeked = StoreChannelsSelected.UserChannelSelection
            .SelectedChannel(55, peekParent = 10, analyticsLocation = SelectedChannelAnalyticsLocation.EMBED)
            .resolveWithChannel(channel) as StoreChannelsSelected.ResolvedSelectedChannel.Channel

        assertEquals(ChannelAnalyticsViewType.PEEK_VIEW, peeked.analyticsViewType)
        assertEquals(
            ChannelAnalyticsViewType.FULL_VIEW,
            StoreChannelsSelected.ResolvedSelectedChannel.Channel(channel).analyticsViewType,
        )
    }

    // -- resolution -----------------------------------------------------------------------------

    @Test
    fun selectionStaysUninitializedUntilTheChannelStoreHasHydrated() {
        assertEquals(StoreChannelsSelected.ID_UNINITIALIZED, channelsSelected.getId())

        dispatcher.schedule { channelsSelected.handleGuildSelected() }
        drain()

        assertEquals(StoreChannelsSelected.ID_UNINITIALIZED, channelsSelected.getId())
    }

    @Test
    fun aSelectedPrivateChannelResolvesOnceItArrivesInTheChannelStore() {
        val dm = channel(55, guildId = 0, type = Channel.DM)
        dispatcher.schedule {
            channels.handleStoreInitTimeout()
            channelsSelected.handleChannelSelected(0L, 55L)
        }
        drain()
        // The channel does not exist yet, and there is no fallback inside the DM pseudo-guild.
        assertEquals(StoreChannelsSelected.ID_UNSELECTED, channelsSelected.getId())

        dispatcher.schedule {
            channels.handleChannelOrThreadCreateOrUpdate(dm)
            channelsSelected.handleChannelSelected(0L, 55L)
        }
        drain()

        assertEquals(55L, channelsSelected.getId())
        assertEquals(dm.id, channelsSelected.getSelectedChannel()?.id)
    }

    @Test
    fun aGuildWithNoVisibleChannelResolvesToUnavailableRatherThanNothing() {
        dispatcher.schedule {
            channels.handleStoreInitTimeout()
            guildSelected.handleGuildSelected(99L)
            channelsSelected.handleGuildSelected()
        }
        drain()

        assertEquals(StoreChannelsSelected.ID_UNAVAILABLE, channelsSelected.getId())
        assertNull(channelsSelected.getSelectedChannel())
    }

    @Test
    fun transientFallbackDoesNotReplaceTheRequestedChannel() {
        val requested = StoreChannelsSelected.UserChannelSelection.SelectedChannel(99)

        assertFalse(StoreChannelsSelected.shouldPersistResolvedSelection(requested, 1))
        assertTrue(StoreChannelsSelected.shouldPersistResolvedSelection(requested, 99))
        assertTrue(StoreChannelsSelected.shouldPersistResolvedSelection(null, 1))
    }

    @Test
    fun coldStartResolvesPersistedChannelBeforeReady() {
        val dm = channel(55, guildId = 0, type = Channel.DM)
        val restoredDispatcher = Dispatcher(scheduler)
        val restoredDeck = ObservationDeck()
        val users = StoreUser(dispatcher = restoredDispatcher, observationDeck = restoredDeck)
        val guilds = StoreGuilds(users, restoredDispatcher, restoredDeck)
        val restoredChannels = StoreChannels(
            restoredDispatcher,
            restoredDeck,
            isAuthenticated = { true },
            channelsCache = Persister("test_channels_${System.nanoTime()}", emptyList<Channel>()).apply {
                set(listOf(dm))
            },
        )
        val permissions = StorePermissions(users, restoredChannels, guilds, restoredDeck)
        val restoredGuildSelected = StoreGuildSelected(restoredDispatcher, restoredDeck)
        val restoredSelection = StoreChannelsSelected(
            restoredChannels,
            permissions,
            restoredGuildSelected,
            restoredDispatcher,
            restoredDeck,
            selectedChannelIdsCache = Persister(
                "test_selected_channel_${System.nanoTime()}",
                emptyMap<Long, Long>(),
            ).apply { set(mapOf(0L to dm.id)) },
        )
        restoredDispatcher.registerDispatchHandlers(
            StoreV2DispatchHandler(
                listOf(users, guilds, restoredChannels, permissions, restoredGuildSelected, restoredSelection),
                restoredDeck,
            ),
        )

        restoredDispatcher.schedule {
            restoredChannels.init()
            restoredSelection.restore()
        }
        drain()

        assertEquals(dm, restoredSelection.getSelectedChannel())
    }

    // -- helpers --------------------------------------------------------------------------------

    /** Drains the dispatcher, the resolution pipeline and the dispatch it schedules back. */
    private fun drain() = repeat(4) { scheduler.triggerActions() }

    private fun channel(id: Long, guildId: Long, type: Int = Channel.GUILD_TEXT, parentId: Long = 0) = Channel(
        topic = null,
        type = type,
        guildId = guildId,
        name = "channel",
        lastMessageId = 0,
        id = id,
        ownerId = 0,
        recipientIds = null,
        recipients = null,
        position = 0,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0,
        applicationId = 0,
        nicks = null,
        nsfw = false,
        parentId = parentId,
        memberListId = null,
        rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null,
        rtcRegion = null,
        flags = null,
        guildHashes = null,
        availableTags = null,
        appliedTags = null,
        threadMetadata = null,
        messageCount = null,
        memberCount = null,
        memberIdsPreview = null,
        member = null,
    )

    companion object {
        private val directory = Files.createTempDirectory("channels-selected")

        @JvmStatic
        @BeforeClass
        fun initializePersister() = Persister.init(directory.toFile())

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            directory.toFile().deleteRecursively()
        }
    }
}
