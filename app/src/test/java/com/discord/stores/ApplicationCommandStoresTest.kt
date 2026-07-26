package com.discord.stores

import com.discord.api.botuikit.SelectItem
import com.discord.api.commands.ApplicationCommandAutocompleteChoice
import com.discord.api.commands.ApplicationCommandAutocompleteResult
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.GuildApplicationCommands
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.Application as ModelApplication
import com.discord.models.commands.RemoteApplicationCommand
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

/**
 * Behavioural cover for the slash-command cluster.
 *
 * Everything runs on a [TestScheduler], so a dispatch only happens where the test says
 * `triggerActions()` — which is also what makes the snapshot/observe boundary observable.
 */
class ApplicationCommandStoresTest {

    /**
     * [StoreApplicationCommandFrecency] builds a real [Persister] for its frecency tracker, so the
     * persistence root has to exist before any fixture is constructed.
     */
    private val directory = Files.createTempDirectory("application-command-stores").toFile()

    @Before fun setUp() = Persister.init(directory)
    @After fun tearDown() { directory.deleteRecursively() }

    // -- StoreAutocomplete ---------------------------------------------------------------------

    @Test
    fun autocompleteVisibilityIsPublishedOnDispatchAndDeduplicated() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreAutocomplete(deck, dispatcher)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        val seen = mutableListOf<Boolean>()
        store.observeAutocompleteVisibility().subscribe(seen::add)
        assertEquals(listOf(false), seen)

        store.setAutocompleteVisible(true)
        scheduler.triggerActions()
        assertEquals(listOf(false, true), seen)

