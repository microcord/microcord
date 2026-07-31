package com.discord.widgets.home

import com.discord.api.channel.Channel
import com.discord.api.user.NsfwAllowance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `unread badge only renders positive counts`() {
        assertNull(WidgetHomeHeaderManager.unreadCountText(-1))
        assertNull(WidgetHomeHeaderManager.unreadCountText(0))
        assertEquals("12", WidgetHomeHeaderManager.unreadCountText(12))
    }
}
