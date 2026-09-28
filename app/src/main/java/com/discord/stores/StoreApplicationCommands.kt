package com.discord.stores

import android.content.res.Resources
import com.discord.R
import com.discord.api.commands.ApplicationCommandAutocompleteChoice
import com.discord.api.commands.ApplicationCommandAutocompleteResult
import com.discord.api.commands.ApplicationCommandContext
import com.discord.api.commands.ApplicationCommandIndex
import com.discord.api.commands.ApplicationCommandIndexApplication
import com.discord.api.commands.ApplicationCommandIndexCommand
import com.discord.api.commands.ApplicationIntegrationType
import com.discord.api.channel.Channel
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.CommandChoice
import com.discord.api.permission.Permission
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.ApplicationSubCommand
import com.discord.models.commands.BuiltInCommand
import com.discord.models.commands.BuiltInCommandAction
import com.discord.models.commands.RemoteApplicationCommand
import com.discord.plugins.PluginUiRegistry
import com.discord.models.commands.flatten
import com.discord.models.commands.isAvailableIn
import com.discord.models.commands.toRestParams
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.NonceGenerator
import com.discord.models.user.User
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.user.UserUtils.synthesizeApiUser
import com.discord.widgets.chat.input.models.ApplicationCommandValue
import dev.mantikafasi.manticord.plugins.api.pluginChannelContext
import dev.mantikafasi.manticord.plugins.api.pluginSlashCommandContext
import dev.mantikafasi.manticord.plugins.api.PluginSlashCommandOptionType
import java.util.concurrent.TimeUnit
import retrofit2.Response
import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers

