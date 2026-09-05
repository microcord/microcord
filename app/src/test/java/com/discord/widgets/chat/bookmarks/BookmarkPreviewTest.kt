package com.discord.widgets.chat.bookmarks

import com.discord.api.channel.Channel
import com.discord.api.message.Message
import com.discord.api.message.MessageTypes
import com.discord.api.permission.Permission
import com.discord.api.savedmessage.SaveData
import com.discord.api.savedmessage.SavedMessage
import com.discord.api.utcdatetime.UtcDateTime
import org.junit.Assert.*
import org.junit.Test
import com.discord.models.message.Message as DomainMessage

class BookmarkPreviewTest {
    @Test
    fun missingMessagesStayUnavailableEvenWhenTheChannelIsAccessible() {
        val saved = saved().copy(message = null)
        assertFalse(canViewBookmark(saved, null, Permission.VIEW_CHANNEL))
        assertFalse(canViewBookmark(saved, bookmarkPreviewChannel(saved, "fixture").copy(type = Channel.DM), null))
    }

    @Test
    fun previewsRequireViewAccessButNotSendOrManagePermission() {
        val saved = saved()
        val channel = bookmarkPreviewChannel(saved, "fixture").copy(type = Channel.GUILD_TEXT)
        assertFalse(canViewBookmark(saved, channel, null))
        assertFalse(canViewBookmark(saved, channel, Permission.SEND_MESSAGES or Permission.MANAGE_MESSAGES))
        assertTrue(canViewBookmark(saved, channel, Permission.VIEW_CHANNEL))
        assertTrue(canViewBookmark(saved, channel.copy(type = Channel.DM), null))
        assertTrue(canViewBookmark(saved, channel.copy(type = Channel.GROUP_DM), null))
    }

    @Test
    fun serverReturnedMessagesCanUseRnsUnknownChannelFallback() {
        val saved = saved()
        assertTrue(canViewBookmark(saved, null, null))
        val channel = bookmarkPreviewChannel(saved, "unknown-channel")
        assertEquals(10000, channel.type)
        assertTrue(canViewBookmark(saved, channel, null))
        assertEquals(saved.saveData.channelId, channel.id)
        assertEquals(saved.saveData.guildId, channel.guildId)
        assertEquals("unknown-channel", channel.name)
        assertNull(channel.permissionOverwrites)
        assertEquals(0L, bookmarkPreviewChannel(saved.copy(saveData = saved.saveData.copy(guildId = null)), "fixture").guildId)
    }

    @Test
    fun commandRepliesRenderTheirBodyInsteadOfAnEmptyCommandInvocation() {
        listOf(MessageTypes.CHAT_INPUT_COMMAND, MessageTypes.CONTEXT_MENU_COMMAND, MessageTypes.REPLY).forEach { type ->
            val original = DomainMessage(20, channelId = 10, type = type, content = "response body")
            val preview = bookmarkPreviewMessage(original)
            assertEquals(MessageTypes.DEFAULT, preview.type)
            assertEquals("response body", preview.content)
            assertEquals(type, original.type)
            assertNull(preview.interaction)
            assertNull(preview.interactionMetadata)
        }
    }

    private fun saved() = SavedMessage(
        Message(id = 20, channelId = 10, content = "fixture"),
        SaveData(10, 20, UtcDateTime(1), "", "", "", 30, 7, "", null),
    )
}
