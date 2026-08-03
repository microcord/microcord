package com.discord.widgets.chat.input.applauncher

import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResult
import com.discord.api.commands.ApplicationCommandIndex
import com.discord.api.commands.ApplicationCommandIndexApplication
import com.discord.api.commands.ApplicationIntegrationType
import com.discord.api.embeddedactivities.EmbeddedActivityAsset
import com.discord.api.embeddedactivities.EmbeddedActivityConfig
import com.discord.api.embeddedactivities.EmbeddedActivityShelf
import com.discord.api.embeddedactivities.EmbeddedActivityShelfApplication
import com.discord.models.commands.Application
import com.discord.models.commands.BuiltInCommand
import com.discord.restapi.ApplicationDirectoryAPI
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLauncherStateTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }

    @Test
    fun sourceConstantsAndRoutesMatchTheNativeBundle() {
        assertEquals(
            listOf("home", "application_view", "command_view", "app_list_view"),
            AppLauncherRoute.entries.map { it.routeName },
        )
        assertEquals(listOf(1, 2, 3), AppLauncherSearchResultType.entries.map { it.value })
        assertEquals(100L, AppLauncherViewModel.INTERACTION_DELAY_MS)
        assertEquals(400L, AppLauncherViewModel.ANALYTICS_DEBOUNCE_MS)
        assertEquals(1, ApplicationDirectoryAPI.SOURCE_APP_LAUNCHER)
        assertEquals(listOf(0, 1, 2), AppLauncherCloseReason.entries.map { it.ordinal })
    }

    @Test
    fun homePairsShelfActivitiesByRankAndCollapsesLongSections() {
        val installed = (1L..4L).map { Application(it, "App $it", commandCount = 1) }
        val rows = home(
            entrypoint = AppLauncherEntrypoint.VOICE,
            installed = installed,
            shelf = shelf(12L to 3, 10L to 1, 11L to 2),
        )

        assertEquals(3, rows.filterIsInstance<AppLauncherRow.ApplicationRow>().size)
        assertTrue(rows.any { it is AppLauncherRow.ViewAll })
        assertEquals(2, rows.filterIsInstance<AppLauncherRow.ActivityPair>().size)
        assertEquals(10L, rows.filterIsInstance<AppLauncherRow.ActivityPair>().first().first.id)
        assertTrue(rows.filterIsInstance<AppLauncherRow.ActivityPair>().first().first.directoryApplication != null)
        assertEquals(
            "https://cdn.discordapp.com/app-assets/10/cover-10.webp?size=512",
            rows.filterIsInstance<AppLauncherRow.ActivityPair>().first().first.imageUrl,
        )
        assertEquals(null, rows.filterIsInstance<AppLauncherRow.ActivityPair>().last().second)
    }

    @Test
    fun homeUsesApplicationFrecencyAndKeepsZeroCommandIndexApps() {
        val rows = home(
            recent = listOf(Application(1, "Recent", commandCount = 0)),
            installed = listOf(Application(2, "Installed", commandCount = 0)),
            server = listOf(Application(3, "Server", commandCount = 0)),
        )

        assertEquals(
            listOf(
                AppLauncherSectionName.RECENT_APPS,
                AppLauncherSectionName.INSTALLED,
                AppLauncherSectionName.APPS_IN_THIS_SERVER,
            ),
            rows.filterIsInstance<AppLauncherRow.Header>().map { it.section },
        )
        assertEquals(listOf(1L, 2L, 3L), rows.filterIsInstance<AppLauncherRow.ApplicationRow>().map { it.application.id })
    }

    @Test
    fun homeDeduplicatesApplicationsAcrossSections() {
        val app1 = Application(1, "One", commandCount = 0)
        val app2 = Application(2, "Two", commandCount = 0)
        val app3 = Application(3, "Three", commandCount = 0)
        val rows = home(
            recent = listOf(app1),
            installed = listOf(app1, app2),
            server = listOf(app2, app3),
            shelf = shelf(3L to 1, 4L to 2),
            directory = listOf(
                ApplicationDirectorySearchResult(1, ApplicationDirectoryApplication(4, "Four")),
                ApplicationDirectorySearchResult(1, ApplicationDirectoryApplication(5, "Five")),
            ),
        )
        val ids = buildList {
            addAll(rows.filterIsInstance<AppLauncherRow.ApplicationRow>().map { it.application.id })
            rows.filterIsInstance<AppLauncherRow.ActivityPair>().forEach {
                add(it.first.id)
                it.second?.let { second -> add(second.id) }
            }
            addAll(rows.filterIsInstance<AppLauncherRow.Banner>().map { it.application.id })
        }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids)
        val serverActivity = rows.filterIsInstance<AppLauncherRow.ApplicationRow>()
            .single { it.application.id == 3L }
            .application
        assertEquals(AppLauncherSearchResultType.ACTIVITY_APPLICATION, serverActivity.searchResultType)
        assertTrue(serverActivity.directoryApplication != null)
    }

    @Test
    fun populatedSourceWinsWhileAnotherSourceLoadsOrFails() {
        val rows = home(
            installed = listOf(Application(2, "Installed", commandCount = 0)),
            loading = true,
        )
        assertTrue(rows.none { it == AppLauncherRow.Loading })
        assertEquals(2L, rows.filterIsInstance<AppLauncherRow.ApplicationRow>().single().application.id)
    }

    @Test
    fun voiceOmitsServerAndRecommendationsWhileTextKeepsThemSeparate() {
        val server = listOf(Application(1, "Server", commandCount = 0))
        val recommendations = listOf(
            ApplicationDirectorySearchResult(1, ApplicationDirectoryApplication(2, "Recommended")),
        )
        val voice = home(AppLauncherEntrypoint.VOICE, server = server, shelf = shelf(3L to 1), directory = recommendations)
        val text = home(AppLauncherEntrypoint.TEXT, server = server, shelf = shelf(3L to 1), directory = recommendations)

        assertTrue(voice.filterIsInstance<AppLauncherRow.Header>().none {
            it.section == AppLauncherSectionName.APPS_IN_THIS_SERVER ||
                it.section == AppLauncherSectionName.NEW_TO_APPS
        })
        assertTrue(text.filterIsInstance<AppLauncherRow.Header>().any {
            it.section == AppLauncherSectionName.APPS_IN_THIS_SERVER
        })
        assertTrue(text.filterIsInstance<AppLauncherRow.Header>().any {
            it.section == AppLauncherSectionName.NEW_TO_APPS
        })
    }

    @Test
    fun commandIndexesKeepApplicationsWithNoCommands() {
        val applications = AppLauncherViewModel.indexApplications(
            ApplicationCommandIndex(applications = listOf(ApplicationCommandIndexApplication(7, "Installed"))),
            ApplicationIntegrationType.USER_INSTALL.value,
        )
        assertEquals(0, applications.single().commandCount)
        assertEquals(setOf(ApplicationIntegrationType.USER_INSTALL.value), applications.single().installationTypes)
    }

    @Test
    fun searchKeepsApplicationActivityAndConnectionResultTypes() {
        val results = AppLauncherSearchResultType.entries.mapIndexed { index, type ->
            ApplicationDirectorySearchResult(
                type.value,
                ApplicationDirectoryApplication((index + 1).toLong(), type.name),
            )
        }
        val rows = AppLauncherRows.search("app", emptyList(), emptyList(), results, loading = false)
        assertEquals(
            AppLauncherSearchResultType.entries,
            rows.filterIsInstance<AppLauncherRow.ApplicationRow>().map { it.application.searchResultType },
        )
    }

    @Test
    fun permissionAndSearchEmptyStatesAreDifferent() {
        assertEquals(
            AppLauncherEmptyStateType.HOME_NO_PERMISSIONS,
            (home(hasPermission = false).single() as AppLauncherRow.Empty).type,
        )
        assertEquals(
            AppLauncherEmptyStateType.SEARCH_EMPTY,
            (AppLauncherRows.search("missing", emptyList(), emptyList(), emptyList(), false)
                .single() as AppLauncherRow.Empty).type,
        )
    }

    @Test
    fun recentCommandsPreserveApplicationIdentity() {
        val application = Application(7, "Tools", commandCount = 1)
        val command = BuiltInCommand("9", 7, "inspect", options = emptyList())
        val rows = home(commandApplications = listOf(application), commands = listOf(command))
        assertEquals(application, rows.filterIsInstance<AppLauncherRow.CommandRow>().single().application)
    }

    @Test
    fun chatAppsSlotOpensTheLauncherRatherThanTheGlobalDirectory() {
        val source = File(
            root,
            "app/src/main/java/com/discord/widgets/chat/input/AppFlexInputFragment.kt",
        ).readText()
        assertTrue(source.contains("WidgetAppLauncher.show"))
        assertTrue(!source.contains("WidgetApplicationDirectory.launch"))
    }

    private fun home(
        entrypoint: AppLauncherEntrypoint = AppLauncherEntrypoint.TEXT,
        commandApplications: List<Application> = emptyList(),
        commands: List<com.discord.models.commands.ApplicationCommand> = emptyList(),
        recent: List<Application> = emptyList(),
        installed: List<Application> = emptyList(),
        server: List<Application> = emptyList(),
        shelf: EmbeddedActivityShelf? = null,
        directory: List<ApplicationDirectorySearchResult> = emptyList(),
        loading: Boolean = false,
        hasPermission: Boolean = true,
    ) = AppLauncherRows.home(
        entrypoint,
        commandApplications,
        commands,
        recent,
        installed,
        server,
        shelf,
        directory,
        loading,
        hasPermission,
    )

    private fun shelf(vararg rankedApplications: Pair<Long, Int>) = EmbeddedActivityShelf(
        activities = rankedApplications.map { (id, rank) -> activityConfig(id, rank) },
        applications = rankedApplications.map { (id, _) ->
            EmbeddedActivityShelfApplication(id, "Activity $id", description = "Description $id")
        },
        assets = rankedApplications.associate { (id, _) ->
            id to listOf(
                EmbeddedActivityAsset(id = "icon-$id", type = 1, name = "icon"),
                EmbeddedActivityAsset(id = "cover-$id", type = 1, name = "embedded_cover"),
            )
        },
    )

    private fun activityConfig(applicationId: Long, shelfRank: Int) = EmbeddedActivityConfig(
        applicationId = applicationId,
        activityPreviewVideoAssetId = null,
        supportedPlatforms = listOf("android"),
        defaultOrientationLockState = 0,
        tabletDefaultOrientationLockState = 0,
        requiresAgeGate = false,
        legacyResponsiveAspectRatio = false,
        premiumTierRequirement = null,
        freePeriodStartsAt = null,
        freePeriodEndsAt = null,
        clientPlatformConfig = emptyMap(),
        shelfRank = shelfRank,
        hasCspException = false,
        displaysAdvertisements = false,
    )
}
