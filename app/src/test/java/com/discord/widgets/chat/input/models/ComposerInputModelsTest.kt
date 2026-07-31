package com.discord.widgets.chat.input.models

import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.RemoteApplicationCommand
import com.discord.widgets.chat.input.autocomplete.commands.AutocompleteCommandUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerInputModelsTest {
    @Test
    fun commandIdsIncludeGroupAndSubcommandNames() {
        val command = command("role")
        val data = ApplicationCommandData(
            Application(1L, "Tools", commandCount = 1),
            command,
            listOf(
                ApplicationCommandValue(
                    "admin",
                    type = ApplicationCommandType.SUBCOMMAND_GROUP.type,
                    options = listOf(
                        ApplicationCommandValue("add", type = ApplicationCommandType.SUBCOMMAND.type),
                    ),
                ),
            ),
        )

        assertEquals("role admin add", data.getCommandId())
    }

    @Test
    fun selectionHelpersMatchCommandOptionKinds() {
        val command = command("weather")
        val model = MentionInputModel.VerifiedCommandInputModel(
            input = "/weather city:",
            autocompletables = emptyMap(),
            inputMentionsMap = emptyMap(),
            inputCommandContext = InputCommandContext(selectedCommand = command),
            commands = AutocompleteApplicationCommands(),
        )
        val freeform = InputSelectionModel.CommandInputSelectionModel(model, 0..0, stringOption("city"))
        val boolean = InputSelectionModel.CommandInputSelectionModel(
            model,
            0..0,
            option("enabled", ApplicationCommandType.BOOLEAN),
        )

        assertEquals(command, freeform.getSelectedCommand())
        assertTrue(freeform.hasSelectedFreeformInput())
        assertFalse(freeform.hasSelectedCommandOptionWithChoices())
        assertTrue(boolean.hasSelectedCommandOptionWithChoices())
        assertFalse(boolean.hasSelectedFreeformInput())
        assertNull(InputSelectionModel.MessageInputSelectionModel(
            MentionInputModel.VerifiedMessageInputModel("hello", emptyMap(), emptyMap()),
            0..0,
        ).getSelectedCommand())
    }

    @Test
    fun commandUtilityFindsAndParsesOptionRanges() {
        val city = stringOption("city")
        val units = stringOption("units")
        val command = command("weather", listOf(city, units))
        val input = "/weather city:London units:Metric"

        assertEquals(9, AutocompleteCommandUtils.run { input.findStartOfParam("city") })
        assertEquals(14..20, AutocompleteCommandUtils.run { input.findValueRange(command, "city") })
        val parsed = AutocompleteCommandUtils.parseCommandOptions(
            input,
            command,
            ChatInputMentionsMap(input, emptyMap()),
            emptyMap(),
        )
        assertEquals("London", parsed[city]?.value)
        assertEquals("Metric", parsed[units]?.value)
    }

    private fun stringOption(name: String) = option(name, ApplicationCommandType.STRING)

    private fun option(name: String, type: ApplicationCommandType) = ApplicationCommandOption(
        type = type,
        name = name,
        required = true,
        default = false,
    )

    private fun command(
        name: String,
        options: List<ApplicationCommandOption> = emptyList(),
    ) = RemoteApplicationCommand(
        id = name,
        applicationId = 1L,
        name = name,
        options = options,
        guildId = 2L,
        version = "1",
    )
}