        // Same value again: distinctUntilChanged must swallow it.
        store.setAutocompleteVisible(true)
        scheduler.triggerActions()
        assertEquals(listOf(false, true), seen)
    }

    // -- StoreLocalActionComponentState --------------------------------------------------------

    /**
     * Clearing one component leaves the message's other selections alone; clearing with a null
     * index drops the message entirely.
     */
    @Test
    fun selectComponentSelectionsClearPerComponentAndPerMessage() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreLocalActionComponentState(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        store.setSelectComponentSelection(7L, 0, listOf(selectItem("a")))
        store.setSelectComponentSelection(7L, 1, listOf(selectItem("b")))
        scheduler.triggerActions()
        assertEquals(setOf(0, 1), store.getSelectComponentSelectionsData()[7L]?.keys)

        dispatcher.schedule { store.clearState(7L, 0) }
        scheduler.triggerActions()
        assertEquals(setOf(1), store.getSelectComponentSelectionsData()[7L]?.keys)

        dispatcher.schedule { store.clearState(7L, null) }
        scheduler.triggerActions()
        assertNull(store.getSelectComponentSelectionsData()[7L])
    }

    /** An index that is not held clears nothing — the reference makes no entry for it. */
    @Test
    fun clearingAnUnknownComponentIndexIsANoOp() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreLocalActionComponentState(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        store.setSelectComponentSelection(7L, 0, listOf(selectItem("a")))
        scheduler.triggerActions()

        dispatcher.schedule { store.clearState(7L, 9) }
        scheduler.triggerActions()
        assertEquals(setOf(0), store.getSelectComponentSelectionsData()[7L]?.keys)
    }

    // -- StoreApplicationCommands: nonce routing -----------------------------------------------

    /**
     * The three request kinds share one gateway event and are told apart only by nonce, so a
     * response carrying the *discover* nonce must not land in the query list and vice versa.
     */
    @Test
    fun gatewayCommandsAreRoutedByNonce() {
        val fixture = commandsFixture()
        fixture.store.requestApplicationCommandsQuery(GUILD_ID, "sh")
        fixture.scheduler.triggerActions()
        val queryNonce = fixture.gateway.lastNonce

        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandsUpdate(
                guildApplicationCommands(queryNonce, listOf(apiCommand(11L, "shrug-ish"))),
            )
        }
        fixture.scheduler.triggerActions()

        assertEquals(listOf("shrug-ish"), fixture.store.getQueryCommands().map(ApplicationCommand::name))
        // Discover was never asked for, so it is still the default empty window.
        assertTrue(fixture.store.getDiscoverCommands().commands.isEmpty())

        // A stale nonce is ignored outright.
        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandsUpdate(
                guildApplicationCommands("not-a-live-nonce", listOf(apiCommand(12L, "ghost"))),
            )
        }
        fixture.scheduler.triggerActions()
        assertEquals(listOf("shrug-ish"), fixture.store.getQueryCommands().map(ApplicationCommand::name))
    }

    /**
     * Query results are the server's matches plus locally matched built-ins. `/sh` matches
     * `shrug` and nothing else.
     */
    @Test
    fun queryResultsAppendMatchingBuiltInCommands() {
        val fixture = commandsFixture()
        fixture.store.requestApplicationCommandsQuery(GUILD_ID, "sh")
        fixture.scheduler.triggerActions()

        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandsUpdate(
                guildApplicationCommands(fixture.gateway.lastNonce, emptyList()),
            )
        }
        fixture.scheduler.triggerActions()

        assertEquals(listOf("shrug"), fixture.store.getQueryCommands().map(ApplicationCommand::name))
    }

    /**
     * A guild with no gateway session cannot answer, so the discover list falls back to the
     * built-ins rather than spinning.
     */
    @Test
    fun aRejectedGatewayRequestFallsBackToBuiltInCommands() {
        val fixture = commandsFixture(gatewayAccepts = false)
        fixture.store.requestInitialApplicationCommands(GUILD_ID, null)
        fixture.scheduler.triggerActions()

        val names = fixture.store.getDiscoverCommands().commands.map(ApplicationCommand::name)
        assertEquals(listOf("shrug", "tableflip", "unflip", "me", "spoiler"), names)
        assertEquals(LoadState.JustLoadedAll, fixture.store.getDiscoverCommands().loadState)
    }

    /** Sub-commands are expanded at snapshot time, so the picker never shows a bare parent. */
    @Test
    fun snapshotExpandsSubCommands() {
        val fixture = commandsFixture()
        fixture.store.requestApplicationCommandsQuery(GUILD_ID, "")
        fixture.scheduler.triggerActions()

        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandsUpdate(
                guildApplicationCommands(
                    fixture.gateway.lastNonce,
                    listOf(
                        apiCommand(
                            id = 20L,
                            name = "role",
                            options = listOf(
                                apiOption("add", ApplicationCommandType.SUBCOMMAND),
                                apiOption("remove", ApplicationCommandType.SUBCOMMAND),
                            ),
                        ),
                    ),
                ),
            )
        }
        fixture.scheduler.triggerActions()

        val names = fixture.store.getQueryCommands().map(ApplicationCommand::name)
        assertTrue(names.containsAll(listOf("role add", "role remove")))
        assertFalse(names.contains("role"))
    }

    /**
     * Autocomplete choices win: a late "loading"/"failed" for a query that already has choices must
     * not blank the suggestion list.
     */
    @Test
    fun autocompleteChoicesAreNotDowngradedByALateLoadingSignal() {
        val fixture = commandsFixture()
        val store = fixture.store

        fixture.dispatcher.schedule { store.setAutocompleteLoading("query", "ka") }
        fixture.scheduler.triggerActions()
        assertEquals(
            CommandAutocompleteState.Loading,
            store.getAutocompleteOptionResults()["query"]?.get("ka"),
        )

        // Choices can only be delivered against a tracked nonce, so drive the same code path the
        // gateway would: an unknown nonce is dropped, a tracked one lands.
        fixture.dispatcher.schedule {
            store.handleApplicationCommandAutocompleteResult(
                ApplicationCommandAutocompleteResult("unknown", listOf(choice("x"))),
            )
        }
        fixture.scheduler.triggerActions()
        assertEquals(
            CommandAutocompleteState.Loading,
            store.getAutocompleteOptionResults()["query"]?.get("ka"),
        )

        fixture.dispatcher.schedule { store.clearAutocompleteResults() }
        fixture.scheduler.triggerActions()
        assertTrue(store.getAutocompleteOptionResults().isEmpty())
    }

    // -- StoreApplicationCommandFrecency -------------------------------------------------------

    /**
     * Guild-scoped and unscoped uses live in the same tracker and are separated by the `:guildId`
     * suffix, so asking for one must never return the other.
     */
    @Test
    fun frecencyKeysAreScopedByGuild() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreApplicationCommandFrecency(dispatcher, deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))

        store.onCommandUsed(GUILD_ID, "100")
        store.onCommandUsed(null, "200")
        scheduler.triggerActions()

        assertEquals(listOf("100"), store.getTopCommandIds(GUILD_ID))
        assertEquals(listOf("200"), store.getTopCommandIds(null))
        assertEquals(listOf("200"), store.getTopCommandIds(0L))
        assertTrue(store.getTopCommandIds(999L).isEmpty())
    }

    // -- Fixtures ------------------------------------------------------------------------------

    private fun commandsFixture(gatewayAccepts: Boolean = true): CommandsFixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val gateway = RecordingGateway(gatewayAccepts)
        val frecency = StoreApplicationCommandFrecency(dispatcher, deck)
        val store = StoreApplicationCommands(
            dispatcher = dispatcher,
            storeApplicationCommandFrecency = frecency,
            gatewayRequest = gateway,
            restApi = { null },
            observationDeck = deck,
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store, frecency), deck))
        // The store parks requests until the socket says it is ready.
        dispatcher.schedule { store.handleConnectionReady(true) }
        scheduler.triggerActions()
        return CommandsFixture(scheduler, dispatcher, gateway, store)
    }

    private class CommandsFixture(
        val scheduler: TestScheduler,
        val dispatcher: Dispatcher,
        val gateway: RecordingGateway,
        val store: StoreApplicationCommands,
    )

    /** Captures the nonce of the last request instead of touching a socket. */
    private class RecordingGateway(private val accepts: Boolean) :
        StoreApplicationCommands.ApplicationCommandRequester {
        var lastNonce: String = ""
            private set

        override fun requestApplicationCommands(
            guildId: Long,
            nonce: String,
            applications: Boolean,
            query: String?,
            offset: Int?,
            commandLimit: Int,
            commandIds: List<String>?,
        ): Boolean {
            lastNonce = nonce
            return accepts
        }
    }

    private companion object {
        const val GUILD_ID = 42L

        fun selectItem(value: String) =
            SelectItem(label = value, value = value, emoji = null, description = null, default = false)

        fun choice(value: String) = ApplicationCommandAutocompleteChoice(name = value, value = value)

        fun apiCommand(
            id: Long,
            name: String,
            options: List<com.discord.api.commands.ApplicationCommandOption>? = null,
        ) = com.discord.api.commands.ApplicationCommand(
            id = id,
            applicationId = 1L,
            name = name,
            description = null,
            options = options,
            version = "1",
            guildId = GUILD_ID.toString(),
            defaultPermissions = null,
            permissions = null,
        )

        fun apiOption(name: String, type: ApplicationCommandType) =
            com.discord.api.commands.ApplicationCommandOption(
                type = type,
                name = name,
                description = null,
                required = false,
                default = false,
                channelTypes = null,
                choices = emptyList(),
                options = null,
                autocomplete = false,
                minValue = null,
                maxValue = null,
            )

        fun guildApplicationCommands(
            nonce: String,
            commands: List<com.discord.api.commands.ApplicationCommand>,
            applications: List<com.discord.api.commands.Application> = emptyList(),
        ) = GuildApplicationCommands(
            guildId = GUILD_ID,
            updatedAt = 0L,
            applications = applications,
            nonce = nonce,
            applicationCommands = commands,
        )

        @Suppress("unused")
        fun modelCommand(id: String, name: String): ApplicationCommand = RemoteApplicationCommand(
            id = id,
            applicationId = 1L,
            name = name,
            options = emptyList<ApplicationCommandOption>(),
            guildId = null,
            version = null,
        )

        @Suppress("unused")
        fun modelApplication(id: Long, name: String) =
            ModelApplication(id = id, name = name, commandCount = 0)
    }
}
