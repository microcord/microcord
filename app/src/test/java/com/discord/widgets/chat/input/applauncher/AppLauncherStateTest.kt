package com.discord.widgets.chat.input.applauncher

import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResult
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
    fun homePairsActivitiesAndCollapsesLongSectionsBehindViewAll() {
        val installed = (1L..4L).map { Application(it, "App $it", commandCount = 1) }
        val activities = (10L..12L).map {
            ApplicationDirectorySearchResult(
                AppLauncherSearchResultType.ACTIVITY_APPLICATION.value,
                ApplicationDirectoryApplication(it, "Activity $it"),
            )
        }
        val rows = AppLauncherRows.home(
            AppLauncherEntrypoint.VOICE,
            installed,
            emptyList(),
            activities,
            loading = false,
            hasPermission = true,
        )

        assertEquals(3, rows.filterIsInstance<AppLauncherRow.ApplicationRow>().size)
        assertTrue(rows.any { it is AppLauncherRow.ViewAll })
        assertEquals(2, rows.filterIsInstance<AppLauncherRow.ActivityPair>().size)
        assertEquals(null, rows.filterIsInstance<AppLauncherRow.ActivityPair>().last().second)
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
            (AppLauncherRows.home(AppLauncherEntrypoint.TEXT, emptyList(), emptyList(), emptyList(), false, false)
                .single() as AppLauncherRow.Empty).type,
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
        val rows = AppLauncherRows.home(
            AppLauncherEntrypoint.TEXT,
            listOf(application),
            listOf(command),
            emptyList(),
            loading = false,
            hasPermission = true,
        )
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
}
