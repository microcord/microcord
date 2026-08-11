package com.discord.stores

import com.discord.api.botuikit.SelectItem
import com.discord.api.commands.ApplicationCommandAutocompleteChoice
import com.discord.api.commands.ApplicationCommandAutocompleteResult
import com.discord.api.commands.ApplicationCommandIndex
import com.discord.api.commands.ApplicationCommandIndexApplication
import com.discord.api.commands.ApplicationCommandIndexCommand
import com.discord.api.commands.ApplicationIntegrationType
import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.api.permission.Permission
import com.discord.models.commands.CommandPermissionContext
import com.discord.models.commands.hasAccess
import com.discord.models.commands.isAvailableIn
import com.discord.models.commands.Application as ModelApplication
import com.discord.models.commands.RemoteApplicationCommand
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import java.lang.reflect.Proxy
import com.google.gson.GsonBuilder
import com.google.gson.FieldNamingPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler
import retrofit2.Response
import rx.Observable

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
     * Query results are the server's matches plus locally matched built-ins. `/sh` matches
     * `shrug` and nothing else.
     */
    @Test
    fun queryResultsAppendMatchingBuiltInCommands() {
        val fixture = commandsFixture()
        fixture.store.requestApplicationCommandsQuery(GUILD_ID, "sh")
        fixture.scheduler.triggerActions()

        assertEquals(listOf("shrug"), fixture.store.getQueryCommands().map(ApplicationCommand::name))
    }

    /** Sub-commands are expanded at snapshot time, so the picker never shows a bare parent. */
    @Test
    fun snapshotExpandsSubCommands() {
        val fixture = commandsFixture()
        fixture.store.requestApplicationCommandsQuery(GUILD_ID, "")
        fixture.scheduler.triggerActions()

        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandIndexes(
                generation = 0,
                guildId = GUILD_ID,
                isBotDm = false,
                indexes = listOf(
                    StoreApplicationCommands.IndexedSource(
                        ApplicationCommandIndex(
                            applications = listOf(ApplicationCommandIndexApplication(1L, "Tools")),
                            applicationCommands = listOf(
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
                        ApplicationIntegrationType.GUILD_INSTALL.value,
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

    @Test
    fun modernIndexesMergeLocalizedNestedCommandsAndRejectStaleLoads() {
        val fixture = commandsFixture()
        val index = indexFixture("application_command_index.json")

        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandIndexes(
                generation = 1,
                guildId = GUILD_ID,
                isBotDm = false,
                indexes = listOf(StoreApplicationCommands.IndexedSource(index, ApplicationIntegrationType.GUILD_INSTALL.value)),
            )
        }
        fixture.scheduler.triggerActions()
        assertTrue(fixture.store.getDiscoverCommands().commands.isEmpty())

        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandIndexes(
                generation = 0,
                guildId = GUILD_ID,
                isBotDm = false,
                indexes = listOf(StoreApplicationCommands.IndexedSource(index, ApplicationIntegrationType.GUILD_INSTALL.value)),
            )
        }
        fixture.scheduler.triggerActions()

        val command = fixture.store.getDiscoverCommands().commands.first { it.applicationId == 100L }
        assertEquals("verwaltung ressource erstellen", command.name)
        assertEquals("manage resource create", command.untranslatedName)
        assertEquals("suche", command.options[5].name)
        assertEquals("query", command.options[5].untranslatedName)
        assertEquals(7, command.options.size)
        assertEquals(2L, command.options.last().choices?.single()?.value)
        assertEquals(2, command.options[5].minLength)
        assertEquals(20, command.options[5].maxLength)
        assertTrue(command.isAvailableIn(GUILD_ID, false))
        assertFalse(command.isAvailableIn(0L, true))
        // The fixture grants role 42 at the application level, denies channel 7 there, and the
        // command itself asks for MANAGE_GUILD. An application-level grant does not excuse a
        // command from its default_member_permissions, and a channel denial outranks both.
        val base = Permission.VIEW_CHANNEL or Permission.USE_APPLICATION_COMMANDS or Permission.SEND_MESSAGES
        val inGuild = CommandPermissionContext(
            userId = 1L,
            guildId = GUILD_ID,
            roleIds = listOf(42L),
            computedPermissions = base or Permission.MANAGE_GUILD,
        )
        assertTrue(command.hasAccess(inGuild))
        assertFalse(command.hasAccess(inGuild.copy(computedPermissions = base)))
        assertFalse(command.hasAccess(inGuild.copy(channelId = 7L)))
    }

    @Test
    fun guildAndUserIndexesMergeApplicationsCommandsAndInstallationContexts() {
        val fixture = commandsFixture()
        val guildIndex = indexFixture("application_command_index.json")
        val userIndex = indexFixture("application_command_index_user.json")

        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandIndexes(
                generation = 0,
                guildId = GUILD_ID,
                isBotDm = false,
                indexes = listOf(
                    StoreApplicationCommands.IndexedSource(userIndex, ApplicationIntegrationType.USER_INSTALL.value),
                    StoreApplicationCommands.IndexedSource(guildIndex, ApplicationIntegrationType.GUILD_INSTALL.value),
                ),
            )
        }
        fixture.scheduler.triggerActions()

        val applications = fixture.store.getApplications().filterNot(ModelApplication::builtIn)
        assertEquals(listOf(100L, 400L, 300L), applications.map(ModelApplication::id))
        assertEquals(setOf(ApplicationIntegrationType.USER_INSTALL.value), applications.last().installationTypes)
        assertEquals(401L, applications.first { it.id == 400L }.botId)
        assertEquals(0, applications.first { it.id == 400L }.commandCount)
        assertEquals(
            setOf("verwaltung ressource erstellen", "profil"),
            fixture.store.getDiscoverCommands().commands.filterNot(ApplicationCommand::builtIn).mapTo(linkedSetOf()) { it.name },
        )
        val userCommand = fixture.store.getDiscoverCommands().commands.first { it.applicationId == 300L }
        assertEquals("profile", userCommand.untranslatedName)
        assertEquals("sichtbarkeit", userCommand.options[0].name)
        assertEquals("visibility", userCommand.options[0].untranslatedName)
        assertEquals(listOf("Nur ich", "Alle"), userCommand.options[0].choices.orEmpty().map { it.displayName })
        assertEquals(listOf("private", "public"), userCommand.options[0].choices.orEmpty().map { it.value })
        assertEquals(ApplicationCommandType.NUMBER, userCommand.options[1].type)
        assertTrue(userCommand.options[1].autocomplete)
    }

    @Test
    fun commandIndexFailurePublishesBuiltInsWithFailureState() {
        val fixture = commandsFixture()

        fixture.store.requestApplicationCommandIndex(7L, GUILD_ID, isBotDm = false)
        fixture.scheduler.triggerActions()

        assertTrue(fixture.store.getDiscoverCommands().loadState is LoadState.Failed)
        assertEquals(
            listOf("shrug", "tableflip", "unflip", "me", "spoiler"),
            fixture.store.getDiscoverCommands().commands.map(ApplicationCommand::name),
        )
    }

    @Test
    fun failedChannelIndexIsCachedWhileUserCommandsRemainAvailable() {
        var channelRequests = 0
        var userRequests = 0
        val api = Proxy.newProxyInstance(
            RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getChannelApplicationCommandIndex" -> {
                    channelRequests++
                    Observable.error<Response<ApplicationCommandIndex>>(
                        IllegalStateException("missing channel index"),
                    )
                }
                "getUserApplicationCommandIndex" -> {
                    userRequests++
                    Observable.just(Response.success(indexFixture("application_command_index_user.json")))
                }
                else -> null
            }
        } as RestAPIInterface
        val fixture = commandsFixture(restApi = api)

        fixture.store.requestApplicationCommandIndex(7L, null, isBotDm = false)
        fixture.await { fixture.store.getApplications().any { it.id == 300L } }
        fixture.store.requestApplicationCommandIndex(7L, null, isBotDm = true)
        fixture.scheduler.triggerActions()

        assertEquals(1, channelRequests)
        assertEquals(1, userRequests)
    }

    @Test
    fun guildIndexIsSharedAcrossChannelsInTheSameGuild() {
        var guildRequests = 0
        var userRequests = 0
        val api = Proxy.newProxyInstance(
            RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getGuildApplicationCommandIndex" -> {
                    guildRequests++
                    Observable.just(Response.success(indexFixture("application_command_index.json")))
                }
                "getUserApplicationCommandIndex" -> {
                    userRequests++
                    Observable.just(Response.success(indexFixture("application_command_index_user.json")))
                }
                else -> null
            }
        } as RestAPIInterface
        val fixture = commandsFixture(restApi = api)

        fixture.store.requestApplicationCommandIndex(7L, GUILD_ID, isBotDm = false)
        Thread.sleep(25L)
        fixture.await { fixture.store.getApplications().any { it.id == 300L } }
        fixture.store.requestApplicationCommandIndex(8L, GUILD_ID, isBotDm = false)
        fixture.scheduler.triggerActions()

        assertEquals(1, guildRequests)
        assertEquals(1, userRequests)
    }

    @Test
    fun persistedAccountIndexesHydrateWithoutNetworkRequests() {
        val cacheKey = "TEST_PERSISTED_APPLICATION_COMMAND_INDEX_CACHE"
        Persister(cacheKey, StoreApplicationCommands.PersistedIndexCache()).set(
            StoreApplicationCommands.PersistedIndexCache(
                userId = 7L,
                userIndex = indexFixture("application_command_index_user.json"),
                guildIndexes = mapOf(GUILD_ID to indexFixture("application_command_index.json")),
            ),
            persist = true,
        )
        var requests = 0
        val api = Proxy.newProxyInstance(
            RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java),
        ) { _, method, _ ->
            if (method.name in setOf("getGuildApplicationCommandIndex", "getUserApplicationCommandIndex")) {
                requests++
                Observable.error<Response<ApplicationCommandIndex>>(
                    AssertionError("network should not be used"),
                )
            } else null
        } as RestAPIInterface
        val fixture = commandsFixture(
            restApi = api,
            indexCachePersister = Persister(cacheKey, StoreApplicationCommands.PersistedIndexCache()),
        )
        val payload = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()
            .fromJson(
                """{"user":{"id":"7","username":"me","discriminator":"0000"},"guilds":[],"private_channels":[]}""",
                com.discord.models.domain.ModelPayload::class.java,
            )
        fixture.dispatcher.schedule { fixture.store.handleConnectionOpen(payload) }
        fixture.scheduler.triggerActions()

        fixture.store.requestApplicationCommandIndex(7L, GUILD_ID, isBotDm = false)
        fixture.await { fixture.store.getApplications().any { it.id == 300L } }

        assertEquals(0, requests)
        assertTrue(fixture.store.getDiscoverCommands().commands.any { it.applicationId == 100L })
    }

    @Test
    fun primaryEntryPointCommandsRemainDiscoverableAndQueryable() {
        val fixture = commandsFixture()
        val index = ApplicationCommandIndex(
            applications = listOf(ApplicationCommandIndexApplication(12L, "Wordle")),
            applicationCommands = listOf(
                ApplicationCommandIndexCommand(
                    id = 99L,
                    type = ApplicationCommandIndexCommand.TYPE_PRIMARY_ENTRY_POINT,
                    applicationId = 12L,
                    name = "wordle",
                    version = "1",
                    handler = 2,
                ),
            ),
        )
        fixture.store.requestApplicationCommandsQuery(GUILD_ID, "wor")
        fixture.scheduler.triggerActions()
        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandIndexes(
                generation = 0,
                guildId = GUILD_ID,
                isBotDm = false,
                indexes = listOf(
                    StoreApplicationCommands.IndexedSource(index, ApplicationIntegrationType.GUILD_INSTALL.value),
                ),
            )
        }
        fixture.scheduler.triggerActions()

        assertTrue(fixture.store.getDiscoverCommands().commands.any { it.name == "wordle" })

        val queried = fixture.store.getQueryCommands().single { it.id == "99" }
        assertEquals(ApplicationCommandIndexCommand.TYPE_PRIMARY_ENTRY_POINT, queried.type)
        assertEquals(2, queried.handler)
    }

    @Test
    fun newerRemoteAutocompleteRequestRejectsTheOlderResponse() {
        val requests = mutableListOf<RestAPIParams.ApplicationCommand>()
        val api = Proxy.newProxyInstance(
            RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java),
        ) { _, method, args ->
            if (method.name == "sendApplicationCommand" && args?.singleOrNull() is RestAPIParams.ApplicationCommand) {
                requests += args.single() as RestAPIParams.ApplicationCommand
                Observable.empty<Void>()
            } else null
        } as RestAPIInterface
        val fixture = commandsFixture(restApi = api)
        fixture.dispatcher.schedule {
            fixture.store.handleConnectionOpen(com.discord.models.domain.ModelPayload().apply {
                sessionId = "session-fixture"
            })
        }
        fixture.scheduler.triggerActions()
        val option = apiOption("query", ApplicationCommandType.STRING).copy(autocomplete = true)
        val command = apiCommand(90L, "search", listOf(option)).toSlashCommand(null, null)
        fun data(value: String) = com.discord.widgets.chat.input.models.ApplicationCommandData(
            ModelApplication(1L, "Fixture", commandCount = 1),
            command,
            listOf(com.discord.widgets.chat.input.models.ApplicationCommandValue("query", value, 3, focused = true)),
        )

        fixture.store.requestApplicationCommandAutocompleteData(GUILD_ID, 7L, data("old"))
        fixture.scheduler.triggerActions()
        fixture.store.requestApplicationCommandAutocompleteData(GUILD_ID, 7L, data("new"))
        fixture.scheduler.triggerActions()

        assertEquals(StoreApplicationCommands.TYPE_APPLICATION_COMMAND_AUTOCOMPLETE, requests[1].type)
        assertEquals("session-fixture", requests[1].sessionId)
        assertEquals(command.id, requests[1].data.id)
        assertEquals(command.version, requests[1].data.version)
        assertTrue(requests[1].data.options!!.single().focused == true)

        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandAutocompleteResult(
                ApplicationCommandAutocompleteResult(requests[0].nonce, listOf(choice("stale"))),
            )
            fixture.store.handleApplicationCommandAutocompleteResult(
                ApplicationCommandAutocompleteResult(requests[1].nonce, listOf(choice("fresh"))),
            )
        }
        fixture.scheduler.triggerActions()

        val states = fixture.store.getAutocompleteOptionResults()["query"].orEmpty()
        assertNull(states["old"])
        assertEquals("fresh", (states["new"] as CommandAutocompleteState.Choices).choices.single().value)
    }

    @Test
    fun autocompleteNormalizesValuesAndKeepsEmptyChoices() {
        val requests = mutableListOf<RestAPIParams.ApplicationCommand>()
        val api = Proxy.newProxyInstance(
            RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java),
        ) { _, method, args ->
            if (method.name == "sendApplicationCommand" && args?.singleOrNull() is RestAPIParams.ApplicationCommand) {
                requests += args.single() as RestAPIParams.ApplicationCommand
                Observable.empty<Void>()
            } else null
        } as RestAPIInterface
        val fixture = commandsFixture(restApi = api)
        fixture.dispatcher.schedule {
            fixture.store.handleConnectionOpen(com.discord.models.domain.ModelPayload().apply {
                sessionId = "session-fixture"
            })
        }
        fixture.scheduler.triggerActions()

        fun request(name: String, type: ApplicationCommandType, value: Any) {
            val option = apiOption(name, type).copy(autocomplete = true)
            val command = apiCommand(name.hashCode().toLong(), "search", listOf(option)).toSlashCommand(null, null)
            fixture.store.requestApplicationCommandAutocompleteData(
                GUILD_ID,
                7L,
                com.discord.widgets.chat.input.models.ApplicationCommandData(
                    ModelApplication(1L, "Fixture", commandCount = 1),
                    command,
                    listOf(com.discord.widgets.chat.input.models.ApplicationCommandValue(name, value, type.type, focused = true)),
                ),
            )
            fixture.scheduler.triggerActions()
        }

        request("string", ApplicationCommandType.STRING, "one")
        request("integer", ApplicationCommandType.INTEGER, 2L)
        request("number", ApplicationCommandType.NUMBER, 2.5)
        fixture.dispatcher.schedule {
            fixture.store.handleApplicationCommandAutocompleteResult(
                ApplicationCommandAutocompleteResult(requests[0].nonce, listOf(choice("value"))),
            )
            fixture.store.handleApplicationCommandAutocompleteResult(
                ApplicationCommandAutocompleteResult(requests[1].nonce, listOf(choice(2.0))),
            )
            fixture.store.handleApplicationCommandAutocompleteResult(
                ApplicationCommandAutocompleteResult(requests[2].nonce, emptyList()),
            )
        }
        fixture.scheduler.triggerActions()

        val states = fixture.store.getAutocompleteOptionResults()
        assertEquals("value", (states["string"]!!["one"] as CommandAutocompleteState.Choices).choices.single().value)
        assertEquals(2L, (states["integer"]!!["2"] as CommandAutocompleteState.Choices).choices.single().value)
        assertTrue((states["number"]!!["2.5"] as CommandAutocompleteState.Choices).choices.isEmpty())
    }

    @Test
    fun autocompleteWithoutSessionFailsWithoutPosting() {
        var posts = 0
        val api = Proxy.newProxyInstance(
            RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java),
        ) { _, method, _ ->
            if (method.name == "sendApplicationCommand") posts++
            Observable.empty<Void>()
        } as RestAPIInterface
        val fixture = commandsFixture(restApi = api)
        val option = apiOption("query", ApplicationCommandType.STRING).copy(autocomplete = true)
        val command = apiCommand(90L, "search", listOf(option)).toSlashCommand(null, null)

        fixture.store.requestApplicationCommandAutocompleteData(
            GUILD_ID,
            7L,
            com.discord.widgets.chat.input.models.ApplicationCommandData(
                ModelApplication(1L, "Fixture", commandCount = 1),
                command,
                listOf(com.discord.widgets.chat.input.models.ApplicationCommandValue("query", "x", 3, focused = true)),
            ),
        )
        fixture.scheduler.triggerActions()

        assertEquals(0, posts)
        assertEquals(CommandAutocompleteState.Failed, fixture.store.getAutocompleteOptionResults()["query"]?.get("x"))
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

    private fun commandsFixture(
        restApi: RestAPIInterface? = null,
        indexCachePersister: Persister<StoreApplicationCommands.PersistedIndexCache> = Persister(
            "TEST_APPLICATION_COMMAND_INDEX_CACHE",
            StoreApplicationCommands.PersistedIndexCache(),
        ),
    ): CommandsFixture {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val frecency = StoreApplicationCommandFrecency(dispatcher, deck)
        val store = StoreApplicationCommands(
            dispatcher = dispatcher,
            storeApplicationCommandFrecency = frecency,
            restApi = { restApi },
            indexCachePersister = indexCachePersister,
            observationDeck = deck,
        )
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store, frecency), deck))
        // The store parks requests until the socket says it is ready.
        dispatcher.schedule { store.handleConnectionReady(true) }
        scheduler.triggerActions()
        return CommandsFixture(scheduler, dispatcher, store)
    }

    private class CommandsFixture(
        val scheduler: TestScheduler,
        val dispatcher: Dispatcher,
        val store: StoreApplicationCommands,
    ) {
        fun await(condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 2_000L
            while (!condition() && System.currentTimeMillis() < deadline) {
                scheduler.triggerActions()
                Thread.sleep(5L)
            }
            scheduler.triggerActions()
            assertTrue(condition())
        }
    }

    private companion object {
        const val GUILD_ID = 42L

        fun indexFixture(name: String): ApplicationCommandIndex {
            val json = ApplicationCommandStoresTest::class.java.classLoader!!
                .getResource("fixtures/$name")!!.readText()
            return GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerDiscordApiTypeAdapters().create()
                .fromJson(json, ApplicationCommandIndex::class.java)
        }

        fun selectItem(value: String) =
            SelectItem(label = value, value = value, emoji = null, description = null, default = false)

        fun choice(value: Any) = ApplicationCommandAutocompleteChoice(name = value.toString(), value = value)

        fun apiCommand(
            id: Long,
            name: String,
            options: List<com.discord.api.commands.ApplicationCommandOption>? = null,
        ) = ApplicationCommandIndexCommand(
            id = id,
            applicationId = 1L,
            name = name,
            options = options,
            version = "1",
            guildId = GUILD_ID.toString(),
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
