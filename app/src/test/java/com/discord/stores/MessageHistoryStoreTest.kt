package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.interaction.Interaction
import com.discord.api.interaction.InteractionStateUpdate
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.models.domain.ModelChannelUnreadUpdate
import com.discord.models.domain.ModelReadState
import com.discord.models.message.Message
import com.discord.stores.StoreMessagesLoader.Direction
import com.discord.stores.StoreMessagesLoader.LoadRequest
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.schedulers.TestScheduler
import rx.subjects.BehaviorSubject

class MessageHistoryStoreTest {
    @Before
    fun initializePersistence() {
        Persister.init(Files.createTempDirectory("message-history").toFile(), Observable.never())
    }

    @Test
    fun applicationCommandGatewayStateUpdatesAndModalRemovalReachTheLocalRow() {
        val fixture = storeFixture()
        val sendData = commandSendData(900L)
        fixture.dispatcher.schedule {
            fixture.store.handleLocalMessageCreate(
                Message(
                    id = 900L,
                    channelId = 42L,
                    nonce = "900",
                    type = -5,
                    interaction = Interaction(name = "upload"),
                ),
            )
        }
        fixture.scheduler.triggerActions()

        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandInteraction(
                sendData,
                InteractionStateUpdate(123L, "900"),
                failed = false,
                loading = true,
            )
        }
        fixture.scheduler.triggerActions()
        var local = fixture.store.localMessagesHolder.getMessage(42L, "900")
        assertEquals(-5, local?.type)
        assertEquals(128L, local?.flags)
        assertEquals(123L, local?.interaction?.id)

        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandInteraction(
                sendData,
                InteractionStateUpdate(123L, "900"),
                failed = true,
                loading = false,
            )
        }
        fixture.scheduler.triggerActions()
        local = fixture.store.localMessagesHolder.getMessage(42L, "900")
        assertEquals(-4, local?.type)
        assertEquals(null, local?.flags)

        fixture.dispatcher.schedule { fixture.store.handleApplicationCommandModal(sendData) }
        fixture.scheduler.triggerActions()
        assertEquals(null, fixture.store.localMessagesHolder.getMessage(42L, "900"))
    }

    @Test
    fun loadPaginateAndJumpUseFiftyMessageRequests() {
        val requests = mutableListOf<LoadRequest>()
        val loader = loader(requests) { request ->
            val start = when (request.direction) {
                Direction.INITIAL -> 101L
                Direction.BEFORE -> 51L
                Direction.AFTER -> 151L
                Direction.AROUND -> requireNotNull(request.anchorMessageId) - 24
            }
            Result.success((start until start + request.limit).map(::message))
        }
        val chunks = mutableListOf<StoreMessagesLoader.ChannelChunk>()
        val scrolls = mutableListOf<Long>()
        loader.observeChunks().subscribe(chunks::add)
        loader.observeScrollTo().subscribe(scrolls::add)
        loader.handleAuthToken("test-token")
        loader.handleConnected(true)

        loader.handleChannelSelected(7)
        loader.loadBefore(7, 101)
        loader.loadAfter(7, 150)
        loader.jumpToMessage(7, 500)

        assertEquals(
            listOf(Direction.INITIAL, Direction.BEFORE, Direction.AFTER, Direction.AROUND),
            requests.map { it.direction },
        )
        assertTrue(requests.all { it.limit == 50 })
        assertEquals(4, chunks.size)
        assertTrue(chunks.last().isJump)
        // The initial load scrolls to the unread divider; the jump then targets its own message.
        assertEquals(listOf(StoreMessagesLoader.SCROLL_TO_LAST_UNREAD, 500L), scrolls)
    }

    @Test
    fun initialUnreadScrollStaysUntilConsumed() {
        val loader = loader(mutableListOf()) { request ->
            Result.success((101L until 101L + request.limit).map(::message))
        }
        loader.handleAuthToken("test-token")
        loader.handleConnected(true)
        loader.handleChannelSelected(7)
        val late = mutableListOf<Long>()
        loader.observeScrollTo().subscribe(late::add)
        assertEquals(listOf(StoreMessagesLoader.SCROLL_TO_LAST_UNREAD), late)
    }

    @Test
    fun channelSelectionCannotCancelPendingJump() {
        val requests = mutableListOf<LoadRequest>()
        val jumped = CountDownLatch(1)
        val loader = StoreMessagesLoader(
            loader = { request, callback ->
                requests += request
                if (request.direction == Direction.AROUND) jumped.countDown()
                callback(Result.success(emptyList()))
            },
            scheduler = TestScheduler(),
            retryJitter = { 0 },
        ).apply { handleBackgrounded(false) }
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        loader.init(
            StoreChat(dispatcher, deck),
            StoreMessages(deck),
            StoreMessagesLoader.Dependencies(
                observeChannelMessages = { Observable.just(emptyList()) },
                observeMessage = { _, _ -> Observable.just(null) },
                observeChannel = { Observable.just(channel(2, Channel.GUILD_TEXT)) },
                getChannel = { channel(2, Channel.GUILD_TEXT) },
                observeConnectionOpen = { Observable.never() },
                selectChannel = {
                    loader.handleChannelSelected(it.id)
                },
                meUserId = { 1L },
            ),
        )
        loader.handleAuthToken("token")
        loader.handleChannelSelected(1)
        requests.clear()

        loader.jumpToMessage(2, 500)

        assertTrue(jumped.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(Direction.AROUND), requests.filter { it.channelId == 2L }.map { it.direction })
    }

    /** A thread the user never joined is not in the local store; the jump has to fetch it. */
    @Test
    fun jumpFetchesAChannelTheLocalStoreDoesNotHold() {
        val requests = mutableListOf<LoadRequest>()
        val jumped = CountDownLatch(1)
        val loader = StoreMessagesLoader(
            loader = { request, callback ->
                requests += request
                if (request.direction == Direction.AROUND) jumped.countDown()
                callback(Result.success(emptyList()))
            },
            scheduler = TestScheduler(),
            retryJitter = { 0 },
        ).apply { handleBackgrounded(false) }
        val fetched = BehaviorSubject.create<Channel?>(null as Channel?)
        val deck = ObservationDeck()
        loader.init(
            StoreChat(Dispatcher(TestScheduler()), deck),
            StoreMessages(deck),
            StoreMessagesLoader.Dependencies(
                observeChannelMessages = { Observable.just(emptyList()) },
                observeMessage = { _, _ -> Observable.just(null) },
                observeChannel = { fetched },
                getChannel = { null },
                observeConnectionOpen = { Observable.just(true) },
                selectChannel = { loader.handleChannelSelected(it.id) },
                meUserId = { 1L },
                fetchChannel = { id -> fetched.onNext(channel(id, Channel.PUBLIC_THREAD)) },
            ),
        )
        loader.handleAuthToken("token")
        loader.handleChannelSelected(1)
        requests.clear()

        loader.jumpToMessage(3, 500)

        assertTrue(jumped.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(Direction.AROUND), requests.filter { it.channelId == 3L }.map { it.direction })
    }

    /** A short page means there is nothing older; a full one leaves the door open. */
    @Test
    fun shortInitialPageMarksOldestLoaded() {
        val loader = loader(mutableListOf()) { Result.success((1L..3L).map(::message)) }
        val states = mutableListOf<StoreMessagesLoader.ChannelLoadedState>()
        loader.getMessagesLoadedState(11).subscribe(states::add)
        loader.handleAuthToken("token")
        loader.handleConnected(true)
        loader.handleChannelSelected(11)

        assertTrue(states.last().isInitialMessagesLoaded)
        assertTrue(states.last().isOldestMessagesLoaded)
        assertFalse(states.last().isLoadingMessages)
    }

    @Test
    fun loadFailureRetriesOnJitteredBackoff() {
        var attempts = 0
        val scheduler = TestScheduler()
        val loader = StoreMessagesLoader(
            loader = { _, callback ->
                attempts++
                if (attempts == 1) callback(Result.failure(IllegalStateException("offline")))
                else callback(Result.success(emptyList()))
            },
            scheduler = scheduler,
            retryJitter = { 0 },
        ).apply { handleBackgrounded(false) }
        val states = mutableListOf<StoreMessagesLoader.ChannelLoadedState>()
        loader.getMessagesLoadedState(9).subscribe(states::add)
        loader.handleAuthToken("test-token")
        loader.handleConnected(true)
        loader.handleChannelSelected(9)

        assertEquals(1, attempts)
        assertFalse(states.last().isLoadingMessages)

        // The default 1500 ms delay is doubled on failure; jitter is pinned to 0 for the test.
        scheduler.advanceTimeBy(2_999, TimeUnit.MILLISECONDS)
        assertEquals(1, attempts)
        scheduler.advanceTimeBy(2, TimeUnit.MILLISECONDS)
        assertEquals(2, attempts)
    }

    /** Nothing is fetched while the app reports itself backgrounded. */
    @Test
    fun backgroundedSuppressesLoads() {
        var attempts = 0
        val loader = StoreMessagesLoader(
            loader = { _, callback ->
                attempts++
                callback(Result.success(emptyList()))
            },
            scheduler = TestScheduler(),
        )
        loader.handleAuthToken("token")
        loader.handleConnected(true)
        loader.handleBackgrounded(true)
        loader.handleChannelSelected(12)
        assertEquals(0, attempts)

        loader.handleBackgrounded(false)
        assertEquals(1, attempts)
    }

    @Test
    fun freshGatewayReadyRefetchesOnlyMismatchedChannels() {
        val requests = mutableListOf<LoadRequest>()
        val states = mutableListOf<StoreMessagesLoader.ChannelLoadedState>()
        val loader = loader(requests) { Result.success(emptyList()) }
        loader.getMessagesLoadedState(12).subscribe(states::add)
        loader.handleAuthToken("token")
        loader.handleConnected(true)
        loader.handleChannelSelected(12)

        loader.handleConnected(false)
        assertEquals(listOf(12L), requests.map(LoadRequest::channelId))
        loader.handleFreshSession()
        assertEquals(listOf(12L), requests.map(LoadRequest::channelId))
        assertTrue(loader.handleFreshSessionReady(mapOf(12L to 2L)) { 1L }.contains(12L))

        assertEquals(listOf(12L, 12L), requests.map(LoadRequest::channelId))
        assertTrue(states.count { it.isLoadingMessages } >= 2)

        loader.handleFreshSession()
        assertEquals(emptySet<Long>(), loader.handleFreshSessionReady(mapOf(12L to 2L)) { 2L })
        assertEquals(2, requests.size)
    }

    @Test
    fun freshGatewayReadyRefetchesChannelsMissingFromPartialReady() {
        val requests = mutableListOf<LoadRequest>()
        val loader = loader(requests) { Result.success(emptyList()) }
        loader.handleAuthToken("token")
        loader.handleConnected(true)
        loader.handleChannelSelected(12)

        loader.handleFreshSession()

        assertTrue(loader.handleFreshSessionReady(emptyMap()) { 1L }.contains(12L))
        assertEquals(listOf(12L, 12L), requests.map(LoadRequest::channelId))
    }

    @Test
    fun forumParentSelectionDoesNotLoadMessageHistoryButItsPostDoes() {
        val requests = mutableListOf<LoadRequest>()
        val loader = loader(requests) { Result.success(emptyList()) }
        loader.handleAuthToken("token")
        loader.handleConnected(true)

        loader.handleChannelSelected(channel(10, Channel.GUILD_FORUM))
        loader.handleChannelSelected(channel(11, Channel.PUBLIC_THREAD))

        assertEquals(listOf(11L), requests.map(LoadRequest::channelId))
    }

    @Test
    fun holderTrimsAtThresholdDetachesAndPublishesImmutableSnapshots() {
        val holder = StoreMessagesHolder(cache = Persister("trim-${System.nanoTime()}", emptyMap()))
        holder.selectChannel(1)
        holder.loadMessageChunks(
            listOf(
                StoreMessagesLoader.ChannelChunk(
                    channelId = 1,
                    messages = (1L..200L).map { message(it).copy(channelId = 1) },
                    isInitial = true,
                    isPresent = false,
                    isAppendingTop = true,
                    isJump = false,
                ),
            ),
        )

        assertEquals((1L..100L).toList(), holder.getMessages(1).map(Message::id))
        assertEquals(setOf(1L), holder.getDetached())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (holder.getAll() as MutableMap<Long, List<Message>>)[2] = emptyList()
        }
    }

    /** A chunk that reaches the present re-attaches a detached channel. */
    @Test
    fun presentChunkReattachesDetachedChannel() {
        val holder = StoreMessagesHolder(cache = Persister("detach-${System.nanoTime()}", emptyMap()))
        holder.selectChannel(1)
        holder.loadMessageChunks(listOf(chunk((1L..5L).map { message(it).copy(channelId = 1) }, isJump = true)))
        assertEquals(setOf(1L), holder.getDetached())

        holder.loadMessageChunks(listOf(chunk((6L..9L).map { message(it).copy(channelId = 1) }, isPresent = true)))
        assertTrue(holder.getDetached().isEmpty())
    }

    /** A detached channel must not have live messages spliced onto its historical window. */
    @Test
    fun detachedChannelIgnoresLiveMessages() {
        val holder = StoreMessagesHolder(cache = Persister("live-${System.nanoTime()}", emptyMap()))
        holder.selectChannel(1)
        holder.loadMessageChunks(listOf(chunk((1L..3L).map { message(it).copy(channelId = 1) }, isJump = true)))

        holder.addMessages(listOf(message(99).copy(channelId = 1)))

        assertEquals(listOf(1L, 2L, 3L), holder.getMessages(1).map(Message::id))
    }

    @Test
    fun partialUpdateAndDeleteMutateOnlyTheTargetMessage() {
        val fixture = storeFixture()
        fixture.dispatcher.schedule {
            fixture.store.handleChannelSelected(3)
            fixture.store.handleMessageCreate(3, message(10, "before"))
            fixture.store.handleMessageCreate(3, message(11, "untouched"))
        }
        fixture.scheduler.triggerActions()
        fixture.dispatcher.schedule {
            fixture.store.handleMessageUpdate(3, 10) { it.copy(content = "after") }
            fixture.store.handleMessageDelete(3, 11)
        }
        fixture.scheduler.triggerActions()

        assertEquals(listOf(10L), fixture.store.getMessages(3).map(Message::id))
        assertEquals("after", fixture.store.getMessage(3, 10)?.content)
    }

    @Test
    fun nonceReplacementRemovesOptimisticMessageExactlyOnce() {
        val fixture = storeFixture()
        fixture.dispatcher.schedule {
            fixture.store.handleChannelSelected(4)
            fixture.store.handleLocalMessageCreate(4, "nonce", message(100, "optimistic"))
        }
        fixture.scheduler.triggerActions()
        fixture.dispatcher.schedule {
            fixture.store.handleMessageCreate(4, message(200, "server"), "nonce")
            fixture.store.handleMessageCreate(4, message(200, "server"), "nonce")
        }
        fixture.scheduler.triggerActions()

        assertEquals(listOf(200L), fixture.store.getMessages(4).map(Message::id))
        assertEquals(null, fixture.store.localMessagesHolder.getMessage(4, "nonce"))
    }

    /**
     * The cache is written at most once per minute, not once per message: 50 mutations inside the
     * window produce no write, and the first mutation past it produces one.
     */
    @Test
    fun holderPersistsOncePerThrottleWindow() {
        var now = 1_000_000L
        val cache = Persister<Map<Long, List<Message>>>("throttle-${System.nanoTime()}", emptyMap())
        val holder = StoreMessagesHolder(cache, clock = { now }, persistScheduler = TestScheduler())
        holder.restore(enabled = true)
        holder.selectChannel(6)

        (1L..50L).forEach { holder.addMessages(listOf(message(it).copy(channelId = 6))) }
        assertTrue("nothing may be written inside the throttle window", cache.get().isEmpty())

        now += StoreMessagesHolder.CACHE_PERSIST_INTERVAL + 1
        holder.addMessages(listOf(message(51).copy(channelId = 6)))
        assertEquals(setOf(6L), cache.get().keys)
    }

    @Test
    fun cachesRestoreAndLogoutClearsBothHistories() {
        var now = 5_000_000L
        val syncedKey = "synced-${System.nanoTime()}"
        val localKey = "local-${System.nanoTime()}"
        val first = storeFixture(syncedKey, localKey) { now }
        first.dispatcher.schedule {
            first.store.restore()
            first.store.handleChannelSelected(5)
            first.store.handleMessageCreate(5, message(300, "cached"))
            first.store.handleLocalMessageCreate(
                5,
                "pending",
                message(301, "pending").copy(
                    channelId = 5,
                    author = User(7, "me"),
                    timestamp = UtcDateTime(now),
                    nonce = "pending",
                    type = -2,
                ),
            )
        }
        first.scheduler.triggerActions()
        // Push past the synced holder's persist throttle, then force one more publish.
        now += StoreMessagesHolder.CACHE_PERSIST_INTERVAL + 1
        first.dispatcher.schedule { first.store.handleMessageCreate(5, message(302, "flush")) }
        first.scheduler.triggerActions()
        Persister.persistAll()

        val restored = storeFixture(syncedKey, localKey) { now }
        restored.dispatcher.schedule {
            restored.store.restore()
            restored.store.handleChannelSelected(5)
        }
        restored.scheduler.triggerActions()
        // Local messages are appended after the synced history, never sorted into it.
        assertEquals(listOf(300L, 302L, 301L), restored.store.getMessages(5).map(Message::id))

        restored.dispatcher.schedule { restored.store.handlePreLogout() }
        restored.scheduler.triggerActions()
        assertTrue(restored.store.getMessages(5).isEmpty())
        assertTrue(restored.store.localMessagesHolder.getFlattenedMessages().isEmpty())
        Persister.persistAll()

        val afterLogout = storeFixture(syncedKey, localKey) { now }
        afterLogout.dispatcher.schedule {
            afterLogout.store.restore()
            afterLogout.store.handleChannelSelected(5)
        }
        afterLogout.scheduler.triggerActions()
        assertTrue(afterLogout.store.getMessages(5).isEmpty())
    }

    @Test
    fun malformedLocalMessagesAreRemovedWhileValidPendingMessagesSurvive() {
        val key = "local-repair-${System.nanoTime()}"
        val cache = Persister<Map<Long, List<StoreLocalMessagesHolder.LocalEntry>>>(key, emptyMap())
        val valid = Message(
            id = 2L,
            channelId = 5L,
            author = User(7L, "me"),
            content = "pending",
            timestamp = UtcDateTime(1L),
            nonce = "valid",
            type = -2,
        )
        val malformed = Message(id = 1L, channelId = 5L, nonce = "broken", type = -2)
        cache.set(
            mapOf(5L to listOf(
                StoreLocalMessagesHolder.LocalEntry("broken", malformed),
                StoreLocalMessagesHolder.LocalEntry("valid", valid),
            )),
            persist = true,
        )

        val holder = StoreLocalMessagesHolder(cache)
        holder.restore()

        assertEquals(listOf(valid), holder.getMessages(5L))
        assertEquals(listOf(valid), cache.get().getValue(5L).map { it.message })
    }

    @Test
    fun mostRecentOnlyMovesForward() {
        val fixture = mostRecentFixture()
        fixture.dispatcher.schedule {
            fixture.store.handleMessageCreate(8, 20)
            fixture.store.handleMessageCreate(8, 19)
        }
        fixture.scheduler.triggerActions()
        assertEquals(20L, fixture.store.getMostRecentIds()[8])
    }

    /** `CHANNEL_UNREAD_UPDATE` is the only source for channels with no live subscription. */
    @Test
    fun channelUnreadUpdateAdvancesMostRecentIds() {
        val fixture = mostRecentFixture()
        fixture.dispatcher.schedule {
            fixture.store.handleChannelUnreadUpdate(
                ModelChannelUnreadUpdate(listOf(ModelReadState(42L, 900L, 0, 1))),
            )
        }
        fixture.scheduler.triggerActions()
        assertEquals(900L, fixture.store.getMostRecentIds()[42])
    }

    private fun loader(
        requests: MutableList<LoadRequest>,
        respond: (LoadRequest) -> Result<List<Message>>,
    ) = StoreMessagesLoader(
        loader = { request, callback ->
            requests += request
            callback(respond(request))
        },
        scheduler = TestScheduler(),
        retryJitter = { 0 },
    ).apply { handleBackgrounded(false) }

    private fun chunk(
        messages: List<Message>,
        isInitial: Boolean = false,
        isPresent: Boolean = false,
        isAppendingTop: Boolean = false,
        isJump: Boolean = false,
    ) = StoreMessagesLoader.ChannelChunk(1, messages, isInitial, isPresent, isAppendingTop, isJump)

    private fun mostRecentFixture(): MostRecentFixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreMessagesMostRecent(deck, Persister("recent-${System.nanoTime()}", emptyMap()))
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        return MostRecentFixture(scheduler, dispatcher, store)
    }

    private fun storeFixture(
        syncedKey: String = "synced-${System.nanoTime()}",
        localKey: String = "local-${System.nanoTime()}",
        clock: () -> Long = { System.currentTimeMillis() },
    ): Fixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreMessages(
            deck,
            StoreMessagesHolder(
                Persister(syncedKey, emptyMap()),
                clock = { clock() },
                persistScheduler = TestScheduler(),
            ),
            StoreLocalMessagesHolder(Persister(localKey, emptyMap())),
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        return Fixture(scheduler, dispatcher, store)
    }

    private fun message(id: Long, content: String = "message-$id") = Message(id = id, content = content)

    private fun channel(id: Long, type: Int) = Channel(
        topic = null, type = type, guildId = 1, name = "channel", lastMessageId = 0, id = id,
        ownerId = 0, recipientIds = null, recipients = null, position = 0, permissionOverwrites = null,
        userLimit = 0, bitrate = 0, icon = null, originChannelId = 0, applicationId = 0, nicks = null,
        nsfw = false, parentId = 10, memberListId = null, rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null, rtcRegion = null, flags = null, guildHashes = null,
        availableTags = null, appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )

    private fun commandSendData(nonce: Long) = ApplicationCommandLocalSendData(
        nonce = nonce,
        channelId = 42L,
        guildId = null,
        commandGuildId = null,
        application = Application(id = 9L, name = "test", commandCount = 1),
        sessionId = "session",
        applicationCommandName = "upload",
        applicationCommandId = "10",
        applicationCommandsValues = emptyList(),
        version = "1",
    )

    private data class Fixture(
        val scheduler: TestScheduler,
        val dispatcher: Dispatcher,
        val store: StoreMessages,
    )

    private data class MostRecentFixture(
        val scheduler: TestScheduler,
        val dispatcher: Dispatcher,
        val store: StoreMessagesMostRecent,
    )
}
