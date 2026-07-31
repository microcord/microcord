package com.discord.widgets.chat.list.sheet

import com.discord.api.commands.ApplicationCommandData
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.ApplicationCommandValue
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetApplicationCommandBottomSheetViewModelTest {

    @Test
    fun recursiveOptionsPreserveReferenceDisplayAndCopyOrdering() {
        val count = option("count", 4.0, ApplicationCommandType.NUMBER)
        val enabled = option("enabled", true, ApplicationCommandType.BOOLEAN)
        val subcommand = option("configure", options = listOf(count, enabled))
        val data = ApplicationCommandData(null, null, "1", "settings", listOf(subcommand), null)
        val values = WidgetApplicationCommandBottomSheetViewModel.commandValues(
            data.options.orEmpty(),
            users = emptyMap(),
            members = emptyMap(),
            roles = emptyMap(),
            channels = emptyMap(),
        )

        assertEquals(listOf(count, enabled), data.options.orEmpty().flattenOptions())
        assertEquals("4", values.getValue("count").value)
        assertEquals("/settings configure count:4 enabled:true", data.toSlashCommandCopyString(values))
    }

    @Test
    fun zeroDecimalTruncationMatchesWirePresentation() {
        assertEquals("12", 12.0.toStringTruncateZeroDecimal())
        assertEquals("12.5", 12.5.toStringTruncateZeroDecimal())
        assertEquals("null", null.toStringTruncateZeroDecimal())
    }

    private fun option(
        name: String,
        value: Any? = null,
        type: ApplicationCommandType = ApplicationCommandType.SUBCOMMAND,
        options: List<ApplicationCommandValue>? = null,
    ) = ApplicationCommandValue(name, value, type.type, options, null)
}
