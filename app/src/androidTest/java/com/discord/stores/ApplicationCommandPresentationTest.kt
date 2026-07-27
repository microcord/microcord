package com.discord.stores

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.CommandChoice
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.BuiltInCommand
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationCommandPresentationTest {
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun resourceDescriptionsAndValidationErrorsMatchCommandType() {
        val command = BuiltInCommand(
            id = "-1",
            applicationId = 0L,
            name = "shrug",
            descriptionRes = R.string.command_shrug_description,
            options = emptyList(),
        )
        val choice = option(
            type = ApplicationCommandType.STRING,
            choices = listOf(CommandChoice("one", "one")),
        )

        assertEquals(
            resources.getString(R.string.command_shrug_description).replace("¯_(ツ)_/¯", "¯\\_(ツ)_/¯"),
            command.getDescriptionText(resources),
        )
        assertEquals(resources.getString(R.string.command_validation_choice_error), choice.getErrorText(resources))
        assertEquals(
            resources.getString(R.string.command_validation_required_error),
            option(ApplicationCommandType.ATTACHMENT).getErrorText(resources),
        )
    }

    private fun option(
        type: ApplicationCommandType,
        choices: List<CommandChoice>? = null,
    ) = ApplicationCommandOption(
        type = type,
        name = "value",
        required = true,
        default = false,
        choices = choices,
    )
}
