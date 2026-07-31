package com.discord.widgets.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class WidgetSettingsAuthorizedAppsContractTest {
    @Test
    fun `layout owns the only authorized apps header and back remains bound`() {
        val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        val layout = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            File(root, "app/src/main/res/layout/widget_settings_authorized_apps.xml"),
        )
        val toolbars = layout.getElementsByTagName("androidx.appcompat.widget.Toolbar")
        assertEquals(1, toolbars.length)
        val toolbar = toolbars.item(0) as Element
        assertEquals("@string/authorized_apps", toolbar.getAttribute("app:title"))
        assertEquals("@string/user_settings", toolbar.getAttribute("app:subtitle"))
        assertEquals("@string/back", toolbar.getAttribute("app:navigationContentDescription"))
        val elements = layout.getElementsByTagName("*")
        val textValues = (0 until elements.length)
            .map { (elements.item(it) as Element).getAttribute("android:text") }
        assertFalse(textValues.contains("@string/authorized_apps"))
        assertEquals(1, textValues.count { it == "@string/applications_and_connections" })

        val source = File(
            root,
            "app/src/main/java/com/discord/widgets/settings/WidgetSettingsAuthorizedApps.kt",
        ).readText()
        assertFalse(source.contains("setActionBarTitle("))
        assertFalse(source.contains("setActionBarSubtitle("))
        assertTrue(source.contains("setActionBarDisplayHomeAsUpEnabled()"))
        assertTrue(source.contains("SettingsNavigation.bindBack(this)"))
        assertTrue(source.contains("R.id.authorized_apps_list"))
    }
}
