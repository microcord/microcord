package com.discord.plugins

import dev.mantikafasi.manticord.plugins.api.PluginActivityType
import dev.mantikafasi.manticord.plugins.api.pluginActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginActivityTest {
    @Test
    fun builderCarriesEveryField() {
        val activity = pluginActivity("Music") {
            applicationId = 7
            type = PluginActivityType.LISTENING
            details = "Song"
            largeImage = "cover"
            button("Open", "https://example.com")
        }
        assertEquals("Music", activity.name)
        assertEquals(PluginActivityType.LISTENING, activity.type)
        assertEquals(7L, activity.applicationId)
        assertEquals("Open", activity.buttons.single().label)
    }

    @Test
    fun anActivityNeedsANameAndAtMostTwoButtons() {
        assertThrows(IllegalArgumentException::class.java) { pluginActivity(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            pluginActivity("x") { repeat(3) { button("b$it", "https://example.com") } }
        }
    }
}