/**
 * Everything the slash-command surface reads: which applications a guild exposes, the paged
 * "discover" list, the current query results, the frequently-used shortlist, and per-argument
 * autocomplete choices.
 *
 * Five independent [ObservationDeck.UpdateSource]s rather than one, because those five surfaces
 * change at wildly different rates — an autocomplete keystroke must not invalidate the application
 * rail — and [snapshotData] copies only what actually changed.
 *
 * Everything comes from the application command index, which answers with the whole catalogue for
 * a context in one response, so the query, discover and frecency surfaces are all views over
 * [indexedCommands] rather than separate requests.
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
     * `null` while `StorePermissions` is not wired. Every channel is then treated as unrestricted,
     * which is the same answer the store gives for a DM.
     */
    private val storePermissions: StorePermissions? = null,
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    private val indexCachePersister: Persister<PersistedIndexCache> =
        Persister(INDEX_CACHE_KEY, PersistedIndexCache(), preload = false),
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val builtInCommandsProvider: BuiltInCommandsProvider = BuiltInCommands(),
    private val channelType: (Long?) -> Int? = { id ->
        id?.let { StoreStream.getChannels().getChannel(it)?.type }
    },
    private val nonceGenerator: NonceGenerator = NonceGenerator(),
    private val retryScheduler: Scheduler = Schedulers.computation(),
) : StoreV2() {

    // -- Applications --------------------------------------------------------------------------

    private var applications: List<Application> = emptyList()
    private var applicationsSnapshot: List<Application> = emptyList()
    private var applicationsMapSnapshot: Map<Long, Application> = emptyMap()

    // -- Discover list -------------------------------------------------------------------------

    private var discoverCommands: DiscoverCommands = DiscoverCommands.defaultModelDiscoveryCommands()
    private var discoverCommandsSnapshot: DiscoverCommands = DiscoverCommands.defaultModelDiscoveryCommands()
    private var discoverGuildId: Long? = null

    // -- Query ---------------------------------------------------------------------------------

    private val queryCommands = ArrayList<ApplicationCommand>()
    private var queryCommandsSnapshot: List<ApplicationCommand> = emptyList()
    private var queryGuildId: Long? = null
    private var query: String? = null
    private var indexedCommands: List<ApplicationCommand> = emptyList()

    /** User and message context-menu commands from the current index; the snapshot pairs them with its channel. */
    private var contextCommands: List<ApplicationCommand> = emptyList()
    private var contextCommandsSnapshot: Pair<Long?, List<ApplicationCommand>> = null to emptyList()
    private var indexRequestGeneration: Long = 0L
    private var indexedChannelId: Long? = null
    private var indexedGuildId: Long? = null
    private var indexedIsBotDm: Boolean = false
    private var indexLoading: Boolean = false
    private var pendingIndexSources: Int = 0
    private val loadedIndexSources = LinkedHashMap<Int, IndexedSource>()
    private var currentUserId = 0L
    @Volatile private var persistedIndexCache = PersistedIndexCache()

    // -- Frecency ------------------------------------------------------------------------------

    private var frecencyCommands: MutableMap<Long, List<ApplicationCommand>> = LinkedHashMap()
    private var frecencyCommandsSnapshot: Map<Long, List<ApplicationCommand>> = emptyMap()

    // -- Autocomplete --------------------------------------------------------------------------

    private var autocompleteNonceData: MutableMap<String, CommandOptionAutocompleteQuery> = LinkedHashMap()
    private var autocompleteOptionResults:
        MutableMap<String, MutableMap<String, CommandAutocompleteState>> = LinkedHashMap()
    private var autocompleteOptionResultsSnapshot: Map<String, Map<String, CommandAutocompleteState>> =
        emptyMap()

    // -- Connection ----------------------------------------------------------------------------

    private var sessionId: String? = null
    private var connectionReady: Boolean = false
    private var pendingBotUser: User? = null

    // -- Reads ---------------------------------------------------------------------------------

    fun getApplications(): List<Application> = applicationsSnapshot

    fun getApplicationMap(): Map<Long, Application> = applicationsMapSnapshot

    fun getDiscoverCommands(): DiscoverCommands = discoverCommandsSnapshot

    fun getQueryCommands(): List<ApplicationCommand> = queryCommandsSnapshot

    /** [type] is [ApplicationCommandIndexCommand.TYPE_USER] or [ApplicationCommandIndexCommand.TYPE_MESSAGE]. */
    fun getContextCommands(channelId: Long, type: Int): List<ApplicationCommand> =
        contextCommandsSnapshot.takeIf { it.first == channelId }?.second.orEmpty().filter { it.type == type }

    /**
     * Context commands of the viewer's user-installed apps that may run in a DM, from the cached
     * account index. Used where no channel is at hand (a profile opened from the friends list):
     * the command then runs in the DM with its target.
     */
    fun getPrivateChannelContextCommands(type: Int): List<ApplicationCommand> {
        val index = persistedIndexCache.userIndex ?: return emptyList()
        val installation = ApplicationIntegrationType.USER_INSTALL.value
        return index.applicationCommands
            .filter { it.type == type }
            .map { command ->
                command.toSlashCommand(
                    applicationPermissions = index.applications.firstOrNull { it.id == command.applicationId }?.permissions,
                    installationType = installation,
                )
            }
            .filter { it.isAvailableIn(0L, isBotDm = false) }
            .sortedBy(ApplicationCommand::name)
    }

    /** The applications behind [getPrivateChannelContextCommands], for names and icons. */
    fun getPrivateChannelContextApplications(): Map<Long, Application> =
        persistedIndexCache.userIndex?.applications.orEmpty().associate { it.id to it.toModel() }

    fun getFrecencyCommands(guildId: Long): List<ApplicationCommand> =
        frecencyCommandsSnapshot[guildId].orEmpty()

    fun getAutocompleteOptionResults(): Map<String, Map<String, CommandAutocompleteState>> =
        autocompleteOptionResultsSnapshot

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
            DiscoverCommands(builtInCommandsProvider.getBuiltInCommands(channelType(channelId)))
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

    fun observeContextCommands(channelId: Long, type: Int): Observable<List<ApplicationCommand>> =
        observationDeck.connectRx(*sourcesWith(ContextCommandsUpdate, storePermissions)) {
            if (shouldReturnApplicationCommands(channelPermissions(channelId))) getContextCommands(channelId, type) else emptyList()
        }.distinctUntilChanged()

    /** The frequently-used shortlist, capped at five. */
    fun observeFrecencyCommands(guildId: Long): Observable<List<ApplicationCommand>> =
        observationDeck.connectRx(FrecencyCommandsUpdate) {
            getFrecencyCommands(guildId).take(FRECENCY_LIMIT)
        }.startWith(emptyList<ApplicationCommand>())

    fun observeAutocompleteResults(): Observable<Map<String, Map<String, CommandAutocompleteState>>> =
        observationDeck.connectRx(AutocompleteResultsUpdate) { getAutocompleteOptionResults() }
            .startWith(emptyMap<String, Map<String, CommandAutocompleteState>>())

    // -- Connection lifecycle ------------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        sessionId = payload.sessionId
        currentUserId = payload.me?.id ?: 0L
        val cached = indexCachePersister.get()
        persistedIndexCache = if (currentUserId > 0L && cached.userId == currentUserId) {
            cached
        } else {
            PersistedIndexCache(userId = currentUserId).also {
                indexCachePersister.set(it, persist = true)
            }
        }
    }

    /** Replays the bot DM that arrived while the socket was down. */
    @StoreThread
    fun handleConnectionReady(connectionReady: Boolean) {
        this.connectionReady = connectionReady
        if (!connectionReady) return
        pendingBotUser?.let(::handleDmUserApplication)
        pendingBotUser = null
    }

    @StoreThread
    fun handlePreLogout() {
        applications = emptyList()
        discoverCommands = DiscoverCommands.defaultModelDiscoveryCommands()
        discoverGuildId = null
        queryCommands.clear()
        queryGuildId = null
        query = null
        indexedCommands = emptyList()
        indexRequestGeneration++
        indexedChannelId = null
        indexedGuildId = null
        indexedIsBotDm = false
        indexLoading = false
        pendingIndexSources = 0
        loadedIndexSources.clear()
        frecencyCommands.clear()
        autocompleteNonceData.clear()
        autocompleteOptionResults.clear()
        connectionReady = false
        pendingBotUser = null
        sessionId = null
        currentUserId = 0L
        persistedIndexCache = PersistedIndexCache()
        indexCachePersister.clear(persist = true)
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
     * `APPLICATION_COMMAND_AUTOCOMPLETE_RESPONSE`.
     *
     * A response whose nonce is no longer tracked is dropped: the user has moved on to another
     * argument and the choices would be for the wrong one.
     */
    @StoreThread
    fun handleApplicationCommandAutocompleteResult(autocompleteResult: ApplicationCommandAutocompleteResult) {
        val queryData = autocompleteNonceData.remove(autocompleteResult.nonce) ?: return
        val forOption = LinkedHashMap<String, CommandAutocompleteState>()
        forOption.putAll(autocompleteOptionResults[queryData.commandOptionName].orEmpty())
        forOption[queryData.queryString] = CommandAutocompleteState.Choices(
            autocompleteResult.choices.map { choice ->
                choice.copy(value = normalizeCommandChoiceValue(queryData.commandOptionType, choice.value))
            },
        )
        autocompleteOptionResults[queryData.commandOptionName] = forOption
        markChanged(AutocompleteResultsUpdate)
    }

    // -- Requests ------------------------------------------------------------------------------

    /** Fetches the modern command indexes for the selected location and the current user's apps. */
    @JvmOverloads
    fun requestApplicationCommandIndex(
        channelId: Long,
        guildId: Long?,
        isBotDm: Boolean,
        force: Boolean = false,
    ) = dispatcher.schedule {
        val sameLocation = indexedGuildId == guildId && (guildId != null || indexedChannelId == channelId)
        if (!force && sameLocation) {
            val botContextChanged = indexedIsBotDm != isBotDm
            indexedChannelId = channelId
            indexedIsBotDm = isBotDm
            // The guild index is shared by every channel in the guild; re-snapshot so its context
            // commands follow the viewer to the new channel.
            markChanged(ContextCommandsUpdate)
            if (indexLoading) return@schedule
            if (loadedIndexSources.size == 2) {
                if (botContextChanged) {
                    handleApplicationCommandIndexes(
                        indexRequestGeneration,
                        guildId,
                        isBotDm,
                        loadedIndexSources.values.toList(),
                    )
                }
                return@schedule
            }
        }
        val preservePartialIndex = !force && sameLocation && loadedIndexSources.isNotEmpty()
        val generation = ++indexRequestGeneration
        indexedChannelId = channelId
        indexedGuildId = guildId
        indexedIsBotDm = isBotDm
        indexLoading = true
        if (!preservePartialIndex) {
            loadedIndexSources.clear()
            indexedCommands = emptyList()
            queryCommands.clear()
            discoverCommands = DiscoverCommands.defaultModelDiscoveryCommands().copy(loadState = LoadState.Loading)
            handleGuildApplicationsUpdate(
                listOf(builtInCommandsProvider.getFrecencyApplication(), builtInCommandsProvider.getBuiltInApplication()),
            )
            markChanged(QueryCommandsUpdate, DiscoverCommandsUpdate)
        }
        val guildInstall = ApplicationIntegrationType.GUILD_INSTALL.value
        val userInstall = ApplicationIntegrationType.USER_INSTALL.value
        if (!force) {
            persistedIndexCache.userIndex?.let {
                loadedIndexSources[userInstall] = IndexedSource(it, userInstall)
            }
            val locationIndex = if (guildId != null && guildId > 0L) {
                persistedIndexCache.guildIndexes[guildId]
            } else {
                persistedIndexCache.channelIndexes[channelId]
            }
            locationIndex?.let { loadedIndexSources[guildInstall] = IndexedSource(it, guildInstall) }
            if (loadedIndexSources.size == 2) {
                indexLoading = false
                pendingIndexSources = 0
                handleApplicationCommandIndexes(generation, guildId, isBotDm, loadedIndexSources.values.toList())
                return@schedule
            }
        }
        pendingIndexSources = listOf(guildInstall, userInstall).count { it !in loadedIndexSources }
        val api = restApi()
        if (api == null) {
            handleApplicationCommandIndexFailure(generation)
            return@schedule
        }
        if (guildInstall !in loadedIndexSources) {
            requestIndexSource(generation, guildId, isBotDm, guildInstall) {
                if (guildId != null && guildId > 0L) {
                    api.getGuildApplicationCommandIndex(guildId)
                } else {
                    api.getChannelApplicationCommandIndex(channelId)
                }
            }
        }
        if (userInstall !in loadedIndexSources) {
            requestIndexSource(generation, guildId, isBotDm, userInstall) {
                api.getUserApplicationCommandIndex()
            }
        }
    }

    /**
     * A cold index answers `202` with no body while the server builds it, so the reference waits
     * and asks again rather than treating the empty answer as "this context has no commands".
     */
    private fun requestIndexSource(
        generation: Long,
        guildId: Long?,
        isBotDm: Boolean,
        installationType: Int,
        attempt: Int = 1,
        request: () -> Observable<Response<ApplicationCommandIndex>>,
    ) {
        fun finish(index: ApplicationCommandIndex) = dispatcher.schedule {
            handleIndexSourceFinished(generation, IndexedSource(index, installationType), guildId, isBotDm)
        }
        request()
            .restSubscribeOn(false)
            .appSubscribe(
                StoreApplicationCommands::class.java,
                errorHandler = { finish(ApplicationCommandIndex()) },
            ) { response ->
                val index = response.body()
                if (response.code() == INDEX_NOT_READY || index == null) {
                    if (attempt >= INDEX_MAX_TRIES) {
                        finish(ApplicationCommandIndex())
                    } else {
                        retryScheduler.createWorker().schedule(
                            {
                                if (generation == indexRequestGeneration) {
                                    requestIndexSource(
                                        generation,
                                        guildId,
                                        isBotDm,
                                        installationType,
                                        attempt + 1,
                                        request,
                                    )
                                }
                            },
                            INDEX_RETRY_MS,
                            TimeUnit.MILLISECONDS,
                        )
                    }
                } else {
                    finish(index)
                }
            }
    }

    @StoreThread
    private fun handleIndexSourceFinished(
        generation: Long,
        source: IndexedSource?,
        guildId: Long? = indexedGuildId,
        isBotDm: Boolean = indexedIsBotDm,
    ) {
        if (generation != indexRequestGeneration) return
        source?.let {
            loadedIndexSources[it.installationType] = it
            persistIndexSource(it)
        }
        pendingIndexSources--
        if (loadedIndexSources.isNotEmpty()) {
            handleApplicationCommandIndexes(generation, guildId, isBotDm, loadedIndexSources.values.toList())
        }
        if (pendingIndexSources <= 0) {
            indexLoading = false
            if (loadedIndexSources.isEmpty()) handleApplicationCommandIndexFailure(generation)
        }
    }

    @StoreThread
    private fun persistIndexSource(source: IndexedSource) {
        if (currentUserId <= 0L) return
        persistedIndexCache = when (source.installationType) {
            ApplicationIntegrationType.USER_INSTALL.value -> persistedIndexCache.copy(userIndex = source.index)
            else -> indexedGuildId?.takeIf { it > 0L }?.let { guildId ->
                persistedIndexCache.copy(guildIndexes = persistedIndexCache.guildIndexes + (guildId to source.index))
            } ?: indexedChannelId?.let { channelId ->
                persistedIndexCache.copy(channelIndexes = persistedIndexCache.channelIndexes + (channelId to source.index))
            } ?: persistedIndexCache
        }
        indexCachePersister.set(persistedIndexCache, persist = true)
    }

    @StoreThread
    private fun handleApplicationCommandIndexFailure(generation: Long) {
        if (generation != indexRequestGeneration) return
        indexLoading = false
        indexedCommands = emptyList()
        contextCommands = emptyList()
        markChanged(ContextCommandsUpdate)
        handleGuildApplicationsUpdate(
            listOf(builtInCommandsProvider.getFrecencyApplication(), builtInCommandsProvider.getBuiltInApplication()),
        )
        discoverCommands = DiscoverCommands(
            commands = builtInCommandsProvider.getBuiltInCommands(channelType(indexedChannelId)),
            loadState = LoadState.Failed,
        )
        markChanged(DiscoverCommandsUpdate)
        query?.let { handleQueryCommandsUpdate(emptyList()) }
    }

    @StoreThread
    internal fun handleApplicationCommandIndexes(
        generation: Long,
        guildId: Long?,
        isBotDm: Boolean,
        indexes: List<IndexedSource>,
    ) {
        if (generation != indexRequestGeneration) return
        val applications = LinkedHashMap<Long, Application>()
        indexes.sortedBy(IndexedSource::installationType).forEach { source ->
            source.index.applications.forEach { app ->
                val previous = applications[app.id]
                applications[app.id] = app.toModel(previous, source.installationType)
            }
        }
        val commands = LinkedHashMap<Long, ApplicationCommand>()
        val contextMenu = LinkedHashMap<Long, ApplicationCommand>()
        indexes.sortedBy(IndexedSource::installationType).forEach { source ->
            source.index.applicationCommands.forEach { command ->
                val target = when (command.type) {
                    ApplicationCommandIndexCommand.TYPE_CHAT_INPUT,
                    ApplicationCommandIndexCommand.TYPE_PRIMARY_ENTRY_POINT -> commands
                    ApplicationCommandIndexCommand.TYPE_USER,
                    ApplicationCommandIndexCommand.TYPE_MESSAGE -> contextMenu
                    else -> return@forEach
                }
                val application = applications[command.applicationId]
                val domain = command.toSlashCommand(
                    applicationPermissions = application?.permissions,
                    installationType = if (target.containsKey(command.id)) null else source.installationType,
                )
                if (domain.isAvailableIn(guildId ?: 0L, isBotDm)) target[command.id] = domain
            }
        }
        indexedCommands = commands.values.toList()
        contextCommands = contextMenu.values.sortedBy(ApplicationCommand::name)
        markChanged(ContextCommandsUpdate)
        val counts = indexedCommands.groupingBy(ApplicationCommand::applicationId).eachCount()
        val realApplications = applications.values.map { it.copy(commandCount = counts[it.id] ?: 0) }
            .sortedBy(Application::name)
        handleGuildApplicationsUpdate(
            listOf(builtInCommandsProvider.getFrecencyApplication()) +
                realApplications + builtInCommandsProvider.getBuiltInApplication(),
        )
        discoverGuildId = guildId
        handleDiscoverCommandsUpdate(indexedCommands)
        query?.let { handleQueryCommandsUpdate(indexedCommands.filterForQuery(it)) }
    }

    data class IndexedSource(val index: ApplicationCommandIndex, val installationType: Int)

    data class PersistedIndexCache(
        val userId: Long = 0L,
        val userIndex: ApplicationCommandIndex? = null,
        val guildIndexes: Map<Long, ApplicationCommandIndex> = emptyMap(),
        val channelIndexes: Map<Long, ApplicationCommandIndex> = emptyMap(),
    )

    @StoreThread
    fun handleApplicationCommandIndexUpdate(guildId: Long) {
        val channelId = indexedChannelId ?: return
        if (indexedGuildId != guildId) return
        persistedIndexCache = persistedIndexCache.copy(guildIndexes = persistedIndexCache.guildIndexes - guildId)
        indexCachePersister.set(persistedIndexCache, persist = true)
        loadedIndexSources.remove(ApplicationIntegrationType.GUILD_INSTALL.value)
        requestApplicationCommandIndex(channelId, guildId, indexedIsBotDm)
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
            applications = emptyList()
            handleGuildApplicationsUpdate(
                listOf(
                    builtInCommandsProvider.getFrecencyApplication(),
                    Application(
                        id = botUser.id,
                        name = botUser.username,
                        commandCount = 0,
                        bot = botUser.synthesizeApiUser(),
                        botId = botUser.id,
                    ),
                    builtInCommandsProvider.getBuiltInApplication(),
                ),
            )
            markChanged(FrecencyCommandsUpdate)
        }
    }

    /** The index holds the whole catalogue, so a query is a filter rather than a request. */
    fun requestApplicationCommandsQuery(guildId: Long?, query: String) = dispatcher.schedule {
        if (queryGuildId != guildId) {
            queryCommands.clear()
            markChanged(QueryCommandsUpdate)
        }
        queryGuildId = guildId
        this.query = query
        handleQueryCommandsUpdate(indexedCommands.filterForQuery(query))
    }

    fun clearQueryCommands() = dispatcher.schedule {
        queryCommands.clear()
        markChanged(QueryCommandsUpdate)
    }

    fun handleLocalCommandsChanged() = dispatcher.schedule {
        handleDiscoverCommandsUpdate(indexedCommands)
        query?.let { handleQueryCommandsUpdate(indexedCommands.filterForQuery(it)) }
        applications = applications.map { application ->
            if (application.id == BuiltInCommands.BUILT_IN_APP_ID) {
                builtInCommandsProvider.getBuiltInApplication()
            } else {
                application
            }
        }
        frecencyCommands.keys.toList().forEach(::handleFrecencyCommandsUpdate)
        markChanged(GuildApplicationsUpdate)
    }

    fun clearAutocompleteResults() = dispatcher.schedule {
        autocompleteOptionResults.clear()
        markChanged(AutocompleteResultsUpdate)
    }

    /** Resolves the frecency shortlist against the index, in rank order. */
    fun requestFrecencyCommands(guildId: Long) = dispatcher.schedule {
        handleFrecencyCommandsUpdate(guildId)
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
        val focused = data.values.flatten().firstOrNull { it.focused == true } ?: return@schedule
        val query = focused.value.toString()
        cancelAutocompleteRequests(focused.name)
        setAutocompleteState(focused.name, query, CommandAutocompleteState.Loading)
        val session = sessionId
        if (session == null) {
            setAutocompleteState(focused.name, query, CommandAutocompleteState.Failed)
            return@schedule
        }
        val nonce = generateNonce()
        val commandData = com.discord.api.commands.ApplicationCommandData(
            version = data.applicationCommand.version,
            guildId = data.applicationCommand.guildId?.toString(),
            id = data.applicationCommand.id,
            name = data.applicationCommand.untranslatedName,
            options = data.values.toRestParams { it },
            attachments = null,
            type = data.applicationCommand.type,
            applicationCommand = data.applicationCommand.apiCommand,
        )
        val body = RestAPIParams.ApplicationCommand(
            type = TYPE_APPLICATION_COMMAND_AUTOCOMPLETE,
            channelId = channelId.toString(),
            applicationId = data.application.id.toString(),
            guildId = guildId?.toString(),
            data = commandData,
            sessionId = session,
            nonce = nonce,
        )
        autocompleteNonceData[nonce] =
            CommandOptionAutocompleteQuery(
                query,
                focused.name,
                ApplicationCommandType.from(focused.type),
            )
        val api = restApi()
        if (api == null) {
            handleAutocompleteFailure(nonce)
            return@schedule
        }
        api.sendApplicationCommand(body)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreApplicationCommands::class.java,
                errorHandler = {
                    dispatcher.schedule {
                        handleAutocompleteFailure(nonce)
                    }
                },
            ) { }
    }

    @StoreThread
    private fun cancelAutocompleteRequests(commandOptionName: String) {
        val cancelled = autocompleteNonceData.filterValues {
            it.commandOptionName == commandOptionName
        }
        cancelled.forEach { (nonce, query) ->
            autocompleteNonceData.remove(nonce)
            val states = autocompleteOptionResults[commandOptionName] ?: return@forEach
            if (states[query.queryString] !is CommandAutocompleteState.Loading) return@forEach
            val updated = LinkedHashMap(states)
            updated.remove(query.queryString)
            if (updated.isEmpty()) autocompleteOptionResults.remove(commandOptionName)
            else autocompleteOptionResults[commandOptionName] = updated
        }
    }

    @StoreThread
    private fun handleAutocompleteFailure(nonce: String) {
        val query = autocompleteNonceData.remove(nonce) ?: return
        setAutocompleteState(
            query.commandOptionName,
            query.queryString,
            CommandAutocompleteState.Failed,
        )
    }

    @StoreThread
    fun setAutocompleteLoading(commandOptionName: String, queryString: String) =
        setAutocompleteState(commandOptionName, queryString, CommandAutocompleteState.Loading)

    @StoreThread
    fun setAutocompleteFailed(commandOptionName: String, queryString: String) =
        setAutocompleteState(commandOptionName, queryString, CommandAutocompleteState.Failed)

    // -- Internal state transitions ------------------------------------------------------------

    @StoreThread
    private fun handleGuildApplicationsUpdate(commands: List<Application>) {
        applications = commands
        markChanged(GuildApplicationsUpdate)
    }

    /** Built-ins sit after everything the index knows about. */
    @StoreThread
    private fun handleDiscoverCommandsUpdate(commands: List<ApplicationCommand>) {
        discoverCommands = DiscoverCommands(
            commands = commands + builtInCommandsProvider.getBuiltInCommands(channelType(indexedChannelId)),
            loadState = LoadState.JustLoadedAll,
        )
        markChanged(DiscoverCommandsUpdate)
    }

    /** The matching indexed commands plus every built-in whose name starts with the query. */
    @StoreThread
    private fun handleQueryCommandsUpdate(commands: List<ApplicationCommand>) {
        queryCommands.clear()
        val indexedById = indexedCommands.associateBy(ApplicationCommand::id)
        queryCommands.addAll(commands.map { indexedById[it.id] ?: it })
        query?.let { prefix ->
            queryCommands.addAll(
                builtInCommandsProvider.getBuiltInCommands(channelType(indexedChannelId))
                    .filter { it.name.startsWith(prefix, ignoreCase = true) },
            )
        }
        markChanged(QueryCommandsUpdate)
    }

    private fun List<ApplicationCommand>.filterForQuery(query: String): List<ApplicationCommand> =
        filter { command ->
            command.name.contains(query, ignoreCase = true) ||
                command.description?.contains(query, ignoreCase = true) == true
        }

    /**
     * Resolves ranked ids to commands, dropping any the index no longer carries.
     *
     * A negative id belongs to a built-in, which the index has never heard of.
     */
    @StoreThread
    private fun handleFrecencyCommandsUpdate(guildId: Long) {
        val flattened = indexedCommands.flattenSubCommands()
        frecencyCommands[guildId] = storeApplicationCommandFrecency.getTopCommandIds(guildId)
            .mapNotNull { id ->
                builtInCommandsProvider.getBuiltInCommands(channelType(indexedChannelId))
                    .firstOrNull { it.id == id }
                    ?: flattened.firstOrNull { it.id == id }
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
        if (updateSources.contains(ContextCommandsUpdate)) {
            contextCommandsSnapshot = indexedChannelId to contextCommands
        }
    }

    companion object {
        /** One update source per surface; see the class doc for why they are separate. */
        val FrecencyCommandsUpdate = object : ObservationDeck.UpdateSource {}
        val DiscoverCommandsUpdate = object : ObservationDeck.UpdateSource {}
        val QueryCommandsUpdate = object : ObservationDeck.UpdateSource {}
        val AutocompleteResultsUpdate = object : ObservationDeck.UpdateSource {}
        val GuildApplicationsUpdate = object : ObservationDeck.UpdateSource {}
        val ContextCommandsUpdate = object : ObservationDeck.UpdateSource {}

        const val COMMANDS_LIMIT_PER_REQUEST = 20

        /** `RestAPIParams.ApplicationCommand.type` for a command invocation. */
        const val TYPE_APPLICATION_COMMAND = 2L

        /** `RestAPIParams.ApplicationCommand.type` for an autocomplete request. */
        const val TYPE_APPLICATION_COMMAND_AUTOCOMPLETE = 4L

        /** `202 Accepted`: the server is still building this index. */
        private const val INDEX_NOT_READY = 202
        private const val INDEX_MAX_TRIES = 3
        private const val INDEX_RETRY_MS = 5000L

        private const val FRECENCY_LIMIT = 5
        private const val INDEX_CACHE_KEY = "STORE_APPLICATION_COMMAND_INDEX_CACHE_V1"
    }
}

