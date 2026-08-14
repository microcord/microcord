package com.discord.widgets.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeComponentGalleryTest {
    @Test
    fun galleryContainsBroadThemedComponentCoverage() {
        val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        val layout = File(root, "app/src/main/res/layout/widget_settings_design_system_page.xml").readText()

        assertTrue("com.discord.views.CheckedSetting" in layout)
        assertTrue("com.google.android.material.button.MaterialButton" in layout)
        assertTrue("com.google.android.material.textfield.TextInputLayout" in layout)
        assertTrue("com.google.android.material.tabs.TabLayout" in layout)
        assertTrue("com.google.android.material.chip.ChipGroup" in layout)
    }

    @Test
    fun mirrorsReactNativeDeveloperMenus() {
        val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        val design = File(root, "app/src/main/res/layout/widget_settings_design_systems.xml").readText()
        val pages = File(root, "app/src/main/res/layout/widget_settings_design_system_page.xml").readText()
        val bot = File(root, "app/src/main/res/layout/widget_settings_bot_ui_kit.xml").readText()

        listOf("Components", "In Progress", "Experimental", "Legacy Audit", "Segmented Control", "Form Primitives", "Haptics")
            .forEach { assertTrue(it in design) }
        listOf("Text Display", "String Select", "User Select", "Role Select", "Mentionable Select", "Channel Select", "Reset Modal State")
            .forEach { assertTrue(it in bot || it.replace(" ", "_").lowercase() in bot) }
        assertTrue("widget_chat_list_bot_ui_select_component" in File(root, "app/src/main/res/layout/view_bot_ui_kit_select.xml").readText())
        assertEquals(25, WidgetSettingsDesignSystemPage.Page.entries.size)
        WidgetSettingsDesignSystemPage.Page.entries.filter { it != WidgetSettingsDesignSystemPage.Page.EVERYTHING }
            .forEach { assertTrue("design_page_${it.name.lowercase()}" in pages) }
        listOf("Semantic Types", "Impact / Notification Types", "Gesture / UI Types", "Platform Haptic Types", "Pattern Presets")
            .forEach { assertTrue(it in pages) }
    }
}
