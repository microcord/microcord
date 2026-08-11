package com.discord.widgets.chat.input.applauncher

import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResult
import com.discord.api.embeddedactivities.EmbeddedActivityShelf
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.sortedForSection

enum class AppLauncherRoute(val routeName: String) {
    HOME("home"),
    APPLICATION_VIEW("application_view"),
    COMMAND_VIEW("command_view"),
    APP_LIST_VIEW("app_list_view"),
}

enum class AppLauncherEntrypoint { NONE, TEXT, VOICE }

enum class AppLauncherCloseReason { DISMISSED, COMMAND, ACTIVITY }

enum class AppLauncherEmptyStateType {
    HOME_EMPTY,
    SEARCH_EMPTY,
    HOME_NO_PERMISSIONS,
    COMMAND_NO_PERMISSIONS,
    COMMAND_NOT_FOUND,
}

enum class AppLauncherSearchResultType(val value: Int) {
    APPLICATION(1),
    ACTIVITY_APPLICATION(2),
    CONNECTION(3),
}

enum class AppLauncherSectionName(val value: String) {
    SEARCH("search"),
    RECENT("recent"),
    INSTALLED("installed"),
    ACTIVITIES("activities"),
    RECENT_APPS("recent_apps"),
    RECENT_COMMANDS("recent_commands"),
    NEW_TO_APPS("new_to_apps"),
    APPS_IN_THIS_SERVER("apps_in_this_server"),
}

data class AppLauncherApplication(
    val id: Long,
    val name: String,
    val description: String,
    val icon: String?,
    val directoryApplication: ApplicationDirectoryApplication? = null,
    val commandApplication: Application? = null,
    val searchResultType: AppLauncherSearchResultType = AppLauncherSearchResultType.APPLICATION,
    val promoted: Boolean = false,
    val imageUrl: String? = null,
    /**
     * Shown in place of the description when the activity cannot run here. The reference keeps
     * the row tappable anyway when the cause is the developer's own platform list, so the app
     * gets the chance to explain itself rather than the row silently vanishing.
     */
    @StringRes val unavailableReasonRes: Int? = null,
)

sealed interface AppLauncherRow {
    val key: String

    data class Header(
        val title: String,
        val section: AppLauncherSectionName,
    ) : AppLauncherRow {
        override val key = "header:${section.value}"
    }

    data class ApplicationRow(
        val application: AppLauncherApplication,
        val first: Boolean = false,
        val last: Boolean = false,
    ) : AppLauncherRow {
        override val key = "app:${application.id}"
    }

    data class ActivityPair(
        val first: AppLauncherApplication,
        val second: AppLauncherApplication?,
    ) : AppLauncherRow {
        override val key = "activity:${first.id}:${second?.id ?: 0}"
    }

    data class Banner(
        val application: AppLauncherApplication,
        val interactive: Boolean = true,
    ) : AppLauncherRow {
        override val key = "banner:${application.id}"
    }

    data class CommandRow(
        val command: ApplicationCommand,
        val application: Application?,
    ) : AppLauncherRow {
        override val key = "command:${command.id}"
    }

    data class ViewAll(
        val title: String,
        val section: AppLauncherSectionName,
        val applications: List<AppLauncherApplication> = emptyList(),
        val commands: List<ApplicationCommand> = emptyList(),
    ) : AppLauncherRow {
        override val key = "view-all:${section.value}"
    }

    data class Empty(val type: AppLauncherEmptyStateType) : AppLauncherRow {
        override val key = "empty:$type"
    }

    data object Loading : AppLauncherRow {
        override val key = "loading"
    }
}

data class AppLauncherViewState(
    val route: AppLauncherRoute = AppLauncherRoute.HOME,
    val query: String = "",
    val rows: List<AppLauncherRow> = listOf(AppLauncherRow.Loading),
    val selectedApplication: AppLauncherApplication? = null,
    val selectedCommand: ApplicationCommand? = null,
    val title: String = "",
    val searchPinnedDivider: Boolean = false,
)

internal object AppLauncherRows {
    const val COLLAPSED_LIST_ITEM_MAX = 3

