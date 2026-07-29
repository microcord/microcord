package com.discord.widgets.chat.input.autocomplete

import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.ApplicationCommandAutocompleteChoice
import com.discord.api.commands.CommandChoice
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.RemoteApplicationCommand
import com.discord.stores.DiscoverCommands
import com.discord.stores.CommandAutocompleteState
import com.discord.stores.LoadState
import com.discord.widgets.chat.input.WidgetChatInputDiscoveryCommandsModel
import com.discord.widgets.chat.input.autocomplete.commands.buildRemoteCommandOptionRequest
import com.discord.widgets.chat.input.models.MentionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandBrowserAndRemoteOptionTest {
    @Test
    fun discoveryModelGroupsRowsAndAddsPagingPlaceholders() {
        val application = Application(10L, "Tools", commandCount = 1)
        val model = WidgetChatInputDiscoveryCommandsModel.parseModelDiscoveryCommands(
            myId = 1L,
            myRoles = emptyList(),
            applications = listOf(application),
            discoveryCommands = DiscoverCommands(
                commands = listOf(command("weather", listOf(option("city")))),
                currentStartOffset = 2,
                currentEndOffset = 2,
                hasMoreBefore = true,
                hasMoreAfter = true,
                jumpedSequenceId = 4,
                jumpedApplicationId = 10L,
                loadState = LoadState.LoadingDown,
            ),
            includeHeaders = true,
            placeholdersCount = 3,
            frecencyCommands = emptyList(),
        )

        val rows = model.commandsByApplication.single().second
        assertTrue(rows.take(3).all { it is ApplicationCommandLoadingPlaceholder })
        assertTrue(rows[3] is ApplicationPlaceholder)
        assertTrue(rows[4] is ApplicationCommandAutocompletable)
        assertTrue(rows.takeLast(3).all { it is ApplicationCommandLoadingPlaceholder })
        assertEquals(10L, model.jumpedApplicationId)
    }

    @Test
    fun discoveryModelPrependsFrecencyOnlyAtStartOfBrowser() {
        val application = Application(10L, "Tools", commandCount = 1)
        val frequent = Application(-2L, "Frequently Used", commandCount = 0, builtIn = true)
        val command = command("weather", emptyList())
        val model = WidgetChatInputDiscoveryCommandsModel.parseModelDiscoveryCommands(
            1L,
            emptyList(),
            listOf(application, frequent),
            DiscoverCommands(listOf(command), 0, 0, false, false, 0, null),
            includeHeaders = true,
            placeholdersCount = 3,
            frecencyCommands = listOf(command),
        )

        assertEquals(-2L, model.commandsByApplication.first().first.id)
        assertTrue(model.commandsByApplication.first().second.first() is ApplicationPlaceholder)
        assertTrue(
            (model.commandsByApplication.first().second.last() as ApplicationCommandAutocompletable).showAvatar,
        )
    }

    @Test
    fun remoteOptionRequestMarksFocusAndSendsChoiceValues() {
        val city = option("city", autocomplete = true)
        val units = option("units", choices = listOf(CommandChoice("Metric", "metric")))
        val command = command("weather", listOf(city, units))

        val request = buildRemoteCommandOptionRequest(
            "/weather city:Lon units:Metric",
            Application(10L, "Tools", commandCount = 1),
            command,
            city,
            emptyList(),
        )

        assertEquals(listOf("city", "units"), request.values.map { it.name })
        assertEquals("Lon", request.values[0].value)
        assertTrue(request.values[0].focused == true)
        assertEquals("metric", request.values[1].value)
        assertFalse(request.values[1].focused == true)
    }

    @Test
    fun remoteOptionStateShowsLoadingFailureAndChoices() {
        val option = option("city", autocomplete = true)
        val token = MentionToken(LeadingIdentifier.NONE, "lon", false, 14)

        val loading = AutocompleteViewModel.reduceRemoteOption(token, option, null)
            as AutocompleteViewState.Autocomplete
        assertTrue(loading.isLoading)
        assertFalse(loading.isError)

        val failed = AutocompleteViewModel.reduceRemoteOption(
            token,
            option,
            CommandAutocompleteState.Failed,
        ) as AutocompleteViewState.Autocomplete
        assertTrue(failed.isError)

        val choices = AutocompleteViewModel.reduceRemoteOption(
            token,
            option,
            CommandAutocompleteState.Choices(
                listOf(ApplicationCommandAutocompleteChoice("London", "london")),
            ),
        ) as AutocompleteViewState.Autocomplete
        assertEquals("london", choices.autocompletables.single().getInputReplacement())
    }

    private fun option(
        name: String,
        autocomplete: Boolean = false,
        choices: List<CommandChoice>? = null,
    ) = ApplicationCommandOption(
        type = ApplicationCommandType.STRING,
        name = name,
        required = true,
        default = false,
        autocomplete = autocomplete,
        choices = choices,
    )

    private fun command(name: String, options: List<ApplicationCommandOption>): ApplicationCommand =
        RemoteApplicationCommand(
            id = name,
            applicationId = 10L,
            name = name,
            options = options,
            guildId = 20L,
            version = "1",
            defaultPermissions = true,
        )
}
