package com.discord.widgets.chat.input.applauncher

import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.api.applicationdirectory.ApplicationDirectoryCollection
import com.discord.api.applicationdirectory.ApplicationDirectoryCollectionItem
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResult
import com.discord.api.embeddedactivities.EmbeddedActivityConfig
import com.discord.api.embeddedactivities.EmbeddedActivityShelf
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.sortedForSection
import com.discord.utilities.time.TimeUtils

enum class AppLauncherRoute(val routeName: String) {
    HOME("home"),
    APPLICATION_VIEW("application_view"),
    COMMAND_VIEW("command_view"),
    APP_LIST_VIEW("app_list_view"),
}

enum class AppLauncherEntrypoint { NONE, TEXT, VOICE }

enum class AppLauncherCloseReason { DISMISSED, COMMAND, ACTIVITY }

enum class ActivityBadge { NEW, UPDATED }

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
    val badge: ActivityBadge? = null,
)

sealed interface AppLauncherRow {
    val key: String

    data class Header(
        @StringRes val titleRes: Int,
        val section: AppLauncherSectionName,
        /** Set for a collection shelf, whose title is server-supplied rather than a resource. */
        val title: String? = null,
    ) : AppLauncherRow {
        override val key = "header:${section.value}:${title.orEmpty()}"
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
        @StringRes val sectionTitleRes: Int,
        val section: AppLauncherSectionName,
        val applications: List<AppLauncherApplication> = emptyList(),
        val commands: List<ApplicationCommand> = emptyList(),
        val title: String? = null,
    ) : AppLauncherRow {
        override val key = "view-all:${section.value}:${title.orEmpty()}"
    }

    /**
     * A horizontally scrolling shelf. The reference lays "Recents" and "In This Server" out this way
     * rather than as stacked rows, and "Recents" carries both lists so the header can switch between
     * them without changing the list's position.
     */
    data class Scroller(
        @StringRes val titleRes: Int,
        val section: AppLauncherSectionName,
        val commands: List<CommandCell> = emptyList(),
        val applications: List<AppLauncherApplication> = emptyList(),
        val showsAppsAsTiles: Boolean = false,
        val canToggle: Boolean = false,
        val showsViewAll: Boolean = false,
    ) : AppLauncherRow {
        override val key = "scroller:${'$'}{section.value}"
    }

    data class CommandCell(
        val command: ApplicationCommand,
        val application: Application?,
    )

    data object LearnMore : AppLauncherRow {
        override val key = "learn-more"
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
    @StringRes val titleRes: Int? = null,
    val searchPinnedDivider: Boolean = false,
)

internal object AppLauncherRows {
    const val COLLAPSED_LIST_ITEM_MAX = 3
    private const val SHELF_ITEM_MAX = 8

