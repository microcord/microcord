package com.discord.widgets.servers

import com.discord.api.channel.Channel
import com.discord.models.guild.Guild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSettingsOverviewViewModelTest {
    private val guild = Guild(id = 1, name = "Test Guild", ownerId = 7)

    @Test
    fun `overview resolves afk and system channels`() {
        val voice = channel(id = 10, type = Channel.GUILD_VOICE, name = "afk")
        val text = channel(id = 11, type = Channel.GUILD_TEXT, name = "general")
        val loaded = state(
            guild.copy(afkChannelId = voice.id, systemChannelId = text.id),
            channels = listOf(voice, text),
        )

        assertEquals(voice.id, loaded.afkChannel?.id)
        assertEquals(text.id, loaded.systemChannel?.id)
    }

    @Test
    fun `all messages is disabled only above the member threshold`() {
        val limit = ServerSettingsOverviewViewModel.NOTIFY_ALL_MEMBER_LIMIT

        assertFalse(state(guild, memberCount = null).isAboveNotifyAllSize)
        assertFalse(state(guild, memberCount = limit).isAboveNotifyAllSize)
        assertTrue(state(guild, memberCount = limit + 1).isAboveNotifyAllSize)
    }

    private fun state(
        guild: Guild,
        channels: List<Channel> = emptyList(),
        memberCount: Int? = 0,
    ) = ServerSettingsOverviewViewModel.toViewState(
        ServerSettingsOverviewViewModel.StoreState(
            guild = guild,
            canManage = true,
            guildChannels = channels,
            approximateMemberCount = memberCount,
        ),
    ) as ServerSettingsOverviewViewModel.ViewState.Loaded

    private fun channel(id: Long, type: Int, name: String) = Channel(
        topic = null, type = type, guildId = guild.id, name = name, lastMessageId = 0, id = id,
        ownerId = 0, recipientIds = null, recipients = null, position = 0,
        permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false,
        parentId = 0, memberListId = null, rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null, rtcRegion = null, flags = null, guildHashes = null,
        availableTags = null, appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )
}
