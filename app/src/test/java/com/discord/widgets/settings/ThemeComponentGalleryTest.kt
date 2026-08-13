package com.discord.widgets.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeComponentGalleryTest {
    @Test
    fun galleryContainsBroadThemedComponentCoverage() {
        val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        val layout = File(root, "app/src/main/res/layout/widget_settings_theme_component_gallery.xml").readText()

        assertTrue("com.discord.views.CheckedSetting" in layout)
        assertTrue("com.discord.views.StatusView" in layout)
        assertTrue("com.discord.views.ReactionView" in layout)
        assertTrue("com.google.android.material.button.MaterialButton" in layout)
        assertTrue("com.google.android.material.textfield.TextInputLayout" in layout)
    }
}
