package com.discord.stores

import com.discord.R
import com.discord.api.commands.ApplicationCommandAutocompleteChoice
import com.discord.api.commands.ApplicationCommandAutocompleteResult
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.GuildApplicationCommands
import com.discord.api.permission.Permission
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.ApplicationSubCommand
import com.discord.models.commands.BuiltInCommand
import com.discord.models.commands.RemoteApplicationCommand
import com.discord.models.commands.flatten
import com.discord.models.commands.toRestParams
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.NonceGenerator
import com.discord.models.user.User
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.user.UserUtils.synthesizeApiUser
import rx.Observable

/**
 * Everything the slash-command surface reads: which applications a guild exposes, the paged
 * "discover" list, the current query results, the frequently-used shortlist, and per-argument
 * autocomplete choices.
 *
 * Five independent [ObservationDeck.UpdateSource]s rather than one, because those five surfaces
 * change at wildly different rates — an autocomplete keystroke must not invalidate the application
 * rail — and [snapshotData] copies only what actually changed.
 *
 * **The paging model is the subtle part.** The gateway answers a command request with a *window*
 * into the guild's flat command list, described by [currentStartOffset]/[currentEndOffset] and the
 * per-application starting indexes in [applicationCommandIndexes]. Three request shapes land in the
 * same handler and are told apart by [loadDirectionUp]/[loadDirectionDown]/[jumpedApplicationId]:
 *
 * - a **jump** to an application replaces the window outright and re-anchors the start offset,
 * - a **load-up** prepends the newly arrived commands and shifts the start offset back by however
 *   many were actually new,
 * - a **load-down** appends them,
 * - and a plain load replaces everything from offset zero.
 *
 * Built-in commands are appended only when the window reaches the end of the remote list, which is
 * why [numRemoteCommands] excludes them.
 *
 * This file also carries the declarations the reference kept in `StoreApplicationCommands.kt`
 * ([LoadState], [DiscoverCommands], [CommandAutocompleteState], [CommandOptionAutocompleteQuery],
 * [BuiltInCommandsProvider], [BuiltInCommands]) and the conversion helpers it kept in
 * `StoreApplicationCommandsKt`.
 */
