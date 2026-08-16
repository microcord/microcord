package com.discord.plugins

import dev.mantikafasi.manticord.plugins.api.PluginContextMenuSurface
import dev.mantikafasi.manticord.plugins.api.PluginMessagePlacement
import dev.mantikafasi.manticord.plugins.api.PluginMessageInterceptionType
import dev.mantikafasi.manticord.plugins.api.pluginContextMenuItem
import dev.mantikafasi.manticord.plugins.api.pluginHeaderContribution
import dev.mantikafasi.manticord.plugins.api.pluginSlashCommand
import dev.mantikafasi.manticord.plugins.api.pluginMessageContribution
import dev.mantikafasi.manticord.plugins.api.pluginMessageInterceptor
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
        val message = PluginUiRegistry.registerMessage(
            "inventory-test",
            pluginMessageContribution(
                "card",
                "Card",
                PluginMessagePlacement.STANDALONE,
                { error("not inflated") },
            ),
        )
        val interceptor = PluginUiRegistry.registerMessageInterceptor(
            "inventory-test",
            pluginMessageInterceptor("lifecycle", PluginMessageInterceptionType.SEND, transform = { it }),
        )

        try {
            assertEquals(
                listOf(
                    PluginContentInfo("Header action", "Header"),
                    PluginContentInfo("Slash command", "/echo", "text"),
                    PluginContentInfo("Message standalone", "Card"),
                    PluginContentInfo("Message interceptor", "send"),
                ),
                PluginUiRegistry.content("inventory-test"),
            )
        } finally {
            header.unregister()
            command.unregister()
            menu.unregister()
            message.unregister()
            interceptor.unregister()
        }
    }
}