// ---------------------------------------------------------------------------------------------
// Declarations the reference keeps in this same file.
// ---------------------------------------------------------------------------------------------

/** Whether the index behind the discover list has arrived. */
sealed class LoadState {
    fun isLoading(): Boolean = this is Loading

    object None : LoadState()
    object Loading : LoadState()
    object JustLoadedAll : LoadState()
    object Failed : LoadState()
}

/** Every command the context offers, built-ins last. */
data class DiscoverCommands(
    val commands: List<ApplicationCommand>,
    val loadState: LoadState = LoadState.None,
) {
    companion object {
        fun defaultModelDiscoveryCommands() = DiscoverCommands(emptyList())
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
    val commandOptionType: ApplicationCommandType,
)

/** Supplies the client-side commands that never reach the server. */
interface BuiltInCommandsProvider {
    fun getBuiltInApplication(): Application

    /** [channelType] admits the commands that only apply to one kind of channel. */
    fun getBuiltInCommands(channelType: Int? = null): List<ApplicationCommand>
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

    private val groupDmCommands: List<ApplicationCommand> = listOf(
        BuiltInCommand(
            id = "-7",
            applicationId = BUILT_IN_APP_ID,
            name = "leave",
            descriptionRes = R.string.command_leave_description,
            options = listOf(
                ApplicationCommandOption(
                    type = ApplicationCommandType.BOOLEAN,
                    name = SILENT_OPTION,
                    descriptionRes = R.string.command_leave_silent_description,
                    required = false,
                    default = false,
                    channelTypes = null,
                ),
            ),
            action = BuiltInCommandAction.LEAVE_GROUP_DM,
        ),
    )

    private val builtInApplication = Application(
        id = BUILT_IN_APP_ID,
        name = "Built-In",
        iconRes = R.drawable.ic_slash_command_24dp,
        commandCount = builtInCommands.size,
        builtIn = true,
    )

    override fun getBuiltInApplication(): Application = builtInApplication.copy(
        commandCount = builtInCommands.size + PluginUiRegistry.slashCommands().size,
    )

    override fun getBuiltInCommands(channelType: Int?): List<ApplicationCommand> {
        val native = if (channelType == Channel.GROUP_DM) builtInCommands + groupDmCommands else builtInCommands
        return native + pluginCommands(channelType, native.mapTo(HashSet(), ApplicationCommand::name))
    }

    private fun pluginCommands(channelType: Int?, reservedNames: Set<String>): List<ApplicationCommand> {
        val channel = runCatching { StoreStream.getChannelsSelected().getSelectedChannel() }.getOrNull()
        val resolvedType = channelType ?: channel?.type ?: Channel.DM
        val context = pluginChannelContext(
            channelId = channel?.id ?: 0L,
            guildId = channel?.guildId?.takeIf { it > 0L },
            channelType = resolvedType,
            isPrivate = resolvedType in setOf(Channel.DM, Channel.GROUP_DM),
        )
        return PluginUiRegistry.slashCommands().distinctBy { it.contribution.name }.mapNotNull { owned ->
            val command = owned.contribution
            if (command.name in reservedNames) return@mapNotNull null
            if (!runCatching { command.isVisible(context) }.getOrDefault(false)) return@mapNotNull null
            BuiltInCommand(
                id = "plugin:${owned.key}",
                applicationId = BUILT_IN_APP_ID,
                name = command.name,
                description = command.description,
                options = command.options.map { option ->
                    ApplicationCommandOption(
                        type = when (option.type) {
                            PluginSlashCommandOptionType.STRING -> ApplicationCommandType.STRING
                            PluginSlashCommandOptionType.INTEGER -> ApplicationCommandType.INTEGER
                            PluginSlashCommandOptionType.BOOLEAN -> ApplicationCommandType.BOOLEAN
                        },
                        name = option.name,
                        description = option.description,
                        required = option.required,
                        default = false,
                        choices = option.choices.map { CommandChoice(it, it) }.takeIf(List<CommandChoice>::isNotEmpty),
                    )
                },
            ) { options ->
                runCatching {
                    command.execute(
                        pluginSlashCommandContext(context, options),
                    )
                }.getOrDefault("")
            }
        }
    }

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
        private const val SILENT_OPTION = "silent"
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
        untranslatedName = "$untranslatedName ${subcommand.untranslatedName}",
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
            untranslatedName = "$untranslatedName ${subcommandGroup.untranslatedName} ${leaf.untranslatedName}",
        )
    }