@Suppress("TooManyFunctions", "LargeClass")
class StoreApplicationCommands(
    private val dispatcher: Dispatcher,
    private val storeApplicationCommandFrecency: StoreApplicationCommandFrecency,
    /**
     * `StoreGatewayConnection::requestApplicationCommands`.
     *
     * A function seam rather than the store itself: `StoreGatewayConnection` takes `StoreStream` in
     * its constructor, so depending on it directly would make this store impossible to exercise
     * without standing up the whole graph. Returns false when there is no established session,
     * which is what drives the built-ins-only fallback.
     */
    private val gatewayRequest: ApplicationCommandRequester,
    /**
     * `null` while `StorePermissions` is not wired. Every channel is then treated as unrestricted,
     * which is the same answer the store gives for a DM.
     */
    private val storePermissions: StorePermissions? = null,
    /**
     * `null` while `StoreExperiments` is not wired. The frecency shortlist is then always empty,
     * matching an un-enrolled user.
     */
    private val storeExperiments: StoreExperiments? = null,
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val builtInCommandsProvider: BuiltInCommandsProvider = BuiltInCommands(),
    private val nonceGenerator: NonceGenerator = NonceGenerator(),
) : StoreV2() {

    /** The one thing this store asks the socket to do. */
    fun interface ApplicationCommandRequester {
        @Suppress("LongParameterList")
        fun requestApplicationCommands(
            guildId: Long,
            nonce: String,
            applications: Boolean,
            query: String?,
            offset: Int?,
            commandLimit: Int,
            commandIds: List<String>?,
        ): Boolean
    }

    // -- Applications --------------------------------------------------------------------------

    private var applications: List<Application> = emptyList()
    private var applicationsSnapshot: List<Application> = emptyList()
    private var applicationsMapSnapshot: Map<Long, Application> = emptyMap()

    /** Application id → its first index in the guild's flat command list. */
    private var applicationCommandIndexes: MutableMap<Long, Int> = LinkedHashMap()

    /** Commands that came from a real application; built-ins are excluded so paging maths works. */
    private var numRemoteCommands: Int = 0

    // -- Discover list -------------------------------------------------------------------------

    private var discoverCommands: DiscoverCommands = DiscoverCommands.defaultModelDiscoveryCommands()
    private var discoverCommandsSnapshot: DiscoverCommands = DiscoverCommands.defaultModelDiscoveryCommands()
    private var discoverGuildId: Long? = null
    private var discoverApplicationId: Long? = null
    private var isLoadingDiscoveryCommands: Boolean = false
    private var currentStartOffset: Int = 0
    private var currentEndOffset: Int = 0
    private var loadDirectionUp: Boolean = false
    private var loadDirectionDown: Boolean = false
    private var jumpedApplicationId: Long? = null

    /** Bumped on every jump so the list can distinguish "same target, asked again" from a no-op. */
    private var jumpedSequenceId: Int = 0

    // -- Query ---------------------------------------------------------------------------------

    private val queryCommands = ArrayList<ApplicationCommand>()
    private var queryCommandsSnapshot: List<ApplicationCommand> = emptyList()
    private var queryGuildId: Long? = null
    private var query: String? = null

    // -- Frecency ------------------------------------------------------------------------------

    private var frecencyCommands: MutableMap<Long, List<ApplicationCommand>> = LinkedHashMap()
    private var frecencyCommandsSnapshot: Map<Long, List<ApplicationCommand>> = emptyMap()
    private var frecencyRequests: MutableMap<String, FrecencyRequest> = LinkedHashMap()

    // -- Autocomplete --------------------------------------------------------------------------

    private var autocompleteNonceData: MutableMap<String, CommandOptionAutocompleteQuery> = LinkedHashMap()
    private var autocompleteOptionResults:
        MutableMap<String, MutableMap<String, CommandAutocompleteState>> = LinkedHashMap()
    private var autocompleteOptionResultsSnapshot: Map<String, Map<String, CommandAutocompleteState>> =
        emptyMap()

    // -- Connection ----------------------------------------------------------------------------

    private var sessionId: String? = null
    private var connectionReady: Boolean = false
    private var initialApplicationsRequested: Boolean = false
    private var pendingGatewayGuildId: Long? = null
    private var pendingBotUser: User? = null

    /** Nonces are correlated per request kind; only one of these matches an inbound payload. */
    private var applicationNonce: String? = null
    private var queryNonce: String? = null
    private var discoverCommandsNonce: String? = null

    /** A frecency request, remembered so the response can be re-ordered back into rank order. */
    data class FrecencyRequest(val guildId: Long?, val applicationCommandIds: List<String>)

    // -- Reads ---------------------------------------------------------------------------------

    fun getApplications(): List<Application> = applicationsSnapshot

    fun getApplicationMap(): Map<Long, Application> = applicationsMapSnapshot

    fun getDiscoverCommands(): DiscoverCommands = discoverCommandsSnapshot

    fun getQueryCommands(): List<ApplicationCommand> = queryCommandsSnapshot

    fun getFrecencyCommands(guildId: Long): List<ApplicationCommand> =
        frecencyCommandsSnapshot[guildId].orEmpty()

    fun getAutocompleteOptionResults(): Map<String, Map<String, CommandAutocompleteState>> =
        autocompleteOptionResultsSnapshot

    /** True when [applicationId]'s slice of the flat list is inside the loaded window. */
    fun hasFetchedApplicationCommands(applicationId: Long): Boolean {
        val index = applicationCommandIndexes[applicationId] ?: return false
        return currentStartOffset <= index && currentEndOffset >= index
    }

    // -- Observation ---------------------------------------------------------------------------

    /**
     * The discover list, with a permission veto applied *after* the fact: a member without
     * `USE_APPLICATION_COMMANDS` in the channel still sees the built-ins, since those never reach
     * the server.
     */
    fun observeDiscoverCommands(channelId: Long): Observable<DiscoverCommands> = Observable.combineLatest(
        observationDeck.connectRx(DiscoverCommandsUpdate) { getDiscoverCommands() }.distinctUntilChanged(),
        storePermissions?.observePermissionsForChannel(channelId) ?: Observable.just<Long?>(null),
    ) { discoveryCommands, channelPermissions ->
        if (shouldReturnApplicationCommands(channelPermissions)) {
            discoveryCommands
        } else {
            val builtIns = builtInCommandsProvider.getBuiltInCommands()
            DiscoverCommands(builtIns, 0, builtIns.size, false, false, 0, null)
        }
    }

    fun observeGuildApplications(channelId: Long): Observable<List<Application>> =
        observationDeck.connectRx(*sourcesWith(GuildApplicationsUpdate, storePermissions)) {
            if (shouldReturnApplicationCommands(channelPermissions(channelId))) {
                getApplications()
            } else {
                getApplications().filter(Application::builtIn)
            }
        }

    fun observeQueryCommands(channelId: Long): Observable<List<ApplicationCommand>> =
        observationDeck.connectRx(*sourcesWith(QueryCommandsUpdate, storePermissions)) {
            if (shouldReturnApplicationCommands(channelPermissions(channelId))) {
                getQueryCommands()
            } else {
                getQueryCommands().filter(ApplicationCommand::builtIn)
            }
        }.distinctUntilChanged()

    /**
     * The frequently-used shortlist, capped at five.
     *
     * Gated on the `2021-09_android_app_commands_frecency` experiment exactly as the reference:
     * outside bucket 1 the list is empty and the picker shows no "Frequently Used" application.
     */
    fun observeFrecencyCommands(guildId: Long): Observable<List<ApplicationCommand>> =
        observationDeck.connectRx(*sourcesWith(FrecencyCommandsUpdate, storeExperiments)) {
            val experiment = storeExperiments?.getUserExperiment(FRECENCY_EXPERIMENT_NAME, false)
            if (experiment?.bucket == FRECENCY_EXPERIMENT_BUCKET) {
                getFrecencyCommands(guildId).take(FRECENCY_LIMIT)
            } else {
                emptyList()
            }
        }.startWith(emptyList<ApplicationCommand>())

    fun observeAutocompleteResults(): Observable<Map<String, Map<String, CommandAutocompleteState>>> =
        observationDeck.connectRx(AutocompleteResultsUpdate) { getAutocompleteOptionResults() }
            .startWith(emptyMap<String, Map<String, CommandAutocompleteState>>())

    // -- Connection lifecycle ------------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        sessionId = payload.sessionId
    }

    /**
     * Flushes whatever was requested while the socket was down.
     *
     * [initialApplicationsRequested] latches for the lifetime of the store, so this only ever runs
     * once — a reconnect does not re-request, matching the reference.
     */
    @StoreThread
    fun handleConnectionReady(connectionReady: Boolean) {
        this.connectionReady = connectionReady
        if (!connectionReady || initialApplicationsRequested) return
        requestFrecencyCommands(pendingGatewayGuildId ?: 0L)
        pendingGatewayGuildId?.let(::requestApplications)
        pendingBotUser?.let(::handleDmUserApplication)
        if (pendingGatewayGuildId == null && pendingBotUser == null) requestApplications(null)
        initialApplicationsRequested = true
        pendingGatewayGuildId = null
        pendingBotUser = null
    }

    @StoreThread
    fun handlePreLogout() {
        applications = emptyList()
        applicationCommandIndexes.clear()
        numRemoteCommands = 0
        discoverCommands = DiscoverCommands.defaultModelDiscoveryCommands()
        discoverGuildId = null
        discoverApplicationId = null
        isLoadingDiscoveryCommands = false
        currentStartOffset = 0
        currentEndOffset = 0
        loadDirectionUp = false
        loadDirectionDown = false
        jumpedApplicationId = null
        queryCommands.clear()
        queryGuildId = null
        query = null
        frecencyCommands.clear()
        frecencyRequests.clear()
        autocompleteNonceData.clear()
        autocompleteOptionResults.clear()
        connectionReady = false
        initialApplicationsRequested = false
        pendingGatewayGuildId = null
        pendingBotUser = null
        sessionId = null
        markChanged(
            GuildApplicationsUpdate,
            DiscoverCommandsUpdate,
            QueryCommandsUpdate,
            FrecencyCommandsUpdate,
            AutocompleteResultsUpdate,
        )
    }

    // -- Gateway handlers ----------------------------------------------------------------------

    /**
     * `GUILD_APPLICATION_COMMANDS_UPDATE`, routed by nonce.
     *
     * The frecency check comes first because a frecency response carries commands *and* would
     * otherwise match no nonce at all.
     */
    @StoreThread
    fun handleApplicationCommandsUpdate(commandsGateway: GuildApplicationCommands) {
        val nonce = commandsGateway.nonce
        if (frecencyRequests.containsKey(nonce)) {
            handleFrecencyCommandsUpdate(nonce, commandsGateway.applicationCommands.map { it.toSlashCommand() })
            return
        }
        when (nonce) {
            applicationNonce -> {
                val remote = commandsGateway.applications
                    .map { it.toDomainApplication() }
                    .sortedBy(Application::name)
                handleGuildApplicationsUpdate(
                    listOf(builtInCommandsProvider.getFrecencyApplication()) +
                        remote +
                        builtInCommandsProvider.getBuiltInApplication(),
                )
            }

            queryNonce ->
                handleQueryCommandsUpdate(commandsGateway.applicationCommands.map { it.toSlashCommand() })

            discoverCommandsNonce ->
                handleDiscoverCommandsUpdate(commandsGateway.applicationCommands.map { it.toSlashCommand() })
        }
    }

    /**
     * `APPLICATION_COMMAND_AUTOCOMPLETE_RESPONSE`.
     *
     * A response whose nonce is no longer tracked is dropped: the user has moved on to another
     * argument and the choices would be for the wrong one.
     */
    @StoreThread
    fun handleApplicationCommandAutocompleteResult(autocompleteResult: ApplicationCommandAutocompleteResult) {
        val queryData = autocompleteNonceData[autocompleteResult.nonce] ?: return
        val forOption = LinkedHashMap<String, CommandAutocompleteState>()
        forOption.putAll(autocompleteOptionResults[queryData.commandOptionName].orEmpty())
        forOption[queryData.queryString] = CommandAutocompleteState.Choices(autocompleteResult.choices)
        autocompleteOptionResults[queryData.commandOptionName] = forOption
        markChanged(AutocompleteResultsUpdate)
    }

    // -- Requests ------------------------------------------------------------------------------

    /**
     * Asks for the applications available in [guildId] (`null` for a DM).
     *
     * Before the socket is ready the guild is parked in [pendingGatewayGuildId] and replayed by
     * [handleConnectionReady]; without that a channel selected during connect would show an empty
     * command picker forever.
     */
    fun requestApplications(guildId: Long?) {
        if (!connectionReady) {
            pendingGatewayGuildId = guildId
            return
        }
        dispatcher.schedule {
            val nonce = generateNonce()
            applicationNonce = nonce
            applications = emptyList()
            applicationCommandIndexes.clear()
            markChanged(GuildApplicationsUpdate)
            if (guildId != null && guildId > 0L) {
                gatewayRequest.requestApplicationCommands(
                    guildId = guildId,
                    nonce = nonce,
                    applications = true,
                    query = null,
                    offset = null,
                    commandLimit = COMMANDS_LIMIT_PER_REQUEST,
                    commandIds = null,
                )
            } else {
                handleGuildApplicationsUpdate(
                    listOf(
                        builtInCommandsProvider.getFrecencyApplication(),
                        builtInCommandsProvider.getBuiltInApplication(),
                    ),
                )
            }
        }
    }

    /** A DM with a bot exposes exactly one application: the bot itself. */
    fun handleDmUserApplication(botUser: User) {
        if (!botUser.isBot) return
        if (!connectionReady) {
            pendingBotUser = botUser
            return
        }
        dispatcher.schedule {
            frecencyCommands[0L] = emptyList()
            pendingGatewayGuildId = null
            applications = emptyList()
            applicationCommandIndexes.clear()
            handleGuildApplicationsUpdate(
                listOf(
                    builtInCommandsProvider.getFrecencyApplication(),
                    Application(
                        id = botUser.id,
                        name = botUser.username,
                        commandCount = 0,
                        bot = botUser.synthesizeApiUser(),
                    ),
                    builtInCommandsProvider.getBuiltInApplication(),
                ),
            )
            markChanged(FrecencyCommandsUpdate)
        }
    }

    fun requestApplicationCommandsQuery(guildId: Long?, query: String) = dispatcher.schedule {
        val nonce = generateNonce()
        queryNonce = nonce
        if (queryGuildId != guildId) {
            queryCommands.clear()
            markChanged(QueryCommandsUpdate)
        }
        queryGuildId = guildId
        this.query = query
        if (guildId != null && guildId > 0L) {
            gatewayRequest.requestApplicationCommands(
                guildId = guildId,
                nonce = nonce,
                applications = false,
                query = query,
                offset = null,
                commandLimit = COMMANDS_LIMIT_PER_REQUEST,
                commandIds = null,
            )
        } else {
            // A DM has no server-side command search; only built-ins can match.
            handleQueryCommandsUpdate(emptyList())
        }
    }

    fun clearQueryCommands() = dispatcher.schedule {
        queryCommands.clear()
        markChanged(QueryCommandsUpdate)
    }

    fun clearAutocompleteResults() = dispatcher.schedule {
        autocompleteOptionResults.clear()
        markChanged(AutocompleteResultsUpdate)
    }

    /**
     * Opens the discover list, optionally anchored at [applicationId].
     *
     * [forDmDiscover] suppresses the anchor: a DM's list is short enough to show whole, so jumping
     * inside it would only scroll away from the top.
     */
    @JvmOverloads
    fun requestInitialApplicationCommands(
        guildId: Long?,
        applicationId: Long?,
        forDmDiscover: Boolean = false,
    ) {
        var offset = 0
        loadDirectionUp = false
        loadDirectionDown = false
        if (!forDmDiscover) jumpedApplicationId = applicationId
        if (jumpedApplicationId != null) jumpedSequenceId++
        if (applicationId != null) {
            // Only `commands` is reset — the offsets are re-anchored just below and the jump
            // bookkeeping must survive.
            discoverCommands = discoverCommands.copy(commands = emptyList())
            applicationCommandIndexes[applicationId]?.let { index ->
                offset = index
                currentStartOffset = index
                currentEndOffset = index
            }
            if (applicationId == builtInCommandsProvider.getBuiltInApplication().id) {
                // The built-in application never round-trips through the gateway.
                dispatcher.schedule {
                    handleDiscoverCommandsUpdate(builtInCommandsProvider.getBuiltInCommands())
                }
                return
            }
        }
        requestApplicationCommands(guildId, offset, applicationId, COMMANDS_LIMIT_PER_REQUEST)
    }

    fun requestDiscoverCommands(applicationId: Long) {
        val guildId = discoverGuildId ?: return
        requestInitialApplicationCommands(guildId, applicationId)
    }

    fun requestLoadMoreDown() {
        val guildId = discoverGuildId ?: return
        requestApplicationCommands(guildId, currentEndOffset + 1, null, COMMANDS_LIMIT_PER_REQUEST)
        loadDirectionUp = false
        loadDirectionDown = true
        jumpedApplicationId = null
        discoverCommands = discoverCommands.copy(loadState = LoadState.LoadingDown)
    }

    /**
     * Walks the window back one page.
     *
     * When fewer than a page remain before the start, the offset clamps to zero and the *limit*
     * shrinks to what is left, so the request never overlaps the already-loaded region.
     */
    fun requestLoadMoreUp() {
        val guildId = discoverGuildId ?: return
        val offset: Int
        val limit: Int
        if (currentStartOffset - COMMANDS_LIMIT_PER_REQUEST < 0) {
            offset = 0
            limit = currentStartOffset
        } else {
            offset = currentStartOffset - COMMANDS_LIMIT_PER_REQUEST
            limit = COMMANDS_LIMIT_PER_REQUEST
        }
        requestApplicationCommands(guildId, offset, null, limit)
        loadDirectionUp = true
        loadDirectionDown = false
        jumpedApplicationId = null
        discoverCommands = discoverCommands.copy(loadState = LoadState.LoadingUp)
    }

    /**
     * Re-requests the commands behind the frecency shortlist so their full definitions are known.
     *
     * Only numeric, positive ids are asked for: a negative id belongs to a built-in command and a
     * non-numeric one to a sub-command, and neither exists server-side. If nothing survives that
     * filter — or the socket rejects the request — the shortlist is resolved to empty rather than
     * left pending.
     */
    fun requestFrecencyCommands(guildId: Long) {
        val topCommandIds = storeApplicationCommandFrecency.getTopCommandIds(guildId)
        val nonce = generateNonce()
        val request = FrecencyRequest(guildId, topCommandIds)
        if (frecencyRequests.containsValue(request)) return
        frecencyRequests[nonce] = request

        val remoteIds = topCommandIds
            .map { it.substringBefore(' ') }
            .filter { (it.toLongOrNull() ?: 0L) > 0L }
            .distinct()

        val requested = remoteIds.isNotEmpty() &&
            gatewayRequest.requestApplicationCommands(
                guildId = guildId,
                nonce = nonce,
                applications = false,
                query = null,
                offset = null,
                commandLimit = COMMANDS_LIMIT_PER_REQUEST,
                commandIds = remoteIds,
            )
        if (!requested) dispatcher.schedule { handleFrecencyCommandsUpdate(nonce, emptyList()) }
    }

    /**
     * Asks the application to autocomplete the focused argument of [data].
     *
     * Nothing happens when no argument is focused — there would be nothing to complete. The
     * request nonce is recorded against the argument so a late response for a stale argument can be
     * discarded in [handleApplicationCommandAutocompleteResult].
     */
    fun requestApplicationCommandAutocompleteData(
        guildId: Long?,
        channelId: Long,
        data: com.discord.widgets.chat.input.models.ApplicationCommandData,
    ) = dispatcher.schedule {
        val nonce = generateNonce()
        val focused = data.values.flatten().firstOrNull { it.focused == true } ?: return@schedule
        val commandData = com.discord.api.commands.ApplicationCommandData(
            version = data.applicationCommand.version,
            guildId = data.applicationCommand.guildId?.toString(),
            id = data.applicationCommand.id,
            name = data.applicationCommand.name,
            options = data.values.toRestParams { it },
            attachments = null,
        )
        val body = RestAPIParams.ApplicationCommand(
            type = TYPE_APPLICATION_COMMAND_AUTOCOMPLETE,
            channelId = channelId.toString(),
            applicationId = data.application.id.toString(),
            guildId = guildId?.toString(),
            data = commandData,
            sessionId = sessionId,
            nonce = nonce,
        )
        autocompleteNonceData[nonce] =
            CommandOptionAutocompleteQuery(focused.value.toString(), focused.name)
        val api = restApi()
        if (api == null) {
            setAutocompleteFailed(focused.name, focused.value.toString())
            return@schedule
        }
        api.sendApplicationCommand(body)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreApplicationCommands::class.java,
                errorHandler = {
                    dispatcher.schedule {
                        setAutocompleteFailed(focused.name, focused.value.toString())
                    }
                },
            ) { }
    }

    @StoreThread
    fun setAutocompleteLoading(commandOptionName: String, queryString: String) =
        setAutocompleteState(commandOptionName, queryString, CommandAutocompleteState.Loading)

    @StoreThread
    fun setAutocompleteFailed(commandOptionName: String, queryString: String) =
        setAutocompleteState(commandOptionName, queryString, CommandAutocompleteState.Failed)

    // -- Internal state transitions ------------------------------------------------------------

    /**
     * Issues one page request against the gateway, or falls back to REST for a DM bot.
     *
     * De-duplicated on [isLoadingDiscoveryCommands]: paging is scroll-driven and would otherwise
     * fire several overlapping requests per fling. Switching guild clears the list; staying in the
     * same guild keeps it and only updates the load state, which is what makes "load more" append
     * rather than flash empty.
     */
    private fun requestApplicationCommands(
        guildId: Long?,
        offset: Int = 0,
        applicationId: Long? = null,
        limit: Int = COMMANDS_LIMIT_PER_REQUEST,
    ) = dispatcher.schedule {
        if (isLoadingDiscoveryCommands) return@schedule
        val nonce = generateNonce()
        discoverCommandsNonce = nonce
        val loadState =
            if (loadDirectionDown || loadDirectionUp) discoverCommands.loadState else LoadState.Loading

        discoverCommands = if (discoverGuildId != guildId) {
            discoverCommands.copy(commands = emptyList(), loadState = loadState)
        } else {
            discoverCommands.copy(loadState = loadState)
        }
        markChanged(DiscoverCommandsUpdate)

        discoverGuildId = guildId
        when {
            guildId != null && guildId > 0L -> {
                val requested = gatewayRequest.requestApplicationCommands(
                    guildId = guildId,
                    nonce = nonce,
                    applications = false,
                    query = null,
                    offset = offset,
                    commandLimit = limit,
                    commandIds = null,
                )
                isLoadingDiscoveryCommands = requested
                if (!requested) handleDiscoverCommandsUpdate(builtInCommandsProvider.getBuiltInCommands())
            }

            applicationId != null -> {
                if (applicationId != discoverApplicationId) {
                    isLoadingDiscoveryCommands = true
                    getApplicationCommandsViaRest(applicationId)
                }
            }

            else -> handleDiscoverCommandsUpdate(builtInCommandsProvider.getBuiltInCommands())
        }
        discoverApplicationId = applicationId
    }

    /**
     * DM fallback: a bot's commands are not reachable over the gateway, so they come from
     * `GET /applications/{id}/commands`.
     *
     * The response rewrites the bot's entry in [applications] with the real command count and — a
     * reference quirk worth knowing — with the *application id carried by the first command*, which
     * is the same value in practice but is what gets stored.
     */
    private fun getApplicationCommandsViaRest(applicationId: Long) {
        val api = restApi()
        if (api == null) {
            dispatcher.schedule { isLoadingDiscoveryCommands = false }
            return
        }
        api.getApplicationCommands(applicationId)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreApplicationCommands::class.java,
                errorHandler = { dispatcher.schedule { isLoadingDiscoveryCommands = false } },
            ) { commands ->
                dispatcher.schedule { handleRestApplicationCommands(applicationId, commands) }
            }
    }

    @StoreThread
    private fun handleRestApplicationCommands(
        applicationId: Long,
        commands: List<com.discord.api.commands.ApplicationCommand>?,
    ) {
        discoverApplicationId = null
        isLoadingDiscoveryCommands = false
        val index = applications.indexOfFirst { it.id == applicationId }
        if (index == -1) return

        val commandCount = commands?.size ?: 0
        val updated = ArrayList(applications)
        val previous = updated.removeAt(index)
        if (commandCount > 0) {
            val first = commands?.firstOrNull() ?: return
            updated.add(
                index,
                Application(
                    id = first.applicationId,
                    name = previous.name,
                    icon = previous.icon,
                    commandCount = commandCount,
                    bot = previous.bot,
                    builtIn = previous.builtIn,
                ),
            )
        }
        applications = updated

        val slashCommands = commands?.map { it.toSlashCommand() }.orEmpty()
        handleGuildApplicationsUpdate(applications.toList())

        // Resolve the DM frecency shortlist locally; there is no gateway round-trip to do it.
        val allCommands = slashCommands + builtInCommandsProvider.getBuiltInCommands()
        val topIds = storeApplicationCommandFrecency.getTopCommandIds(0L)
            .filter { id ->
                val numeric = id.toIntOrNull() ?: 0
                numeric < 0 || slashCommands.any { it.id == id }
            }
        frecencyCommands[0L] = topIds.mapNotNull { id -> allCommands.firstOrNull { it.id == id } }
        markChanged(FrecencyCommandsUpdate)

        handleDiscoverCommandsUpdate(slashCommands)
        handleQueryCommandsUpdate(slashCommands + builtInCommandsProvider.getBuiltInCommands())
    }

    @StoreThread
    private fun handleGuildApplicationsUpdate(commands: List<Application>) {
        applications = commands
        numRemoteCommands = 0
        var index = 0
        commands.forEach { application ->
            applicationCommandIndexes[application.id] = index
            index += application.commandCount
            if (!application.builtIn) numRemoteCommands += application.commandCount
        }
        markChanged(GuildApplicationsUpdate)
    }

    /**
     * Merges an incoming page into the discover window. See the class doc for the three shapes.
     *
     * The built-in commands are appended only when the window's far edge reaches the end of the
     * remote list, so they always sit last and are never duplicated by a subsequent page.
     */
    @StoreThread
    private fun handleDiscoverCommandsUpdate(commands: List<ApplicationCommand>) {
        isLoadingDiscoveryCommands = false
        val jumpedTo = jumpedApplicationId
        val existing = discoverCommands.commands.toMutableList()
        val previousSize = existing.size

        val merged: MutableList<ApplicationCommand> = when {
            jumpedTo != null -> {
                currentStartOffset = applicationCommandIndexes[jumpedTo] ?: 0
                commands.toMutableList()
            }

            loadDirectionUp -> {
                val existingIds = existing.map(ApplicationCommand::id)
                val newOnes = commands.filterNot { it.id in existingIds }
                newOnes.asReversed().forEach { existing.add(0, it) }
                currentStartOffset -= existing.size - previousSize
                existing
            }

            loadDirectionDown -> {
                val existingIds = existing.map(ApplicationCommand::id)
                (existing + commands.filterNot { it.id in existingIds }).toMutableList()
            }

            else -> {
                currentStartOffset = 0
                commands.toMutableList()
            }
        }

        if (merged.size + currentStartOffset - 1 == numRemoteCommands - 1) {
            merged.addAll(builtInCommandsProvider.getBuiltInCommands())
        }
        currentEndOffset = merged.size + currentStartOffset - 1

        discoverCommands = DiscoverCommands(
            commands = merged,
            currentStartOffset = currentStartOffset,
            currentEndOffset = currentEndOffset,
            hasMoreBefore = currentStartOffset > 0 && merged.isNotEmpty(),
            hasMoreAfter = currentEndOffset < numRemoteCommands - 1,
            jumpedSequenceId = jumpedSequenceId,
            jumpedApplicationId = jumpedTo,
            loadState = when {
                loadDirectionUp -> LoadState.JustLoadedUp
                loadDirectionDown -> LoadState.JustLoadedDown
                else -> LoadState.JustLoadedAll
            },
        )
        markChanged(DiscoverCommandsUpdate)
    }

    /**
     * The query result set: whatever the server matched, plus every built-in whose name starts with
     * the query. Built-ins are matched locally because the server has never heard of them.
     */
    @StoreThread
    private fun handleQueryCommandsUpdate(commands: List<ApplicationCommand>) {
        queryCommands.clear()
        queryCommands.addAll(commands)
        query?.let { prefix ->
            queryCommands.addAll(
                builtInCommandsProvider.getBuiltInCommands()
                    .filter { it.name.startsWith(prefix, ignoreCase = true) },
            )
        }
        markChanged(QueryCommandsUpdate)
    }

    /**
     * Re-orders a frecency response back into rank order.
     *
     * The gateway answers in its own order, so each requested id is looked up again — negative ids
     * against the built-in list, everything else against the flattened response — and unresolved
     * ids are dropped.
     */
    @StoreThread
    private fun handleFrecencyCommandsUpdate(nonce: String, commands: List<ApplicationCommand>) {
        val request = frecencyRequests[nonce] ?: return
        frecencyRequests.remove(nonce)
        val flattened = commands.flattenSubCommands()
        frecencyCommands[request.guildId ?: 0L] = request.applicationCommandIds.mapNotNull { id ->
            val numeric = id.toIntOrNull()
            if (numeric != null && numeric < 0) {
                builtInCommandsProvider.getBuiltInCommands()
                    .firstOrNull { it.id.toIntOrNull() == numeric }
            } else {
                flattened.firstOrNull { it.id == id }
            }
        }
        markChanged(FrecencyCommandsUpdate)
    }

    /**
     * Records an autocomplete state for one (option, query) pair — unless choices are already held
     * for it, which must not be downgraded back to loading or failed by a late signal.
     */
    @StoreThread
    private fun setAutocompleteState(
        commandOptionName: String,
        queryString: String,
        state: CommandAutocompleteState,
    ) {
        val existing = autocompleteOptionResults[commandOptionName].orEmpty()
        if (existing[queryString] is CommandAutocompleteState.Choices) return
        val updated = LinkedHashMap<String, CommandAutocompleteState>()
        updated.putAll(existing)
        updated[queryString] = state
        autocompleteOptionResults[commandOptionName] = updated
        markChanged(AutocompleteResultsUpdate)
    }

    /**
     * A DM (`discoverGuildId` null or 0) always shows commands; a guild channel requires
     * `USE_APPLICATION_COMMANDS`.
     */
    private fun channelPermissions(channelId: Long): Long? =
        storePermissions?.getPermissionsByChannel()?.get(channelId)

    /** Drops the optional store from the update-source list when it is not wired. */
    private fun sourcesWith(
        own: ObservationDeck.UpdateSource,
        optional: ObservationDeck.UpdateSource?,
    ): Array<ObservationDeck.UpdateSource> =
        if (optional != null) arrayOf(own, optional) else arrayOf(own)

    private fun shouldReturnApplicationCommands(channelPermissions: Long?): Boolean {
        val guildId = discoverGuildId ?: return true
        if (guildId == 0L) return true
        return PermissionUtils.can(Permission.USE_APPLICATION_COMMANDS, channelPermissions)
    }

    @StoreThread
    private fun generateNonce(): String = nonceGenerator.nonce().toString()

    @StoreThread
    override fun snapshotData() {
        if (updateSources.contains(DiscoverCommandsUpdate)) {
            discoverCommandsSnapshot =
                discoverCommands.copy(commands = discoverCommands.commands.flattenSubCommands())
        }
        if (updateSources.contains(QueryCommandsUpdate)) {
            queryCommandsSnapshot = ArrayList(queryCommands.flattenSubCommands())
        }
        if (updateSources.contains(GuildApplicationsUpdate)) {
            val snapshot = ArrayList(applications)
            applicationsSnapshot = snapshot
            applicationsMapSnapshot = snapshot.associateBy(Application::id)
        }
        if (updateSources.contains(AutocompleteResultsUpdate)) {
            autocompleteOptionResultsSnapshot = HashMap(autocompleteOptionResults)
        }
        if (updateSources.contains(FrecencyCommandsUpdate)) {
            frecencyCommandsSnapshot = LinkedHashMap(frecencyCommands)
        }
    }

    companion object {
        /** One update source per surface; see the class doc for why they are separate. */
        val FrecencyCommandsUpdate = object : ObservationDeck.UpdateSource {}
        val DiscoverCommandsUpdate = object : ObservationDeck.UpdateSource {}
        val QueryCommandsUpdate = object : ObservationDeck.UpdateSource {}
        val AutocompleteResultsUpdate = object : ObservationDeck.UpdateSource {}
        val GuildApplicationsUpdate = object : ObservationDeck.UpdateSource {}

        const val COMMANDS_LIMIT_PER_REQUEST = 20

        /** `RestAPIParams.ApplicationCommand.type` for a command invocation. */
        const val TYPE_APPLICATION_COMMAND = 2L

        /** `RestAPIParams.ApplicationCommand.type` for an autocomplete request. */
        const val TYPE_APPLICATION_COMMAND_AUTOCOMPLETE = 4L

        private const val FRECENCY_EXPERIMENT_NAME = "2021-09_android_app_commands_frecency"
        private const val FRECENCY_EXPERIMENT_BUCKET = 1
        private const val FRECENCY_LIMIT = 5
    }
}