    fun home(
        entrypoint: AppLauncherEntrypoint,
        commandApplications: List<Application>,
        frecentCommands: List<ApplicationCommand>,
        frecentApplications: List<Application>,
        installedApplications: List<Application>,
        serverApplications: List<Application>,
        shelf: EmbeddedActivityShelf?,
        directoryResults: List<ApplicationDirectorySearchResult>,
        collections: List<ApplicationDirectoryCollection>,
        loading: Boolean,
        hasPermission: Boolean,
        nsfwAllowed: Boolean,
    ): List<AppLauncherRow> {
        val rows = mutableListOf<AppLauncherRow>()
        val shownApplicationIds = HashSet<Long>()
        val isText = entrypoint == AppLauncherEntrypoint.TEXT
        val activities = shelfApplications(shelf, entrypoint, nsfwAllowed)
        val activitiesById = activities.associateBy(AppLauncherApplication::id)
        val activitiesOnly = entrypoint == AppLauncherEntrypoint.VOICE
        if (frecentCommands.isNotEmpty() && !activitiesOnly && !isText) {
            rows += commandSection(
                R.string.app_launcher_section_recent_commands,
                AppLauncherSectionName.RECENT_COMMANDS,
                frecentCommands,
                commandApplications,
            )
        }

        fun launcherApps(applications: List<Application>): List<AppLauncherApplication> = applications
            .filterNot(Application::builtIn)
            .map { application ->
                activitiesById[application.id]?.copy(commandApplication = application)
                    ?: fromCommandApplication(application)
            }

        /** Only the stacked voice sections dedupe; the text shelves deliberately overlap. */
        fun unique(applications: List<Application>): List<AppLauncherApplication> =
            launcherApps(applications.filter { shownApplicationIds.add(it.id) })

        val recent = (if (isText) launcherApps(frecentApplications) else unique(frecentApplications))
            .filter { !activitiesOnly || it.id in activitiesById }
        // The reference renders this shelf for both entrypoints; voice simply has no commands to
        // switch to, so it shows the app tiles alone.
        val commandCells = if (activitiesOnly) {
            emptyList()
        } else {
            frecentCommands.take(SHELF_ITEM_MAX).map { command ->
                AppLauncherRow.CommandCell(
                    command,
                    commandApplications.firstOrNull { it.id == command.applicationId },
                )
            }
        }
        if (commandCells.isNotEmpty() || recent.isNotEmpty()) {
            rows += AppLauncherRow.Scroller(
                titleRes = R.string.app_launcher_section_recents,
                section = AppLauncherSectionName.RECENT_APPS,
                commands = commandCells,
                applications = recent.take(SHELF_ITEM_MAX),
                showsAppsAsTiles = true,
                canToggle = commandCells.isNotEmpty() && recent.isNotEmpty(),
                showsViewAll = true,
            )
        }
        val installed = when {
            activitiesOnly -> emptyList()
            isText -> launcherApps(installedApplications)
            else -> unique(installedApplications)
        }
        if (installed.isNotEmpty() && !isText) {
            rows += applicationSection(R.string.app_launcher_section_installed, AppLauncherSectionName.INSTALLED, installed)
        }
        // The reference has no separate "Installed" shelf; everything usable here is "In This Server".
        val inServer = if (isText) {
            (launcherApps(serverApplications) + installed).distinctBy(AppLauncherApplication::id)
        } else {
            emptyList()
        }
        if (inServer.isNotEmpty()) {
            rows += AppLauncherRow.Scroller(
                titleRes = R.string.app_launcher_section_apps_in_server,
                section = AppLauncherSectionName.APPS_IN_THIS_SERVER,
                applications = inServer.take(SHELF_ITEM_MAX),
                showsViewAll = inServer.size > SHELF_ITEM_MAX,
            )
        }

        // The reference only lists activities on their own for the voice entrypoint; in text they
        // reach the user through a collection flagged to append whatever it did not already name.
        val remainingActivities = if (isText) emptyList() else activities.filter { shownApplicationIds.add(it.id) }
        if (remainingActivities.isNotEmpty()) {
            rows += AppLauncherRow.Header(R.string.app_launcher_section_activities, AppLauncherSectionName.ACTIVITIES)
            rows += remainingActivities.chunked(2).map { AppLauncherRow.ActivityPair(it[0], it.getOrNull(1)) }
        }
        if (isText) {
            val curatedIds = collections
                .flatMap(ApplicationDirectoryCollection::applicationDirectoryCollectionItems)
                .mapNotNull { it.application?.id }
                .toSet()
            val leftoverActivities = activities.filterNot { it.id in curatedIds }
            collections.forEach { collection ->
                // Collections are curated server-side and deliberately overlap the shelves above,
                // so they are not filtered against what those already showed.
                val curated = collection.applicationDirectoryCollectionItems
                    .filter { it.type == ApplicationDirectoryCollectionItem.TYPE_APPLICATION }
                    .sortedBy(ApplicationDirectoryCollectionItem::position)
                    .mapNotNull { item ->
                        item.application?.let { fromDirectoryApplication(it, promoted = item.promoted) }
                    }
                val appendsActivities = collection.flags and
                    ApplicationDirectoryCollection.FLAG_APPENDS_REMAINING_ACTIVITIES != 0
                val items = if (appendsActivities) curated + leftoverActivities else curated
                if (items.isEmpty()) return@forEach

                rows += AppLauncherRow.Header(
                    R.string.app_launcher_section_recommended,
                    AppLauncherSectionName.NEW_TO_APPS,
                    title = collection.title,
                )
                if (collection.type == ApplicationDirectoryCollection.TYPE_BANNER_CARDS ||
                    collection.type == ApplicationDirectoryCollection.TYPE_SMALL_BANNER_CARDS
                ) {
                    rows += items.chunked(2).map { AppLauncherRow.ActivityPair(it[0], it.getOrNull(1)) }
                } else {
                    val visible = items.take(COLLAPSED_LIST_ITEM_MAX)
                    rows += visible.mapIndexed { index, app ->
                        AppLauncherRow.ApplicationRow(app, index == 0, index == visible.lastIndex && items.size <= COLLAPSED_LIST_ITEM_MAX)
                    }
                    if (items.size > COLLAPSED_LIST_ITEM_MAX) {
                        rows += AppLauncherRow.ViewAll(
                            R.string.app_launcher_section_recommended,
                            AppLauncherSectionName.NEW_TO_APPS,
                            applications = items,
                            title = collection.title,
                        )
                    }
                }
            }
        }

        if (isText && rows.isNotEmpty()) rows += AppLauncherRow.LearnMore

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
        entrypoint: AppLauncherEntrypoint = AppLauncherEntrypoint.TEXT,
        activities: List<AppLauncherApplication> = emptyList(),
    ): List<AppLauncherRow> {
        if (entrypoint == AppLauncherEntrypoint.VOICE) {
            return searchActivities(query, activities, directoryResults, loading)
        }
        val rows = mutableListOf<AppLauncherRow>()
        if (commands.isNotEmpty()) {
            rows += commandSection(R.string.commands, AppLauncherSectionName.SEARCH, commands, applications, collapsed = false)
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
            rows += AppLauncherRow.Header(R.string.apps, AppLauncherSectionName.SEARCH)
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

    private fun searchActivities(
        query: String,
        activities: List<AppLauncherApplication>,
        directoryResults: List<ApplicationDirectorySearchResult>,
        loading: Boolean,
    ): List<AppLauncherRow> {
        val local = activities.filter { it.name.contains(query, ignoreCase = true) }
        val localIds = local.mapTo(HashSet()) { it.id }
        val global = directoryResults
            .map(::fromDirectoryResult)
            .filter { it.searchResultType == AppLauncherSearchResultType.ACTIVITY_APPLICATION }
            .filterNot { it.id in localIds }
        val results = local + global
        return when {
            results.isNotEmpty() ->
                results.chunked(2).map { AppLauncherRow.ActivityPair(it[0], it.getOrNull(1)) }
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
                    R.string.commands,
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
        @StringRes titleRes: Int,
        section: AppLauncherSectionName,
        applications: List<AppLauncherApplication>,
    ): List<AppLauncherRow> =
        listOf(AppLauncherRow.Header(titleRes, section)) + applicationSectionRows(titleRes, section, applications)

    private fun applicationSectionRows(
        @StringRes titleRes: Int,
        section: AppLauncherSectionName,
        applications: List<AppLauncherApplication>,
    ): List<AppLauncherRow> {
        val visible = applications.take(COLLAPSED_LIST_ITEM_MAX)
        return visible.mapIndexed { index, app ->
            AppLauncherRow.ApplicationRow(app, index == 0, index == visible.lastIndex)
        } + if (applications.size > visible.size) {
            listOf(AppLauncherRow.ViewAll(titleRes, section, applications = applications))
        } else {
            emptyList()
        }
    }

    private fun commandSection(
        @StringRes titleRes: Int,
        section: AppLauncherSectionName,
        commands: List<ApplicationCommand>,
        applications: List<Application>,
        collapsed: Boolean = true,
    ): List<AppLauncherRow> {
        val visible = if (collapsed) commands.take(COLLAPSED_LIST_ITEM_MAX) else commands
        val appMap = applications.associateBy(Application::id)
        return listOf(AppLauncherRow.Header(titleRes, section)) +
            visible.map { AppLauncherRow.CommandRow(it, appMap[it.applicationId]) } +
            if (collapsed && commands.size > visible.size) {
                listOf(AppLauncherRow.ViewAll(titleRes, section, commands = commands))
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

    fun fromDirectoryApplication(
        application: ApplicationDirectoryApplication,
        promoted: Boolean,
    ): AppLauncherApplication = AppLauncherApplication(
        application.id,
        application.name,
        application.directoryEntry?.shortDescription?.ifBlank { application.description } ?: application.description,
        application.icon,
        directoryApplication = application,
        promoted = promoted || application.flagsValue() and PROMOTED_FLAG != 0L,
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

    fun shelfApplications(
        shelf: EmbeddedActivityShelf?,
        entrypoint: AppLauncherEntrypoint = AppLauncherEntrypoint.TEXT,
        nsfwAllowed: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ): List<AppLauncherApplication> {
        if (shelf == null) return emptyList()
        val applications = shelf.applications.associateBy { it.id }
        val surface = if (entrypoint == AppLauncherEntrypoint.VOICE) VOICE_SURFACE else TEXT_SURFACE
        return shelf.activities
            .filter { ANDROID_PLATFORM in it.supportedPlatforms }
            .filterNot { it.requiresAgeGate && !nsfwAllowed }
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
                    imageUrl = shelf.assets[application.id]
                        ?.firstOrNull { (it.name ?: it.key) == "embedded_cover" }
                        ?.let { asset -> asset.id ?: asset.assetId?.toString() }
                        ?.let { "https://cdn.discordapp.com/app-assets/${application.id}/$it.webp?size=512" },
                    badge = config.badge(surface, now),
                ) to config.shelfRank
            }
            .promoteLabelled()
    }

    /** A labelled activity is pulled up to its `shelfRank`, never pushed down. */
    private fun List<Pair<AppLauncherApplication, Int>>.promoteLabelled(): List<AppLauncherApplication> {
        val ordered = mapTo(ArrayList(size)) { it.first }
        forEach { (application, shelfRank) ->
            if (application.badge == null) return@forEach
            val index = ordered.indexOfFirst { it.id == application.id }
            val rank = (shelfRank - 1).coerceAtLeast(0)
            if (rank < index) {
                ordered.removeAt(index)
                ordered.add(rank, application)
            }
        }
        return ordered
    }

    private fun EmbeddedActivityConfig.badge(surface: String, now: Long): ActivityBadge? {
        val platform = clientPlatformConfig[ANDROID_PLATFORM] ?: return null
        if (surface in platform.omitBadgeFromSurfaces) return null
        val from = platform.labelFrom?.let(TimeUtils::parseUTCDate) ?: return null
        val until = platform.labelUntil?.let(TimeUtils::parseUTCDate) ?: return null
        if (now < from || now > until) return null
        return when (platform.labelType) {
            LABEL_TYPE_NEW -> ActivityBadge.NEW
            LABEL_TYPE_UPDATED -> ActivityBadge.UPDATED
            else -> null
        }
    }

    private fun ApplicationDirectoryApplication.flagsValue(): Long =
        flagsNew?.toLongOrNull() ?: flags ?: 0L

    private const val PROMOTED_FLAG = 1L shl 29
    private const val ANDROID_PLATFORM = "android"
    private const val VOICE_SURFACE = "voice_launcher"
    private const val TEXT_SURFACE = "text_launcher"
    private const val LABEL_TYPE_NEW = 1
    private const val LABEL_TYPE_UPDATED = 2
}
