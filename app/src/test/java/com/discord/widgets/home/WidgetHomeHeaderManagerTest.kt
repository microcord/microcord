package com.discord.widgets.home

import com.discord.api.channel.Channel
import com.discord.api.user.NsfwAllowance
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
    fun `guild header actions match screen width and thread experiment`() {
        val wide = WidgetHomeHeaderManager.menuState(
            channelType = Channel.GUILD_TEXT,
            threadExperimentEnabled = true,
            threadCount = 4,
        )
        val small = WidgetHomeHeaderManager.menuState(
            channelType = Channel.GUILD_TEXT,
            isSmallScreen = true,
            threadExperimentEnabled = true,
            threadCount = 4,
        )

        assertTrue(wide.search)
        assertTrue(wide.sidePanel)
        assertTrue(wide.threadBrowser)
        assertFalse(small.search)
        assertTrue(small.threadBrowser)
    }

    @Test
    fun `nsfw gate hides the thread browser`() {
        val state = WidgetHomeHeaderManager.menuState(
            channelType = Channel.GUILD_FORUM,
            threadExperimentEnabled = true,
            threadCount = 1,
            isChannelNsfw = true,
            nsfwAllowed = NsfwAllowance.DISALLOWED,
        )

        assertFalse(state.threadBrowser)
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
        assertTrue(dm.startCall)
        assertTrue(dm.startVideoCall)
        assertTrue(connectedDm.stopCall)
        assertFalse(systemDm.startCall)
        assertFalse(systemDm.stopCall)
    }

    @Test
    fun `pins survive every message header transition without changing existing actions`() {
        val supported = listOf(
            Channel.GUILD_TEXT,
            Channel.GUILD_ANNOUNCEMENT,
            Channel.DM,
            Channel.GROUP_DM,
            Channel.ANNOUNCEMENT_THREAD,
            Channel.PUBLIC_THREAD,
            Channel.PRIVATE_THREAD,
            Channel.GUILD_VOICE,
        )

        supported.forEach { type ->
            val state = WidgetHomeHeaderManager.menuState(type)
            assertTrue("pins hidden for channel type $type", state.pins)
            assertTrue("pins disabled for channel type $type", state.pinsEnabled)
        }
        assertFalse(WidgetHomeHeaderManager.menuState(Channel.GUILD_FORUM).pins)
        assertFalse(WidgetHomeHeaderManager.menuState(Channel.DIRECTORY).pins)
        assertTrue(WidgetHomeHeaderManager.menuState(Channel.DM).sidePanel)
        assertTrue(WidgetHomeHeaderManager.menuState(Channel.DM).startCall)
        assertTrue(WidgetHomeHeaderManager.menuState(Channel.GUILD_TEXT).search)
    }

    @Test
    fun `nsfw gate disables visible pins`() {
        val state = WidgetHomeHeaderManager.menuState(
            channelType = Channel.GUILD_TEXT,
            isChannelNsfw = true,
            isNsfwUnconsented = true,
            nsfwAllowed = NsfwAllowance.DISALLOWED,
        )

        assertTrue(state.pins)
        assertFalse(state.pinsEnabled)
    }

    @Test
    fun `toolbar declares one persistent accessible tinted pin action`() {
        val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        val menu = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "app/src/main/res/menu/menu_chat_toolbar.xml"))
        val pins = (0 until menu.getElementsByTagName("item").length)
            .map { menu.getElementsByTagName("item").item(it) as Element }
            .filter { it.getAttribute("android:id") == "@+id/menu_chat_pins" }

        assertEquals(1, pins.size)
        assertEquals("@drawable/ic_pin_24dp", pins.single().getAttribute("android:icon"))
        assertEquals("@string/pinned_messages", pins.single().getAttribute("android:title"))
        assertEquals("?attr/colorInteractiveNormal", pins.single().getAttribute("app:iconTint"))
        assertEquals("always", pins.single().getAttribute("app:showAsAction"))
    }

    @Test
    fun `unread badge only renders positive counts`() {
        assertNull(WidgetHomeHeaderManager.unreadCountText(-1))
        assertNull(WidgetHomeHeaderManager.unreadCountText(0))
        assertEquals("12", WidgetHomeHeaderManager.unreadCountText(12))
    }
}