// ---------------------------------------------------------------------------------------------
// Declarations the reference keeps in this same file.
// ---------------------------------------------------------------------------------------------

/**
 * Paging state of the discover list.
 *
 * The `JustLoaded*` variants exist so the list can decide whether to keep its scroll anchor: after
 * a load-up the adapter must compensate for the prepended rows, after a load-down it must not.
 */
sealed class LoadState {
    fun isLoading(): Boolean = this is Loading || this is LoadingUp || this is LoadingDown

    object None : LoadState()
    object Loading : LoadState()
    object LoadingUp : LoadState()
    object LoadingDown : LoadState()
    object JustLoadedAll : LoadState()
    object JustLoadedUp : LoadState()
    object JustLoadedDown : LoadState()
}

/**
 * The loaded window of a guild's command list.
 *
 * [jumpedSequenceId] increments on every jump so a repeat jump to the same application is still
 * observably a new value; [jumpedApplicationId] is what the list scrolls to.
 */
data class DiscoverCommands(
    val commands: List<ApplicationCommand>,
    val currentStartOffset: Int,
    val currentEndOffset: Int,
    val hasMoreBefore: Boolean,
    val hasMoreAfter: Boolean,
    val jumpedSequenceId: Int,
    val jumpedApplicationId: Long?,
    val loadState: LoadState = LoadState.None,
) {
    companion object {
        fun defaultModelDiscoveryCommands() =
            DiscoverCommands(emptyList(), 0, 0, false, false, 0, null)
    }
}

