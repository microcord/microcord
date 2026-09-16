package com.discord.widgets.chat.input.autocomplete

import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.ApplicationCommandAutocompleteChoice
import com.discord.api.commands.CommandChoice
import com.discord.models.commands.Application
import com.discord.models.commands.CommandPermissionContext
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.ApplicationSubCommand
import com.discord.models.commands.BuiltInCommand
import com.discord.models.commands.RemoteApplicationCommand
import com.discord.stores.DiscoverCommands
import com.discord.stores.CommandAutocompleteState
import com.discord.stores.LoadState
import com.discord.widgets.chat.input.WidgetChatInputDiscoveryCommandsModel
import com.discord.widgets.chat.input.autocomplete.commands.buildRemoteCommandOptionRequest
import com.discord.widgets.chat.input.models.AutocompleteApplicationCommands
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
    fun discoveryModelGroupsRowsAndTrailsPlaceholdersWhileLoading() {
        val application = Application(10L, "Tools", commandCount = 1)
        val model = WidgetChatInputDiscoveryCommandsModel.parseModelDiscoveryCommands(
            permissionContext = CommandPermissionContext(userId = 1L),
            applications = listOf(application),
            discoveryCommands = DiscoverCommands(
                commands = listOf(command("weather", listOf(option("city")))),
                loadState = LoadState.Loading,
            ),
            includeHeaders = true,
            placeholdersCount = 3,
            frecencyCommands = emptyList(),
        )

        val rows = model.commandsByApplication.single().second
        assertTrue(rows[0] is ApplicationPlaceholder)
        assertTrue(rows[1] is ApplicationCommandAutocompletable)
        assertTrue(rows.takeLast(3).all { it is ApplicationCommandLoadingPlaceholder })
    }

    @Test
    fun discoveryModelPrependsFrecencyOnlyAtStartOfBrowser() {
        val application = Application(10L, "Tools", commandCount = 1)
        val frequent = Application(-2L, "Frequently Used", commandCount = 0, builtIn = true)
        val command = command("weather", emptyList())
        val model = WidgetChatInputDiscoveryCommandsModel.parseModelDiscoveryCommands(
            CommandPermissionContext(userId = 1L),
            listOf(application, frequent),
            DiscoverCommands(listOf(command)),
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
    fun staticChoicesRenderWhileRemoteChoicesArePendingThenMerge() {
        val option = option(
            "scope",
            autocomplete = true,
            choices = listOf(CommandChoice("Only me", "private")),
        )
        val token = MentionToken(LeadingIdentifier.NONE, "", false, 14)
        val static = AutocompleteModelUtils.autocompletablesForCommandOption(option, emptyMap())
            .getValue(LeadingIdentifier.NONE)
            .filterIsInstance<ApplicationCommandChoiceAutocompletable>()

        val pending = AutocompleteViewModel.reduceRemoteOption(token, option, null, static)
            as AutocompleteViewState.Autocomplete
        assertFalse(pending.isLoading)
        assertEquals(listOf("private"), InputAutocomplete.renderedItems(pending).map { it.getInputReplacement() })

        val loaded = AutocompleteViewModel.reduceRemoteOption(
            token,
            option,
            CommandAutocompleteState.Choices(listOf(ApplicationCommandAutocompleteChoice("Everyone", "public"))),
            static,
        ) as AutocompleteViewState.Autocomplete
        assertEquals(listOf("private", "public"), loaded.autocompletables.map { it.getInputReplacement() })
    }

    @Test
    fun slashPrefixBrowserContainsRemoteCommands() {
        val application = Application(10L, "Tools", commandCount = 1)
        val remote = command("weather", emptyList())
        val discovery = WidgetChatInputDiscoveryCommandsModel.parseModelDiscoveryCommands(
            CommandPermissionContext(userId = 1L),
            listOf(application),
            DiscoverCommands(listOf(remote)),
            includeHeaders = true,
            placeholdersCount = 3,
            frecencyCommands = emptyList(),
        )
        val viewModel = AutocompleteViewModel(
            channelId = 7L,
            autocompletablesSource = { Observable.just(emptyMap()) },
            commandStateSource = { Observable.never() },
            commandIndexRequest = {},
        )
        var state: AutocompleteViewState = AutocompleteViewState.Hidden
        viewModel.observeViewState().subscribe { state = it }

        viewModel.onInputChanged("/", 1..1)
        assertTrue(state is AutocompleteViewState.LoadingItems)
        viewModel.onCommandStateChanged(
            AutocompleteViewModel.CommandState(
                applicationCommands = AutocompleteApplicationCommands(
                    applications = listOf(application),
                    queryCommands = listOf(remote),
                    discoveryCommands = discovery,
                ),
            ),
        )

        val visible = state as AutocompleteViewState.Autocomplete
        assertTrue(visible.autocompletables.filterIsInstance<ApplicationCommandAutocompletable>()
            .any { it.command.applicationId == 10L && it.command.name == "weather" })
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

    @Test
    fun blankOptionalBuiltInArgumentIsOmittedAndValid() {
        val option = option("message", required = false)
        val command = BuiltInCommand("-2", -1L, "tableflip", options = listOf(option)) {
            "${it["message"] ?: ""} (flip)"
        }
        val viewModel = AutocompleteViewModel(
            channelId = 7L,
            autocompletablesSource = { Observable.just(emptyMap()) },
            commandStateSource = { Observable.just(AutocompleteViewModel.CommandState()) },
            commandIndexRequest = {},
        )
        viewModel.selectAutocompleteItem(
            ApplicationCommandAutocompletable(
                Application(-1L, "Built-In", commandCount = 1, builtIn = true),
                command,
                true,
                true,
            ),
        )

        val data = requireNotNull(viewModel.buildSelectedCommandData("/tableflip ", emptyList()))
        assertTrue(data.validInputs)
        assertTrue(data.values.isEmpty())
        assertEquals(" (flip)", command.execute?.invoke(emptyMap()))
    }

    @Test
    fun selectedOptionalUserTokenIsValidWithoutTheTransientMentionMap() {
        val user = option("user", type = ApplicationCommandType.USER, required = false)
        val note = option("note", required = false)
        val viewModel = AutocompleteViewModel(
            channelId = 7L,
            autocompletablesSource = { Observable.just(emptyMap()) },
            commandStateSource = { Observable.just(AutocompleteViewModel.CommandState()) },
            commandIndexRequest = {},
        )
        viewModel.selectAutocompleteItem(ApplicationCommandAutocompletable(
            Application(10L, "Tools", commandCount = 1), command("inspect", listOf(user, note)), true, true,
        ))

        val data = requireNotNull(viewModel.buildSelectedCommandData("/inspect user:<@123456789012345678>", emptyList()))
        assertTrue(data.validInputs)
        assertEquals(1, data.values.size)
        assertEquals("user", data.values.single().name)
        assertEquals("123456789012345678", data.values.single().value)
    }

    @Test
    fun completedUserRoleAndChannelTokensValidateOnlyForMatchingOptionTypes() {
        val options = listOf(
            option("user", ApplicationCommandType.USER, required = false),
            option("role", ApplicationCommandType.ROLE, required = false),
            option("channel", ApplicationCommandType.CHANNEL, required = false),
            option("mentionable", ApplicationCommandType.MENTIONABLE, required = false),
        )
        fun data(input: String) = AutocompleteViewModel(
            channelId = 7L,
            autocompletablesSource = { Observable.just(emptyMap()) },
            commandStateSource = { Observable.just(AutocompleteViewModel.CommandState()) },
            commandIndexRequest = {},
        ).run {
            selectAutocompleteItem(ApplicationCommandAutocompletable(
                Application(10L, "Tools", commandCount = 1), command("inspect", options), true, true,
            ))
            requireNotNull(buildSelectedCommandData(input, emptyList()))
        }
        val valid = data("/inspect user:<@1> role:<@&2> channel:<#3> mentionable:<@!4>")
        assertTrue(valid.validInputs)
        assertEquals(listOf("1", "2", "3", "4"), valid.values.map { it.value })
        assertFalse(data("/inspect user:<@&2>").validInputs)
    }

    private fun option(
        name: String,
        type: ApplicationCommandType = ApplicationCommandType.STRING,
        autocomplete: Boolean = false,
        required: Boolean = true,
        choices: List<CommandChoice>? = null,
        untranslatedName: String = name,
        options: List<ApplicationCommandOption>? = null,
    ) = ApplicationCommandOption(
        type = type,
        name = name,
        required = required,
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
            untranslatedName = untranslatedName,
        )
}
