package com.discord.widgets.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ServerRootExactPathContractsTest {
    @Test
    fun `126 21 server settings nested paths exist`() {
        listOf(
            "com.discord.widgets.servers.WidgetServerSettings\$Model",
            "com.discord.widgets.servers.WidgetServerSettings\$Model\$Companion",
            "com.discord.widgets.servers.WidgetServerSettingsOverview\$AfkBottomSheet",
            "com.discord.widgets.servers.WidgetServerSettingsOverview\$Model",
            "com.discord.widgets.servers.WidgetServerSettingsOverview\$Model\$Companion",
            "com.discord.widgets.servers.WidgetServerSettingsModeration\$Model",
            "com.discord.widgets.servers.WidgetServerSettingsModeration\$Model\$Companion",
        ).forEach { assertNotNull(Class.forName(it)) }
    }

    @Test
    fun `moderation model retains its reference data contract`() {
        val model = WidgetServerSettingsModeration.Model(
            com.discord.models.guild.Guild(id = 1, name = "Guild", ownerId = 2),
        )

        assertEquals(model, model.copy())
        assertEquals("Guild", model.guild.name)
    }
}