/** What is known about one argument's autocomplete suggestions. */
sealed class CommandAutocompleteState {
    object Loading : CommandAutocompleteState()
    object Failed : CommandAutocompleteState()
    data class Choices(val choices: List<ApplicationCommandAutocompleteChoice>) :
        CommandAutocompleteState()
}

/** The argument an autocomplete request was issued for, keyed by the request's nonce. */
data class CommandOptionAutocompleteQuery(
    val queryString: String,
    val commandOptionName: String,
)

/** Supplies the client-side commands that never reach the server. */
interface BuiltInCommandsProvider {
    fun getBuiltInApplication(): Application
    fun getBuiltInCommands(): List<ApplicationCommand>
    fun getFrecencyApplication(): Application
}

/**
 * The five commands the client executes itself.
 *
 * They carry negative ids so nothing can confuse them with a real application command — the
 * frecency decoder relies on exactly that to tell the two apart.
 */
class BuiltInCommands : BuiltInCommandsProvider {

    private val builtInCommands: List<ApplicationCommand> = listOf(
        appendToEndCommand(-1L, "shrug", R.string.command_shrug_description, R.string.command_shrug_message_description, "¯\\\\_(ツ)\\_/¯"),
        appendToEndCommand(-2L, "tableflip", R.string.command_tableflip_description, R.string.command_tableflip_message_description, "(╯°□°）╯︵ ┻━┻"),
        appendToEndCommand(-3L, "unflip", R.string.command_tableunflip_description, R.string.command_tableunflip_message_description, "┬─┬ ノ( ゜-゜ノ)"),
        wrappedCommand(-5L, "me", R.string.command_me_description, R.string.command_me_message_description, "_"),
        wrappedCommand(-6L, "spoiler", R.string.command_spoiler_description, R.string.command_spoiler_message_description, "||"),
    )

