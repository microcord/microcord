package com.discord.widgets.channels

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetCreateChannelTest {
    @Test
    fun `public channel has no permission overwrites`() {
        assertNull(WidgetCreateChannel.permissionOverwrites(1L, Channel.GUILD_TEXT, false, false, emptySet()))
    }

    @Test
    fun `private text channel denies everyone and allows selected roles`() {
        val overwrites = WidgetCreateChannel.permissionOverwrites(
            guildId = 1L,
            channelType = Channel.GUILD_TEXT,
            privateSwitchChecked = true,
            forcedPrivate = false,
            selectedRoleIds = linkedSetOf(2L, 3L),
        ).orEmpty()

        assertEquals(listOf("1", "2", "3"), overwrites.map { it.id })
        assertEquals(listOf(Permission.VIEW_CHANNEL, 0L, 0L), overwrites.map { it.deny })
        assertEquals(listOf(0L, Permission.VIEW_CHANNEL, Permission.VIEW_CHANNEL), overwrites.map { it.allow })
    }

    @Test
    fun `private voice channel gates connect instead of visibility`() {
        val overwrites = WidgetCreateChannel.permissionOverwrites(1L, Channel.GUILD_VOICE, true, false, emptySet()).orEmpty()

        assertEquals(Permission.CONNECT, overwrites.single().deny)
    }

    @Test
    fun `granting everyone cancels the private overwrite set`() {
        assertNull(WidgetCreateChannel.permissionOverwrites(1L, Channel.GUILD_TEXT, true, false, setOf(1L)))
    }
}
