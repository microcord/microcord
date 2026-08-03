package com.discord.widgets.chat.input.autocomplete

import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.ApplicationCommandAutocompleteChoice
import com.discord.api.commands.CommandChoice
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.ApplicationSubCommand
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
import rx.Observable
import rx.schedulers.TestScheduler
import java.util.concurrent.TimeUnit

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
    fun nestedRemoteRequestUsesRootAndUntranslatedNames() {
        val query = option("suche", autocomplete = true, untranslatedName = "query")
        val root = command(
            "verwaltung",
            listOf(
                option(
                    "ressource",
                    type = ApplicationCommandType.SUBCOMMAND_GROUP,
                    untranslatedName = "resource",
                    options = listOf(
                        option(
                            "erstellen",
                            type = ApplicationCommandType.SUBCOMMAND,
                            untranslatedName = "create",
                            options = listOf(query),
                        ),
                    ),
                ),
            ),
            untranslatedName = "manage",
        )
        val command = ApplicationSubCommand(
            rootCommand = root,
            subCommandName = "erstellen",
            parentGroupName = "ressource",
            applicationId = 10L,
            name = "verwaltung ressource erstellen",
            options = listOf(query),
            guildId = 20L,
            version = "1",
        )

        val request = buildRemoteCommandOptionRequest(
            "/verwaltung ressource erstellen suche:lon",
            Application(10L, "Tools", commandCount = 1),
            command,
            query,
            emptyList(),
        )

        assertEquals(root, request.applicationCommand)
        val group = request.values.single()
        val subcommand = group.options!!.single()
        assertEquals("resource", group.name)
        assertEquals("create", subcommand.name)
        assertEquals("query", subcommand.options!!.single().name)
        assertTrue(subcommand.options!!.single().focused == true)
    }

    @Test
    fun focusedRemoteValuesKeepTheirWireTypes() {
        fun value(type: ApplicationCommandType, raw: String): Any? {
            val option = option("value", type = type, autocomplete = true)
            return buildRemoteCommandOptionRequest(
                "/test value:$raw",
                Application(10L, "Tools", commandCount = 1),
                command("test", listOf(option)),
                option,
                emptyList(),
            ).values.single().value
        }

        assertEquals("hello", value(ApplicationCommandType.STRING, "hello"))
        assertEquals(12L, value(ApplicationCommandType.INTEGER, "12"))
        assertEquals(1.5, value(ApplicationCommandType.NUMBER, "1.5"))
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

        val empty = AutocompleteViewModel.reduceRemoteOption(
            token,
            option,
            CommandAutocompleteState.Choices(emptyList()),
        ) as AutocompleteViewState.Autocomplete
        assertTrue(empty.autocompletables.isEmpty())
        assertEquals(
            com.discord.R.string.application_command_autocomplete_no_options,
            InputAutocomplete.statusTextRes(empty),
        )
        assertEquals(
            com.discord.R.string.application_command_autocomplete_failed,
            InputAutocomplete.statusTextRes(failed),
        )
        assertTrue(InputAutocomplete.renderedItems(loading).all { it is ApplicationCommandLoadingPlaceholder })
        assertEquals(4, InputAutocomplete.renderedItems(loading).size)
    }

    @Test
    fun rapidTypingOnlySendsTheLatestRemoteQuery() {
        val scheduler = TestScheduler()
        val requests = mutableListOf<com.discord.widgets.chat.input.models.ApplicationCommandData>()
        val option = option("query", autocomplete = true)
        val command = command("search", listOf(option))
        val viewModel = AutocompleteViewModel(
            channelId = 7L,
            autocompletablesSource = { Observable.just(emptyMap()) },
            commandStateSource = { Observable.just(AutocompleteViewModel.CommandState()) },
            remoteOptionRequest = { _, _, data -> requests += data },
            commandIndexRequest = {},
            clearRemoteResults = {},
            channelGuildId = { 20L },
            remoteAutocompleteDelayMs = 500L,
            remoteRequestScheduler = scheduler,
        )
        viewModel.selectAutocompleteItem(
            ApplicationCommandAutocompletable(Application(10L, "Tools", commandCount = 1), command, true, true),
        )
        viewModel.selectCommandOption(option)

        viewModel.onInputChanged("/search query:a", 15..15)
        viewModel.onInputChanged("/search query:ab", 16..16)
        scheduler.advanceTimeBy(499L, TimeUnit.MILLISECONDS)
        assertTrue(requests.isEmpty())
        scheduler.advanceTimeBy(1L, TimeUnit.MILLISECONDS)

        assertEquals("ab", requests.single().values.single().value)
    }

    private fun option(
        name: String,
        type: ApplicationCommandType = ApplicationCommandType.STRING,
        autocomplete: Boolean = false,
        choices: List<CommandChoice>? = null,
        untranslatedName: String = name,
        options: List<ApplicationCommandOption>? = null,
    ) = ApplicationCommandOption(
        type = type,
        name = name,
        required = true,
        default = false,
        autocomplete = autocomplete,
        choices = choices,
        untranslatedName = untranslatedName,
        options = options,
    )

    private fun command(
        name: String,
        options: List<ApplicationCommandOption>,
        untranslatedName: String = name,
    ): ApplicationCommand =
        RemoteApplicationCommand(
            id = name,
            applicationId = 10L,
            name = name,
            options = options,
            guildId = 20L,
            version = "1",
            defaultPermissions = true,
            untranslatedName = untranslatedName,
        )
}