    private val builtInApplication = Application(
        id = BUILT_IN_APP_ID,
        name = "Built-In",
        iconRes = R.drawable.ic_slash_command_24dp,
        commandCount = builtInCommands.size,
        builtIn = true,
    )

    override fun getBuiltInApplication(): Application = builtInApplication

    override fun getBuiltInCommands(): List<ApplicationCommand> = builtInCommands

    override fun getFrecencyApplication(): Application = Application(
        id = FRECENCY_APP_ID,
        name = "Frequently Used",
        iconRes = R.drawable.ic_recent_24dp,
        commandCount = 0,
        builtIn = true,
    )

    /** `/shrug`, `/tableflip`, `/unflip` — the message keeps its text and gains a suffix. */
    private fun appendToEndCommand(
        id: Long,
        name: String,
        descriptionRes: Int?,
        optionDescriptionRes: Int?,
        replacement: String,
    ): ApplicationCommand = BuiltInCommand(
        id = id.toString(),
        applicationId = BUILT_IN_APP_ID,
        name = name,
        descriptionRes = descriptionRes,
        options = listOf(messageOption(optionDescriptionRes, required = false)),
        execute = { options -> "${options[MESSAGE_OPTION] ?: ""} $replacement" },
    )

    /** `/me`, `/spoiler` — the message is wrapped on both sides, so the argument is required. */
    private fun wrappedCommand(
        id: Long,
        name: String,
        descriptionRes: Int?,
        optionDescriptionRes: Int?,
        wrapper: String,
    ): ApplicationCommand = BuiltInCommand(
        id = id.toString(),
        applicationId = BUILT_IN_APP_ID,
        name = name,
        descriptionRes = descriptionRes,
        options = listOf(messageOption(optionDescriptionRes, required = true)),
        execute = { options -> "$wrapper${options[MESSAGE_OPTION] ?: ""}$wrapper" },
    )