    fun home(
        entrypoint: AppLauncherEntrypoint,
        commandApplications: List<Application>,
        frecentCommands: List<ApplicationCommand>,
        frecentApplications: List<Application>,
        installedApplications: List<Application>,
        serverApplications: List<Application>,
        shelf: EmbeddedActivityShelf?,
        directoryResults: List<ApplicationDirectorySearchResult>,
        loading: Boolean,
        hasPermission: Boolean,
    ): List<AppLauncherRow> {
        val rows = mutableListOf<AppLauncherRow>()
        val shownApplicationIds = HashSet<Long>()
        val activities = shelfApplications(shelf)
        val activitiesById = activities.associateBy(AppLauncherApplication::id)
        if (frecentCommands.isNotEmpty()) {
            rows += commandSection(
                "Recent commands",
                AppLauncherSectionName.RECENT_COMMANDS,
                frecentCommands,
                commandApplications,
            )
        }

        fun unique(applications: List<Application>): List<AppLauncherApplication> = applications
            .filterNot(Application::builtIn)
            .filter { shownApplicationIds.add(it.id) }
            .map { application ->
                activitiesById[application.id]?.copy(commandApplication = application)
                    ?: fromCommandApplication(application)
            }

        val recent = unique(frecentApplications)
        if (recent.isNotEmpty()) {
            rows += applicationSection("Recents", AppLauncherSectionName.RECENT_APPS, recent)
        }
        val installed = unique(installedApplications)
        if (installed.isNotEmpty()) {
            rows += applicationSection("Installed", AppLauncherSectionName.INSTALLED, installed)
        }
        val inServer = if (entrypoint == AppLauncherEntrypoint.TEXT) unique(serverApplications) else emptyList()
        if (inServer.isNotEmpty()) {
            rows += applicationSection(
                "Apps in this server",
                AppLauncherSectionName.APPS_IN_THIS_SERVER,
                inServer,
            )
        }

        val remainingActivities = activities.filter { shownApplicationIds.add(it.id) }
        if (remainingActivities.isNotEmpty()) {
            rows += AppLauncherRow.Header("Activities", AppLauncherSectionName.ACTIVITIES)
            rows += remainingActivities.chunked(2).map { AppLauncherRow.ActivityPair(it[0], it.getOrNull(1)) }
        }
        if (entrypoint == AppLauncherEntrypoint.TEXT) {
            val recommendations = directoryResults
                .map(::fromDirectoryResult)
                .filterNot { it.searchResultType == AppLauncherSearchResultType.ACTIVITY_APPLICATION }
                .filter { shownApplicationIds.add(it.id) }
            if (recommendations.isNotEmpty()) {
                rows += AppLauncherRow.Header("Recommended", AppLauncherSectionName.NEW_TO_APPS)
                rows += AppLauncherRow.Banner(recommendations.first())
                rows += applicationSectionRows(AppLauncherSectionName.NEW_TO_APPS, recommendations.drop(1))
            }
        }

        return when {
            rows.isNotEmpty() -> rows
            loading -> listOf(AppLauncherRow.Loading)
            !hasPermission -> listOf(AppLauncherRow.Empty(AppLauncherEmptyStateType.HOME_NO_PERMISSIONS))
            else -> listOf(AppLauncherRow.Empty(AppLauncherEmptyStateType.HOME_EMPTY))
        }
    }

    fun search(
        query: String,
        commands: List<ApplicationCommand>,
        applications: List<Application>,
        directoryResults: List<ApplicationDirectorySearchResult>,
        loading: Boolean,
    ): List<AppLauncherRow> {
        val rows = mutableListOf<AppLauncherRow>()
        if (commands.isNotEmpty()) {
            rows += commandSection("Commands", AppLauncherSectionName.SEARCH, commands, applications, collapsed = false)
        }
        val local = applications.filterNot(Application::builtIn)
            .filter { it.name.contains(query, ignoreCase = true) }
            .map(::fromCommandApplication)
        val localIds = local.mapTo(HashSet()) { it.id }
        val global = directoryResults
            .filterNot { it.data.id in localIds }
            .map(::fromDirectoryResult)
        val applicationResults = local + global
        if (applicationResults.isNotEmpty()) {
            rows += AppLauncherRow.Header("Apps", AppLauncherSectionName.SEARCH)
            rows += applicationResults.mapIndexed { index, app ->
                AppLauncherRow.ApplicationRow(app, index == 0, index == applicationResults.lastIndex)
            }
        }
        return when {
            rows.isNotEmpty() -> rows
            loading -> listOf(AppLauncherRow.Loading)
            else -> listOf(AppLauncherRow.Empty(AppLauncherEmptyStateType.SEARCH_EMPTY))
        }
    }

