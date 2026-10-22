package com.discord.widgets.channels

import com.discord.api.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPickerFlowTest {
    @Test
    fun pickerFiltersCategoriesAnnouncementsAndInaccessibleChannels() {
        val text = channel(1, Channel.GUILD_TEXT, "general")
        val hidden = channel(2, Channel.GUILD_TEXT, "hidden")
        val voice = channel(3, Channel.GUILD_VOICE, "voice")
        val announcement = channel(4, Channel.GUILD_ANNOUNCEMENT, "news")
        val category = channel(5, Channel.CATEGORY, "category")

        assertEquals(
            // Like the reference, a channel the user cannot see is still pickable (AFK, rules...).
            listOf(text, hidden, voice),
            WidgetChannelPickerBottomSheetViewModel.filterChannels(
                listOf(text, hidden, voice, announcement, category),
                hideAnnouncementChannels = true,
            ),
        )
    }

    @Test
    fun pickerSearchIsCaseSensitiveAndKeepsCreateAndSelectionRows() {
        val selected = channel(1, Channel.GUILD_TEXT, "General")
        val other = channel(2, Channel.GUILD_TEXT, "general-chat")

        val items = WidgetChannelPickerBottomSheetViewModel.buildChannelItemList(
            listOf(selected, other),
            selectedChannelId = selected.id,
            searchQuery = "General",
        )

        assertEquals(ChannelPickerAdapterItem.CreateChannelItem, items[0])
        assertEquals(listOf(selected), items.filterIsInstance<ChannelPickerAdapterItem.ChannelItem>().map { it.channel })
        assertTrue((items[1] as ChannelPickerAdapterItem.ChannelItem).isSelected)
    }

    @Test
    fun selectorAppliesCallerFilterAndReferenceOrdering() {
        val later = channel(1, Channel.GUILD_TEXT, "later", position = 2)
        val first = channel(2, Channel.GUILD_TEXT, "first", position = 1)
        val voiceWithoutConnect = channel(3, Channel.GUILD_VOICE, "voice")
        val excluded = channel(4, Channel.GUILD_TEXT, "excluded")

        val items = WidgetChannelSelector.Model.buildItems(
            listOf(later, first, voiceWithoutConnect, excluded),
            includeNoChannel = true,
            filterFunction = WidgetChannelSelector.SetFilterFunction(setOf(later.id, first.id, voiceWithoutConnect.id)),
        )

        assertEquals(null, items.first().channel)
        assertEquals(listOf(first, later, voiceWithoutConnect), items.drop(1).map { it.channel })
    }

    @Test
    fun resultSelectionOrdinalsMatchTheReferenceWireContract() {
        assertEquals(0, WidgetChannelPickerBottomSheet.SelectionType.UNKNOWN.ordinal)
        assertEquals(1, WidgetChannelPickerBottomSheet.SelectionType.CREATE_CHANNEL.ordinal)
        assertEquals(2, WidgetChannelPickerBottomSheet.SelectionType.CHANNEL.ordinal)
        assertEquals(
            WidgetChannelPickerBottomSheet.SelectionType.UNKNOWN,
            WidgetChannelPickerBottomSheet.SelectionType.fromInt(99),
        )
        assertFalse(WidgetChannelSelector.TypeFilterFunction(Channel.GUILD_TEXT).includeChannel(channel(1, Channel.GUILD_VOICE, "v")))
    }

    private fun channel(
        id: Long,
        type: Int,
        name: String,
        position: Int = 0,
    ) = Channel(
        topic = null,
        type = type,
        guildId = 100,
        name = name,
        lastMessageId = 0,
        id = id,
        ownerId = 0,
        recipientIds = null,
        recipients = null,
        position = position,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0,
        applicationId = 0,
        nicks = null,
        nsfw = false,
        parentId = 0,
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
