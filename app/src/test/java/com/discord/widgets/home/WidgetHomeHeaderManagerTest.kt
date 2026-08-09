package com.discord.widgets.home

import com.discord.api.channel.Channel
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class WidgetHomeHeaderManagerTest {

    @Test
    fun `every message header offers search and members and nothing else`() {
        val messageChannels = listOf(
            Channel.GUILD_TEXT,
            Channel.GUILD_ANNOUNCEMENT,
            Channel.GUILD_FORUM,
            Channel.GUILD_MEDIA,
            Channel.DM,
            Channel.GROUP_DM,
            Channel.ANNOUNCEMENT_THREAD,
            Channel.PUBLIC_THREAD,
            Channel.PRIVATE_THREAD,
        )

        messageChannels.forEach { type ->
            val state = WidgetHomeHeaderManager.menuState(type)
            assertTrue("search hidden for channel type $type", state.search)
            assertTrue("members hidden for channel type $type", state.sidePanel)
        }
    }

    @Test
    fun `thread and private call actions match channel state`() {
        val archivedThread = WidgetHomeHeaderManager.menuState(
            channelType = Channel.PUBLIC_THREAD,
            isArchivedThread = true,
        )
        val dm = WidgetHomeHeaderManager.menuState(Channel.DM)
        val connectedDm = WidgetHomeHeaderManager.menuState(Channel.DM, isCallConnected = true)
        val systemDm = WidgetHomeHeaderManager.menuState(Channel.DM, isSystemDm = true)

        assertFalse(archivedThread.sidePanel)
        assertTrue(archivedThread.search)
        assertTrue(dm.startCall)
        assertTrue(dm.startVideoCall)
        assertTrue(connectedDm.stopCall)
        assertFalse(systemDm.startCall)
        assertFalse(systemDm.stopCall)
    }

    /** Both bar actions are `always`, so neither can be pushed into an overflow that is never shown. */
    @Test
    fun `toolbar pins search and members to the bar`() {
        val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        val menu = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "app/src/main/res/menu/menu_chat_toolbar.xml"))
        val items = (0 until menu.getElementsByTagName("item").length)
            .map { menu.getElementsByTagName("item").item(it) as Element }
            .associateBy { it.getAttribute("android:id") }

        listOf("@+id/menu_chat_search", "@+id/menu_chat_side_panel").forEach { id ->
            assertEquals("always", items.getValue(id).getAttribute("app:showAsAction"))
            assertEquals("?attr/colorInteractiveNormal", items.getValue(id).getAttribute("app:iconTint"))
        }
    }

    @Test
    fun `unread badge only renders positive counts`() {
        assertNull(WidgetHomeHeaderManager.unreadCountText(-1))
        assertNull(WidgetHomeHeaderManager.unreadCountText(0))
        assertEquals("12", WidgetHomeHeaderManager.unreadCountText(12))
    }
}
