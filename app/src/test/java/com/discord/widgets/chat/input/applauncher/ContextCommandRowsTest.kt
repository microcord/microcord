package com.discord.widgets.chat.input.applauncher

import com.discord.models.commands.Application
import com.discord.models.commands.RemoteApplicationCommand
import com.discord.widgets.chat.input.applauncher.WidgetContextCommandsSheet.Row
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextCommandRowsTest {
    private val applications = mapOf(
        1L to Application(id = 1L, name = "Zeta", commandCount = 1),
        2L to Application(id = 2L, name = "Alpha", commandCount = 2),
    )
    private val commands = listOf(
        command("10", 1L, "Report"),
        command("20", 2L, "Translate"),
        command("21", 2L, "Quote"),
    )

    @Test
    fun frequentlyUsedComesFirstThenApplicationsAlphabetically() {
        val rows = WidgetContextCommandsSheet.buildRows(commands, applications, listOf("10", "999"), "", "Frequently Used")

        assertEquals(
            listOf("# Frequently Used", "Report", "# Alpha", "Translate", "Quote", "# Zeta", "Report"),
            rows.map(::label),
        )
        val alpha = rows.filterIsInstance<Row.Command>().filter { it.command.applicationId == 2L }
        assertEquals(listOf(true to false, false to true), alpha.map { it.isFirst to it.isLast })
    }

    @Test
    fun searchMatchesCommandOrApplicationNameAndDropsEmptySections() {
        assertEquals(
            listOf("# Alpha", "Quote"),
            WidgetContextCommandsSheet.buildRows(commands, applications, listOf("10"), "quo", "F").map(::label),
        )
        assertEquals(
            listOf("# Zeta", "Report"),
            WidgetContextCommandsSheet.buildRows(commands, applications, emptyList(), " zeta ", "F").map(::label),
        )
    }

    private fun label(row: Row) = when (row) {
        is Row.Header -> "# ${row.title}"
        is Row.Command -> row.command.name
    }

    private fun command(id: String, applicationId: Long, name: String) = RemoteApplicationCommand(
        id = id,
        applicationId = applicationId,
        name = name,
        options = emptyList(),
        guildId = null,
        version = "1",
        type = 3,
    )
}