/** An index application as the picker shows it, merged over what an earlier index said about it. */
private fun ApplicationCommandIndexApplication.toModel(previous: Application? = null, installationType: Int? = null) = Application(
    id = id,
    name = bot?.username ?: name,
    icon = icon,
    commandCount = 0,
    bot = bot ?: previous?.bot,
    botId = botId ?: bot?.id ?: previous?.botId,
    permissions = permissions ?: previous?.permissions,
    installationTypes = previous?.installationTypes.orEmpty() + setOfNotNull(installationType),
)

fun ApplicationCommandIndexCommand.toSlashCommand(
    applicationPermissions: com.discord.api.commands.ApplicationCommandIndexPermissions?,
    installationType: Int?,
): ApplicationCommand = RemoteApplicationCommand(
    id = id.toString(),
    applicationId = applicationId,
    name = name,
    untranslatedName = nameDefault ?: name,
    description = description,
    options = options?.map { it.toSlashCommandOption() }.orEmpty(),
    guildId = guildId?.toLongOrNull(),
    version = version,
    type = type,
    defaultMemberPermissions = defaultMemberPermissions?.toLongOrNull(),
    applicationPermissions = applicationPermissions,
    commandPermissions = permissions,
    contexts = contexts?.toSet(),
    integrationTypes = integrationTypes?.toSet(),
    installationType = installationType,
    nsfw = nsfw,
    dmPermission = dmPermission,
    globalPopularityRank = globalPopularityRank,
    handler = handler,
    apiCommand = this,
)

