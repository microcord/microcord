package com.discord.plugins

import dev.mantikafasi.manticord.plugins.api.PluginContextMenuSurface
import dev.mantikafasi.manticord.plugins.api.pluginContextMenuItem
import dev.mantikafasi.manticord.plugins.api.pluginHeaderContribution
import dev.mantikafasi.manticord.plugins.api.pluginSlashCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginUiRegistryTest {
    @Test
    fun contentListsOnlyOwnedRegistrations() {
        val header = PluginUiRegistry.registerHeader(
            "inventory-test",
            pluginHeaderContribution("header", "Header", { error("not inflated") }),
        )
        val command = PluginUiRegistry.registerSlashCommand(
            "inventory-test",
            pluginSlashCommand("echo", "echo", "Echo", { "" }) { string("text", "Text") },
        )
        val menu = PluginUiRegistry.registerContextMenuItem(
            "other-plugin",
            pluginContextMenuItem("raw", "View Raw", PluginContextMenuSurface.MESSAGE, onClick = {}),
        )

        try {
            assertEquals(
                listOf(
                    PluginContentInfo("Header action", "Header"),
                    PluginContentInfo("Slash command", "/echo", "text"),
                ),
                PluginUiRegistry.content("inventory-test"),
            )
        } finally {
            header.unregister()
            command.unregister()
            menu.unregister()
        }
    }
}
