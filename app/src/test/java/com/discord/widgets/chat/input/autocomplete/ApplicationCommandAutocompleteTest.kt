package com.discord.widgets.chat.input.autocomplete

import com.discord.api.channel.Channel
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.permission.Permission
import com.discord.api.commands.ApplicationCommandIndexPermissions
import com.discord.models.commands.Application
import com.discord.models.commands.CommandPermissionContext
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.RemoteApplicationCommand
import com.discord.widgets.chat.input.models.MentionToken
import com.discord.widgets.chat.input.autocomplete.sources.ApplicationCommandsAutocompletableSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationCommandAutocompleteTest {
    @Test
    fun commandSourceAttachesApplicationsAndEvaluatesPermissions() {
        val application = Application(10L, "Tools", commandCount = 2)
        val allowed = command("1", commandPermissions = ApplicationCommandIndexPermissions(roles = mapOf(7L to true)))
        val denied = command("2", commandPermissions = ApplicationCommandIndexPermissions(roles = mapOf(7L to false)))

        val items = ApplicationCommandsAutocompletableSource.createAutocompletables(
            permissionContext = CommandPermissionContext(
                userId = 1L,
                roleIds = listOf(7L),
                guildId = 5L,
                computedPermissions = Permission.VIEW_CHANNEL or Permission.USE_APPLICATION_COMMANDS or
                    Permission.SEND_MESSAGES,
            ),
            applications = listOf(application),
            commands = listOf(allowed, denied),
        )[LeadingIdentifier.APP_COMMAND].orEmpty().filterIsInstance<ApplicationCommandAutocompletable>()

        assertEquals(2, items.size)
        assertEquals(application, items.first { it.command.id == "1" }.application)
        assertTrue(items.first { it.command.id == "1" }.hasPermissionToUse)
        assertFalse(items.first { it.command.id == "2" }.hasPermissionToUse)
    }

    @Test
    fun focusedCommandOptionUsesItsValueInsteadOfTheSlashPrefix() {
        val option = option(ApplicationCommandType.USER, "target")
        val input = "/ban target:@ali reason:test"

        val token = AutocompleteModelUtils.getCommandOptionAutocompleteToken(
            input,
            16..16,
            option,
            listOf(option, option(ApplicationCommandType.STRING, "reason")),
        )

        assertEquals(LeadingIdentifier.MENTION, token?.leadingIdentifier)
        assertEquals("@ali", token?.token)
        assertEquals(12, token?.startIndex)
    }

    @Test
    fun booleanOptionsProvideLocalChoices() {
        val candidates = AutocompleteModelUtils.autocompletablesForCommandOption(
            option(ApplicationCommandType.BOOLEAN, "enabled"),
            emptyMap(),
        )[LeadingIdentifier.NONE].orEmpty().filterIsInstance<ApplicationCommandChoiceAutocompletable>()

        assertEquals(listOf("true", "false"), candidates.map { it.choice.value })

        val state = AutocompleteViewModel.reduce(
            MentionToken(LeadingIdentifier.NONE, "", false, 0),
            mapOf(LeadingIdentifier.NONE to candidates.toSet()),
            allowEmptyQuery = true,
        ) as AutocompleteViewState.Autocomplete
        assertEquals(2, state.autocompletables.size)
    }

    private fun option(type: ApplicationCommandType, name: String) = ApplicationCommandOption(
        type = type,
        name = name,
        required = true,
        default = false,
    )

    private fun command(
        id: String,
        commandPermissions: ApplicationCommandIndexPermissions? = null,
    ) = RemoteApplicationCommand(
        id = id,
        applicationId = 10L,
        name = "command$id",
        options = emptyList(),
        guildId = 20L,
        version = "1",
        commandPermissions = commandPermissions,
    )
}
