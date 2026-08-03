package com.discord.widgets.chat.input.applauncher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResult
import com.discord.api.channel.isPrivate
import com.discord.api.permission.Permission
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.restapi.ApplicationDirectoryAPI
import com.discord.stores.StoreApplicationCommands
import com.discord.stores.StoreApplicationDirectory
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsUtils
import com.discord.utilities.permissions.PermissionUtils
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
    scheduler: Scheduler = Schedulers.computation(),
    private val trackSearch: (String, AppLauncherEntrypoint) -> Unit = { query, source ->
        AnalyticsUtils.Tracker.instance.track(
            "app_launcher_search_query_typed",
            mapOf("query" to query, "source" to source.name),
        )
    },
) : ViewModel() {
    private val subscriptions = CompositeSubscription()
    private val queries = PublishSubject.create<String>().toSerialized()
    private val stateSubject = BehaviorSubject.create(AppLauncherViewState())

    private val channel = StoreStream.getChannels().getChannel(channelId)
    private val guildId = channel?.guildId?.takeUnless { it == 0L }
    private var applications: List<Application> = emptyList()
    private var commands: List<ApplicationCommand> = emptyList()
    private var frecentCommands: List<ApplicationCommand> = emptyList()
    private var directoryResults: List<ApplicationDirectorySearchResult> = emptyList()
    private var commandsLoading = true
    private var directoryLoading = true
    private var query = ""
    private var route = AppLauncherRoute.HOME
    private var selectedApplication: AppLauncherApplication? = null
    private var selectedCommand: ApplicationCommand? = null
    private var viewAllRows: List<AppLauncherRow> = emptyList()
    private var viewAllTitle: String = ""
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
                    commands = it
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
                    if (query.isBlank()) commands = it.commands
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
                    frecentCommands = it
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

        channel?.let {
            commandsStore.requestApplicationCommandIndex(
                channelId = it.id,
                guildId = guildId,
                isBotDm = it.recipients.orEmpty().any { recipient -> recipient.bot == true },
            )
            commandsStore.requestInitialApplicationCommands(guildId, null, it.isPrivate())
        }
        directoryStore.search("", source = ApplicationDirectoryAPI.SOURCE_APP_LAUNCHER)
    }

    fun observeState(): Observable<AppLauncherViewState> = stateSubject.distinctUntilChanged()

    fun setQuery(value: String) {
        query = value.take(MAX_QUERY_LENGTH)
        route = AppLauncherRoute.HOME
        publish()
        queries.onNext(query)
    }

    fun setSearchPinnedDivider(visible: Boolean) {
        if (searchPinnedDivider == visible) return
        searchPinnedDivider = visible
        publish()
    }

    fun selectApplication(application: AppLauncherApplication) {
        selectedApplication = application
        selectedCommand = null
        route = AppLauncherRoute.APPLICATION_VIEW
        application.directoryApplication?.let { directoryStore.loadApplication(it.id) }
        commandsStore.requestInitialApplicationCommands(guildId, application.id, channel?.isPrivate() == true)
        publish()
    }

    fun selectCommand(command: ApplicationCommand) {
        selectedCommand = command
        selectedApplication = applications.firstOrNull { it.id == command.applicationId }
            ?.let(AppLauncherRows::fromCommandApplication)
        route = AppLauncherRoute.COMMAND_VIEW
        publish()
    }

    fun showViewAll(row: AppLauncherRow.ViewAll) {
        viewAllTitle = row.title
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
        if (route == AppLauncherRoute.HOME) return false
        route = AppLauncherRoute.HOME
        selectedApplication = null
        selectedCommand = null
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
                        frecentCommands,
                        directoryResults,
                        commandsLoading || directoryLoading,
                        hasLauncherPermission(),
                    )
                } else {
                    AppLauncherRows.search(query, commands, applications, directoryResults, commandsLoading || directoryLoading)
                },
                title = "Apps",
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
                title = viewAllTitle,
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

    private fun hasLauncherPermission(): Boolean {
        val currentChannel = channel ?: return false
        if (currentChannel.isPrivate()) return true
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()[channelId]
        return PermissionUtils.can(Permission.USE_APPLICATION_COMMANDS, permissions)
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
    }
}
