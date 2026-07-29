package com.discord.overlay

import com.discord.api.channel.Channel
import com.discord.views.OverlayMenuBubbleDialog
import com.discord.widgets.user.search.WidgetGlobalSearchModel
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayVoicePrerequisitesTest {
    @Test
    fun `menu excludes me and caps visible participants at three`() {
        val (visible, overflow) = OverlayMenuBubbleDialog.visibleParticipants(
            listOf(1L, 2L, 3L, 4L, 5L),
        ) { it == 1L }

        assertEquals(listOf(2L, 3L, 4L), visible)
        assertEquals(1, overflow)
    }

    @Test
    fun `selector keeps only voice channel rows`() {
        val voice = item(Channel.GUILD_VOICE, 1L)
        val stage = item(Channel.GUILD_STAGE_VOICE, 2L)
        val text = item(Channel.GUILD_TEXT, 3L)
        val header = WidgetGlobalSearchModel.ItemHeader(0)

        assertEquals(
            listOf(voice, stage),
            OverlayVoiceSelectorBubbleDialog.voiceChannelRows(listOf(header, voice, text, stage)),
        )
    }

    private fun item(type: Int, id: Long) = WidgetGlobalSearchModel.ItemChannel(
        matchedResult = WidgetGlobalSearchModel.EMPTY_MATCH_RESULT,
        channel = channel(type, id),
    )

    private fun channel(type: Int, id: Long) = Channel(
        topic = null,
        type = type,
        guildId = 1L,
        name = "channel-$id",
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
