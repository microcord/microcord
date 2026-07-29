package com.discord.utilities.permissions

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPermissionsManagerTest {
    private val manager = VideoPermissionsManager()

    @Test
    fun afkChannelNeverAllowsVideo() {
        val channel = channel(Channel.DM, id = 10L)

        assertFalse(manager.hasVideoPermission(channel, 10L, Permission.STREAM))
    }

    @Test
    fun privateChannelsAllowVideoWithoutGuildPermission() {
        assertTrue(manager.hasVideoPermission(channel(Channel.DM), null, null))
        assertTrue(manager.hasVideoPermission(channel(Channel.GROUP_DM), null, null))
    }

    @Test
    fun guildChannelsRequireStreamPermission() {
        val channel = channel(Channel.GUILD_VOICE)

        assertFalse(manager.hasVideoPermission(channel, null, null))
        assertFalse(manager.hasVideoPermission(channel, null, Permission.CONNECT))
        assertTrue(manager.hasVideoPermission(channel, null, Permission.STREAM))
    }

    private fun channel(type: Int, id: Long = 1L) = Channel(
        topic = null,
        type = type,
        guildId = if (type == Channel.DM || type == Channel.GROUP_DM) 0L else 2L,
        name = null,
        lastMessageId = 0L,
        id = id,
        ownerId = 0L,
        recipientIds = null,
        recipients = null,
        position = 0,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0L,
        applicationId = 0L,
        nicks = null,
        nsfw = false,
        parentId = 0L,
        memberListId = null,
        rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null,
        rtcRegion = null,
        flags = null,
        guildHashes = null,
        availableTags = null,
        appliedTags = null,
        threadMetadata = null,
        messageCount = null,
        memberCount = null,
        memberIdsPreview = null,
        member = null,
    )
}