fun com.discord.api.commands.ApplicationCommandOption.toSlashCommandOption(): ApplicationCommandOption =
    ApplicationCommandOption(
        type = type,
        name = nameLocalized ?: name,
        description = descriptionLocalized ?: description,
        required = required,
        default = default,
        channelTypes = channelTypes,
        choices = choices.orEmpty().map { choice ->
            choice.copy(value = normalizeCommandChoiceValue(type, choice.value))
        },
        options = options?.map { it.toSlashCommandOption() },
        autocomplete = autocomplete,
        minValue = minValue,
        maxValue = maxValue,
        minLength = minLength,
        maxLength = maxLength,
        untranslatedName = nameDefault ?: name,
    )

private fun normalizeCommandChoiceValue(type: ApplicationCommandType, value: Any): Any = when (type) {
    ApplicationCommandType.INTEGER -> (value as? Number)?.toLong() ?: value
    ApplicationCommandType.NUMBER -> (value as? Number)?.toDouble() ?: value
    ApplicationCommandType.STRING -> value.toString()
    else -> value
}

fun ApplicationCommand.getDescriptionText(resources: Resources): String? =
    descriptionRes?.let(resources::getString)?.replace("¯_(ツ)_/¯", "¯\\_(ツ)_/¯") ?: description

fun ApplicationCommandOption.getDescriptionText(resources: Resources): String? =
    descriptionRes?.let(resources::getString) ?: description