    private fun messageOption(descriptionRes: Int?, required: Boolean) = ApplicationCommandOption(
        type = ApplicationCommandType.STRING,
        name = MESSAGE_OPTION,
        descriptionRes = descriptionRes,
        required = required,
        default = false,
        channelTypes = null,
    )

    companion object {
        const val BUILT_IN_APP_ID = -1L
        const val FRECENCY_APP_ID = -2L
        private const val MESSAGE_OPTION = "message"
    }
}

// ---------------------------------------------------------------------------------------------
// Conversions — the reference's `StoreApplicationCommandsKt`, absorbed into this file.
// ---------------------------------------------------------------------------------------------

/**
 * Expands every command that declares sub-commands into one entry per leaf, so the picker shows
 * `/role add` and `/role remove` rather than a bare `/role`.
 *
 * Only [RemoteApplicationCommand]s are expanded; a built-in has no sub-commands and an already
 * expanded [ApplicationSubCommand] must not be expanded twice.
 */
fun List<ApplicationCommand>.flattenSubCommands(): List<ApplicationCommand> = flatMap { command ->
    if (command is RemoteApplicationCommand) command.expandSubCommands() else listOf(command)
}

/** A command with no sub-command options expands to itself. */
private fun ApplicationCommand.expandSubCommands(): List<ApplicationCommand> {
    val expanded = ArrayList<ApplicationCommand>()
    var hasSubCommands = false
    options.forEach { option ->
        when (option.type) {
            ApplicationCommandType.SUBCOMMAND -> {
                expanded.add(expandSubCommand(option))
                hasSubCommands = true
            }

            ApplicationCommandType.SUBCOMMAND_GROUP -> {
                expanded.addAll(expandSubCommandGroup(option))
                hasSubCommands = true
            }

            else -> Unit
        }
    }
    if (!hasSubCommands) expanded.add(this)
    return expanded
}