    fun application(
        application: AppLauncherApplication,
        commands: List<ApplicationCommand>,
        loading: Boolean,
    ): List<AppLauncherRow> = buildList {
        add(AppLauncherRow.Banner(application, interactive = false))
        if (commands.isNotEmpty()) {
            addAll(
                commandSection(
                    "Commands",
                    AppLauncherSectionName.INSTALLED,
                    commands.sortedForSection(),
                    listOfNotNull(application.commandApplication),
                    collapsed = false,
                ),
            )
        } else if (loading) {
            add(AppLauncherRow.Loading)
        }
    }

    fun command(command: ApplicationCommand, application: Application?): List<AppLauncherRow> =
        listOf(AppLauncherRow.CommandRow(command, application))

    private fun applicationSection(
        title: String,
        section: AppLauncherSectionName,
        applications: List<AppLauncherApplication>,
    ): List<AppLauncherRow> = listOf(AppLauncherRow.Header(title, section)) + applicationSectionRows(section, applications)

    private fun applicationSectionRows(
        section: AppLauncherSectionName,
        applications: List<AppLauncherApplication>,
    ): List<AppLauncherRow> {
        val visible = applications.take(COLLAPSED_LIST_ITEM_MAX)
        return visible.mapIndexed { index, app ->
            AppLauncherRow.ApplicationRow(app, index == 0, index == visible.lastIndex)
        } + if (applications.size > visible.size) {
            listOf(AppLauncherRow.ViewAll("View all", section, applications = applications))
        } else {
            emptyList()
        }
    }

    private fun commandSection(
        title: String,
        section: AppLauncherSectionName,
        commands: List<ApplicationCommand>,
        applications: List<Application>,
        collapsed: Boolean = true,
    ): List<AppLauncherRow> {
        val visible = if (collapsed) commands.take(COLLAPSED_LIST_ITEM_MAX) else commands
        val appMap = applications.associateBy(Application::id)
        return listOf(AppLauncherRow.Header(title, section)) +
            visible.map { AppLauncherRow.CommandRow(it, appMap[it.applicationId]) } +
            if (collapsed && commands.size > visible.size) {
                listOf(AppLauncherRow.ViewAll("View all", section, commands = commands))
            } else {
                emptyList()
            }
    }

    fun fromCommandApplication(application: Application) = AppLauncherApplication(
        application.id,
        application.name,
        "",
        application.icon,
        commandApplication = application,
    )

    fun fromDirectoryResult(result: ApplicationDirectorySearchResult): AppLauncherApplication {
        val application = result.data
        return AppLauncherApplication(
            application.id,
            application.name,
            application.directoryEntry?.shortDescription?.ifBlank { application.description } ?: application.description,
            application.icon,
            directoryApplication = application,
            searchResultType = AppLauncherSearchResultType.entries.firstOrNull { it.value == result.type }
                ?: AppLauncherSearchResultType.APPLICATION,
            promoted = application.flagsValue() and PROMOTED_FLAG != 0L,
        )
    }

    fun shelfApplications(shelf: EmbeddedActivityShelf?): List<AppLauncherApplication> {
        if (shelf == null) return emptyList()
        val applications = shelf.applications.associateBy { it.id }
        return shelf.activities
            .sortedBy { it.shelfRank }
            .mapNotNull { config ->
                val id = config.applicationId ?: return@mapNotNull null
                val application = applications[id] ?: return@mapNotNull null
                val directoryApplication = ApplicationDirectoryApplication(
                    id = application.id,
                    name = application.name,
                    description = application.description,
                    icon = application.icon,
                    coverImage = application.coverImage,
                    splash = application.splash,
                    flags = application.flags,
                    flagsNew = application.flagsNew,
                )
                AppLauncherApplication(
                    id = application.id,
                    name = application.name,
                    description = application.description,
                    icon = application.icon,
                    directoryApplication = directoryApplication,
                    searchResultType = AppLauncherSearchResultType.ACTIVITY_APPLICATION,
                    unavailableReasonRes = R.string.app_launcher_activity_not_available_on_device
                        .takeIf { "android" !in config.supportedPlatforms },
                    imageUrl = shelf.assets[application.id]
                        ?.firstOrNull { (it.name ?: it.key) == "embedded_cover" }
                        ?.let { asset -> asset.id ?: asset.assetId?.toString() }
                        ?.let { "https://cdn.discordapp.com/app-assets/${application.id}/$it.webp?size=512" },
                )
            }
    }

    private fun ApplicationDirectoryApplication.flagsValue(): Long =
        flagsNew?.toLongOrNull() ?: flags ?: 0L

    private const val PROMOTED_FLAG = 1L shl 29
}