fun ApplicationCommandOption.getErrorText(resources: Resources): String = resources.getString(
    when {
        type == ApplicationCommandType.STRING && !choices.isNullOrEmpty() -> R.string.command_validation_choice_error
        type == ApplicationCommandType.INTEGER -> R.string.command_validation_integer_error
        type == ApplicationCommandType.NUMBER -> R.string.command_validation_number_error
        type == ApplicationCommandType.BOOLEAN -> R.string.command_validation_boolean_error
        type == ApplicationCommandType.USER -> R.string.command_validation_user_error
        type == ApplicationCommandType.CHANNEL -> R.string.command_validation_channel_error
        type == ApplicationCommandType.ROLE -> R.string.command_validation_role_error
        type == ApplicationCommandType.MENTIONABLE -> R.string.command_validation_mentionable_error
        else -> R.string.command_validation_required_error
    },
)

fun List<ApplicationCommandValue>.mapApplicationCommandValues(
    mappingFunction: (ApplicationCommandValue) -> ApplicationCommandValue,
): List<ApplicationCommandValue> = map { value ->
    val nested = when (value.type) {
        ApplicationCommandType.SUBCOMMAND.type ->
            value.copy(options = value.options?.map(mappingFunction))

        ApplicationCommandType.SUBCOMMAND_GROUP.type -> value.copy(
            options = value.options?.map { subcommand ->
                mappingFunction(subcommand.copy(options = subcommand.options?.map(mappingFunction)))
            },
        )

        else -> value
    }
    mappingFunction(nested)
}
