package com.discord.utilities.feature

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginSurfaceCustomizationTest {
    @Test
    fun `saved order visibility and new elements reconcile`() {
        val entries = PluginSurfaceCustomization.decode(
            "plugin%3Aowner%3Aitem:false,builtin%3AHOME:true,removed:true",
            listOf("builtin:HOME", "builtin:SEARCH", "plugin:owner:item"),
        )

        assertEquals(
            listOf(
                PluginSurfaceCustomization.Entry("plugin:owner:item", false),
                PluginSurfaceCustomization.Entry("builtin:HOME"),
                PluginSurfaceCustomization.Entry("builtin:SEARCH"),
            ),
            entries,
        )
    }

    @Test
    fun `default order uses priority then stable key`() {
        assertEquals(
            listOf("high:a", "high:b", "low"),
            PluginSurfaceCustomization.defaultOrder(
                listOf("low" to 0, "high:b" to 10, "high:a" to 10),
            ),
        )
    }
}