private fun ApplicationCommand.expandSubCommand(subcommand: ApplicationCommandOption) =
    ApplicationSubCommand(
        rootCommand = this,
        subCommandName = subcommand.name,
        applicationId = applicationId,
        name = "$name ${subcommand.name}",
        description = subcommand.description,
        options = subcommand.options.orEmpty(),
        guildId = guildId,
        version = version,
    )

private fun ApplicationCommand.expandSubCommandGroup(
    subcommandGroup: ApplicationCommandOption,
): List<ApplicationCommand> = subcommandGroup.options.orEmpty()
    .filter { it.type == ApplicationCommandType.SUBCOMMAND }
    .map { leaf ->
        ApplicationSubCommand(
            rootCommand = this,
            subCommandName = leaf.name,
            parentGroupName = subcommandGroup.name,
            applicationId = applicationId,
            name = "$name ${subcommandGroup.name} ${leaf.name}",
            description = leaf.description,
            options = leaf.options.orEmpty(),
            guildId = guildId,
            version = version,
        )
    }

fun com.discord.api.commands.Application.toDomainApplication(): Application = Application(
    id = id,
    name = name,
    icon = icon,
    commandCount = commandCount,
    bot = bot,
)

fun List<com.discord.api.commands.ApplicationCommand>.toDomainCommands(): List<ApplicationCommand> =
    map { it.toSlashCommand() }

fun com.discord.api.commands.ApplicationCommand.toSlashCommand(): ApplicationCommand =
    RemoteApplicationCommand(
        id = id.toString(),
        applicationId = applicationId,
        name = name,
        description = description,
        options = options?.map { it.toSlashCommandOption() }.orEmpty(),
        guildId = guildId?.toLongOrNull(),
        version = version,
        defaultPermissions = defaultPermissions,
        permissions = permissions,
    )

fun com.discord.api.commands.ApplicationCommandOption.toSlashCommandOption(): ApplicationCommandOption =
    ApplicationCommandOption(
        type = type,
        name = name,
        description = description,
        required = required,
        default = default,
        channelTypes = channelTypes,
        choices = choices,
        options = options?.map { it.toSlashCommandOption() },
        autocomplete = autocomplete,
        minValue = minValue,
        maxValue = maxValue,
    )
