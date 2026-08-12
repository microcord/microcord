package com.discord.widgets.chat.input.applauncher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResult
import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.commands.ApplicationCommandIndex
import com.discord.api.commands.ApplicationIntegrationType
import com.discord.api.embeddedactivities.EmbeddedActivityShelf
import com.discord.api.permission.Permission
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.CommandPermissionContext
import com.discord.models.commands.hasAccess
import com.discord.restapi.ApplicationDirectoryAPI
import com.discord.stores.StoreApplicationCommands
import com.discord.stores.StoreApplicationDirectory
import com.discord.stores.StoreApplicationCommandFrecency
import com.discord.stores.StoreEmbeddedActivities
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsUtils
import com.discord.utilities.applicationcommands.CommandPermissionContexts
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription

class AppLauncherViewModel(
    private val channelId: Long,
    private val entrypoint: AppLauncherEntrypoint,
    private val commandsStore: StoreApplicationCommands = StoreStream.getApplicationCommands(),
    private val directoryStore: StoreApplicationDirectory = StoreStream.getApplicationDirectory(),
    private val embeddedActivitiesStore: StoreEmbeddedActivities = StoreStream.getEmbeddedActivities(),
    private val applicationFrecencyStore: StoreApplicationCommandFrecency = StoreStream.getApplicationCommandFrecency(),
    private val channelProvider: (Long) -> Channel? = StoreStream.getChannels()::getChannel,
    private val permissionsProvider: (Long) -> Long? = {
        StoreStream.getPermissions().getPermissionsByChannel()[it]
    },
    private val permissionContextProvider: (Channel) -> CommandPermissionContext =
        CommandPermissionContexts::snapshot,
    private val fetchShelf: (Long?) -> Observable<EmbeddedActivityShelf> = { guildId ->
        RestAPI.embeddedActivitiesApiOrNull?.let { embeddedActivitiesStore.fetchShelf(it, guildId, force = true) }
            ?: Observable.error(IllegalStateException("Embedded activities API unavailable"))
    },
    scheduler: Scheduler = Schedulers.computation(),
    private val trackSearch: (String, AppLauncherEntrypoint) -> Unit = { query, source ->
        AnalyticsUtils.Tracker.instance.track(
            "app_launcher_search_query_typed",
            mapOf("query" to query, "source" to source.name),
        )
    },
) : ViewModel() {
    private val subscriptions = CompositeSubscription()
    private val backStack = ArrayDeque<AppLauncherRoute>()
    private val queries = PublishSubject.create<String>().toSerialized()
    private val stateSubject = BehaviorSubject.create(AppLauncherViewState())

    private val channel = channelProvider(channelId)
    private val guildId = channel?.guildId?.takeUnless { it == 0L }
    private val permissionContext = channel?.let(permissionContextProvider)
    private var applications: List<Application> = emptyList()
    private var installedApplications: List<Application> = emptyList()
    private var serverApplications: List<Application> = emptyList()
    private var commands: List<ApplicationCommand> = emptyList()
    private var frecentCommands: List<ApplicationCommand> = emptyList()
    private var frecentCommandIds: List<String> = emptyList()
    private var shelf: EmbeddedActivityShelf? = null
    private var directoryResults: List<ApplicationDirectorySearchResult> = emptyList()
    private var commandsLoading = true
    private var directoryLoading = true
    private var installedLoading = true
    private var serverLoading = entrypoint == AppLauncherEntrypoint.TEXT
    private var shelfLoading = shouldLoadActivities()
    private var query = ""
    private var route = AppLauncherRoute.HOME
    private var selectedApplication: AppLauncherApplication? = null
    private var selectedCommand: ApplicationCommand? = null
    private var viewAllRows: List<AppLauncherRow> = emptyList()
    private var viewAllTitleRes: Int? = null
    private var searchPinnedDivider = false
    private var pendingRestoreRoute: AppLauncherRoute? = null
    private var pendingRestoreApplicationId: Long? = null
    private var pendingRestoreCommandId: String? = null

    init {
        subscriptions.add(
            queries.debounce(INTERACTION_DELAY_MS, TimeUnit.MILLISECONDS, scheduler).subscribe(::runSearch),
        )
        subscriptions.add(
            queries.debounce(ANALYTICS_DEBOUNCE_MS, TimeUnit.MILLISECONDS, scheduler)
                .subscribe { trackSearch(it, entrypoint) },
        )
        subscriptions.add(
            commandsStore.observeGuildApplications(channelId).subscribe(
                {
                    applications = it
                    installedApplications = it.filter { application ->
                        ApplicationIntegrationType.USER_INSTALL.value in application.installationTypes
                    }
                    serverApplications = it.filter { application ->
                        ApplicationIntegrationType.GUILD_INSTALL.value in application.installationTypes
                    }
                    installedLoading = false
                    serverLoading = false
                    publish()
                },
                {
                    commandsLoading = false
                    publish()
                },
            ),
        )
        subscriptions.add(
            commandsStore.observeQueryCommands(channelId).subscribe(
                {
                    commands = accessible(it)
                    commandsLoading = false
                    publish()
                },
                {
                    commandsLoading = false
                    publish()
                },
            ),
        )
        subscriptions.add(
            commandsStore.observeDiscoverCommands(channelId).subscribe(
                {
                    if (query.isBlank()) commands = accessible(it.commands)
                    commandsLoading = it.loadState.isLoading()
                    publish()
                },
                {
                    commandsLoading = false
                    publish()
                },
            ),
        )
        subscriptions.add(
            commandsStore.observeFrecencyCommands(guildId ?: 0L).subscribe(
                {
                    frecentCommands = accessible(it)
                    publish()
                },
                { publish() },
            ),
        )
        subscriptions.add(
            applicationFrecencyStore.observeTopCommandIds(guildId).subscribe(
                {
                    frecentCommandIds = it
                    publish()
                },
                { publish() },
            ),
        )
        subscriptions.add(
            directoryStore.observeState().subscribe(::onDirectoryState, {
                directoryLoading = false
                publish()
            }),
        )
        subscriptions.add(
            embeddedActivitiesStore.observeShelf(guildId).subscribe(
                {
                    shelf = it.shelf
                    if (it.shelf != null || it.error != null) shelfLoading = false
                    publish()
                },
                {
                    shelfLoading = false
                    publish()
                },
            ),
        )

        if (shelfLoading) {
            subscriptions.add(fetchShelf(guildId).restSubscribeOn(false).subscribe(
                {
                    shelf = it
                    shelfLoading = false
                    publish()
                },
                {
                    shelfLoading = false
                    publish()
                },
            ))
        }

        channel?.let {
            commandsStore.requestApplicationCommandIndex(
                channelId = it.id,
                guildId = guildId,
                isBotDm = it.recipients.orEmpty().any { recipient -> recipient.bot == true },
            )
        }
        directoryStore.search("", source = ApplicationDirectoryAPI.SOURCE_APP_LAUNCHER)
    }

    fun observeState(): Observable<AppLauncherViewState> = stateSubject.distinctUntilChanged()

    fun setQuery(value: String) {
        query = value.take(MAX_QUERY_LENGTH)
        route = AppLauncherRoute.HOME
        backStack.clear()
        publish()
        queries.onNext(query)
    }

    fun setSearchPinnedDivider(visible: Boolean) {
        if (searchPinnedDivider == visible) return
        searchPinnedDivider = visible
        publish()
    }

    fun selectApplication(application: AppLauncherApplication) {
        backStack.addLast(route)
        selectedApplication = application
        selectedCommand = null
        route = AppLauncherRoute.APPLICATION_VIEW
        application.directoryApplication?.let { directoryStore.loadApplication(it.id) }
        publish()
    }

    fun selectCommand(command: ApplicationCommand) {
        backStack.addLast(route)
        selectedCommand = command
        if (selectedApplication?.id != command.applicationId) {
            selectedApplication = applications.firstOrNull { it.id == command.applicationId }
                ?.let(AppLauncherRows::fromCommandApplication)
        }
        route = AppLauncherRoute.COMMAND_VIEW
        publish()
    }

    fun showViewAll(row: AppLauncherRow.ViewAll) {
        backStack.addLast(route)
        viewAllTitleRes = row.sectionTitleRes
        viewAllRows = if (row.applications.isNotEmpty()) {
            row.applications.mapIndexed { index, application ->
                AppLauncherRow.ApplicationRow(application, index == 0, index == row.applications.lastIndex)
            }
        } else {
            val applicationMap = applications.associateBy(Application::id)
            row.commands.map { AppLauncherRow.CommandRow(it, applicationMap[it.applicationId]) }
        }
        route = AppLauncherRoute.APP_LIST_VIEW
        publish()
    }

    fun navigateBack(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        route = previous
        selectedCommand = null
        if (previous == AppLauncherRoute.HOME) selectedApplication = null
        publish()
        return true
    }

    fun restore(routeName: String?, selectedApplicationId: Long?, selectedCommandId: String?) {
        pendingRestoreRoute = AppLauncherRoute.entries.firstOrNull { it.routeName == routeName } ?: return
        pendingRestoreApplicationId = selectedApplicationId
        pendingRestoreCommandId = selectedCommandId
        resolvePendingRestore()
        publish()
    }

    fun currentState(): AppLauncherViewState = stateSubject.value ?: AppLauncherViewState()

    private fun runSearch(value: String) {
        if (value.isBlank()) {
            commandsStore.clearQueryCommands()
        } else if (entrypoint == AppLauncherEntrypoint.TEXT) {
            commandsLoading = true
            commandsStore.requestApplicationCommandsQuery(guildId, value)
        }
        directoryLoading = true
        directoryStore.search(value, source = ApplicationDirectoryAPI.SOURCE_APP_LAUNCHER)
        publish()
    }

    private fun onDirectoryState(state: StoreApplicationDirectory.State) {
        val search = state.search
        if (search.source == ApplicationDirectoryAPI.SOURCE_APP_LAUNCHER) {
            directoryResults = search.results
            directoryLoading = search.loading
        }
        val selected = selectedApplication
        if (selected != null) {
            val detail = state.details[selected.id]
            if (detail is StoreApplicationDirectory.DetailState.Loaded) {
                selectedApplication = AppLauncherRows.fromDirectoryResult(
                    ApplicationDirectorySearchResult(selected.searchResultType.value, detail.application),
                ).copy(commandApplication = selected.commandApplication)
            }
        }
        publish()
    }

    @Synchronized
    private fun publish() {
        resolvePendingRestore()
        val state = when (route) {
            AppLauncherRoute.HOME -> AppLauncherViewState(
                route = route,
                query = query,
                rows = if (query.isBlank()) {
                    AppLauncherRows.home(
                        entrypoint,
                        applications,
                        pinnedFrecentCommands(),
                        frecentApplications(),
                        installedApplications,
                        serverApplications,
                        shelf,
                        directoryResults,
                        commandsLoading || directoryLoading || installedLoading || serverLoading || shelfLoading,
                        hasLauncherPermission(),
                    )
                } else {
                    AppLauncherRows.search(query, commands, applications, directoryResults, commandsLoading || directoryLoading)
                },
                titleRes = R.string.apps,
                searchPinnedDivider = searchPinnedDivider,
            )
            AppLauncherRoute.APPLICATION_VIEW -> {
                val application = selectedApplication
                AppLauncherViewState(
                    route = route,
                    rows = application?.let {
                        AppLauncherRows.application(it, commands.filter { command -> command.applicationId == it.id }, commandsLoading)
                    } ?: listOf(AppLauncherRow.Empty(AppLauncherEmptyStateType.HOME_EMPTY)),
                    selectedApplication = application,
                    title = application?.name.orEmpty(),
                )
            }
            AppLauncherRoute.COMMAND_VIEW -> {
                val command = selectedCommand
                AppLauncherViewState(
                    route = route,
                    rows = command?.let { AppLauncherRows.command(it, selectedApplication?.commandApplication) }
                        ?: listOf(AppLauncherRow.Empty(AppLauncherEmptyStateType.COMMAND_NOT_FOUND)),
                    selectedApplication = selectedApplication,
                    selectedCommand = command,
                    title = command?.name.orEmpty(),
                )
            }
            AppLauncherRoute.APP_LIST_VIEW -> AppLauncherViewState(
                route = route,
                rows = viewAllRows,
                titleRes = viewAllTitleRes,
            )
        }
        stateSubject.onNext(state)
    }

    private fun resolvePendingRestore() {
        val restoredRoute = pendingRestoreRoute ?: return
        pendingRestoreApplicationId?.let { id ->
            selectedApplication = applications.firstOrNull { it.id == id }?.let(AppLauncherRows::fromCommandApplication)
                ?: directoryResults.firstOrNull { it.data.id == id }?.let(AppLauncherRows::fromDirectoryResult)
        }
        pendingRestoreCommandId?.let { id ->
            selectedCommand = (commandsStore.getDiscoverCommands().commands + commandsStore.getQueryCommands())
                .firstOrNull { it.id == id }
        }
        val restored = when (restoredRoute) {
            AppLauncherRoute.APPLICATION_VIEW -> selectedApplication != null
            AppLauncherRoute.COMMAND_VIEW -> selectedCommand != null
            AppLauncherRoute.APP_LIST_VIEW -> viewAllRows.isNotEmpty()
            AppLauncherRoute.HOME -> true
        }
        if (!restored) return
        route = restoredRoute
        pendingRestoreRoute = null
        pendingRestoreApplicationId = null
        pendingRestoreCommandId = null
    }

    /**
     * The index hands back everything the guild has installed; the reference filters it against
     * the viewer before any of it reaches a list.
     */
    private fun accessible(source: List<ApplicationCommand>): List<ApplicationCommand> {
        val context = permissionContext ?: return source
        val applicationsById =
            (applications + installedApplications + serverApplications).associateBy(Application::id)
        return source.filter { it.hasAccess(context, applicationsById[it.applicationId]) }
    }

    private fun hasLauncherPermission(): Boolean {
        val currentChannel = channel ?: return false
        if (currentChannel.isPrivate()) return true
        val permissions = permissionsProvider(channelId)
        return PermissionUtils.can(Permission.USE_APPLICATION_COMMANDS, permissions)
    }

    private fun shouldLoadActivities(): Boolean {
        if (entrypoint == AppLauncherEntrypoint.VOICE) return true
        if (entrypoint != AppLauncherEntrypoint.TEXT) return false
        val currentChannel = channel ?: return false
        if (currentChannel.type !in ACTIVITY_CHANNEL_TYPES) return false
        // A category is the only parent an activity-capable channel may have, which is what
        // keeps threads and forum posts out.
        val parent = currentChannel.parentId.takeIf { it != 0L }?.let(channelProvider)
        if (parent != null && parent.type != Channel.CATEGORY) return false
        return currentChannel.isPrivate() ||
            PermissionUtils.can(Permission.USE_EMBEDDED_ACTIVITIES, permissionsProvider(channelId))
    }

    /** The command that was just run leads the row, ahead of the ranking. */
    private fun pinnedFrecentCommands(): List<ApplicationCommand> {
        val lastUsedId = applicationFrecencyStore.getLastUsedCommandId() ?: return frecentCommands
        val lastUsed = (frecentCommands + commands).firstOrNull { it.id == lastUsedId }
            ?: return frecentCommands
        return listOf(lastUsed) + frecentCommands.filterNot { it.id == lastUsedId }
    }

    private fun frecentApplications(): List<Application> {
        val commandApplicationIds = (frecentCommands + commands)
            .associate { it.id to it.applicationId }
        val applicationsById = (applications + installedApplications + serverApplications).associateBy(Application::id)
        return frecentCommandIds.mapNotNull(commandApplicationIds::get)
            .distinct()
            .mapNotNull(applicationsById::get)
    }

    override fun onCleared() {
        subscriptions.unsubscribe()
        super.onCleared()
    }

    class Factory(
        private val channelId: Long,
        private val entrypoint: AppLauncherEntrypoint,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppLauncherViewModel(channelId, entrypoint) as T
    }

    companion object {
        const val INTERACTION_DELAY_MS = 100L
        const val ANALYTICS_DEBOUNCE_MS = 400L
        const val MAX_QUERY_LENGTH = 100

        private val ACTIVITY_CHANNEL_TYPES = setOf(
            Channel.GUILD_TEXT,
            Channel.GUILD_VOICE,
            Channel.DM,
            Channel.GROUP_DM,
        )

        internal fun indexApplications(index: ApplicationCommandIndex, installationType: Int): List<Application> {
            val commandCounts = index.applicationCommands.groupingBy { it.applicationId }.eachCount()
            return index.applications.map { application ->
                Application(
                    id = application.id,
                    name = application.bot?.username ?: application.name,
                    icon = application.icon,
                    commandCount = commandCounts[application.id] ?: 0,
                    bot = application.bot,
                    permissions = application.permissions,
                    installationTypes = setOf(installationType),
                )
            }
        }
    }
}
