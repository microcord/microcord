package com.discord.widgets.chat.input.applicationcommands

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.recyclerview.widget.RecyclerView
import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.RemoteApplicationCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectedApplicationCommandAdapterTest {
    @Test
    fun commandRowsAndValidationMatchReferenceOrdering() {
        val required = option("required", true)
        val optional = option("optional", false)
        val command = RemoteApplicationCommand(
            id = "command",
            applicationId = 1,
            name = "test",
            options = listOf(optional, required),
            guildId = 2,
            version = "1",
        )
        val recycler = RecyclerView(ApplicationProvider.getApplicationContext())
        val clicked = mutableListOf<ApplicationCommandOption>()
        val adapter = SelectedApplicationCommandAdapter(recycler, clicked::add)

        adapter.setApplicationCommand(command, Application(1, "app", commandCount = 1))

        assertEquals(4, adapter.itemCount)
        assertEquals(SelectedApplicationCommandItem.TYPE_TITLE, adapter.getItem(0).type)
        assertEquals(required, adapter.getItem(1).option)
        assertEquals(SelectedApplicationCommandItem.TYPE_SECTION_HEADING, adapter.getItem(2).type)
        assertEquals(optional, adapter.getItem(3).option)

        adapter.setVerified(mapOf(required to true, optional to false), setOf(optional))
        assertTrue(adapter.getItem(1).completed)
        assertFalse(adapter.getItem(1).error)
        assertFalse(adapter.getItem(3).completed)
        assertTrue(adapter.getItem(3).error)

        adapter.onParamClicked(optional)
        assertEquals(listOf(optional), clicked)
    }

    private fun option(name: String, required: Boolean) = ApplicationCommandOption(
        type = ApplicationCommandType.STRING,
        name = name,
        required = required,
        default = false,
    )
}
