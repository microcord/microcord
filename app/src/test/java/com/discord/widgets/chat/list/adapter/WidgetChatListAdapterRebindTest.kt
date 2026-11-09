package com.discord.widgets.chat.list.adapter

import com.discord.api.message.MessageSnapshot
import com.discord.api.message.embed.EmbedField
import com.discord.api.message.embed.MessageEmbed
import com.discord.models.message.Message
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter.Companion.linksChannel
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter.Companion.renamedChannelIds
import com.discord.widgets.chat.list.entries.ForwardedMessageEntry
import com.discord.widgets.chat.list.entries.SpacerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChatListAdapterRebindTest {

    @Test fun equalSnapshotRenamesNothing() {
        // The store hands out a fresh map on every channel change anywhere.
        assertEquals(emptySet<Long>(), renamedChannelIds(hashMapOf(5L to "general"), hashMapOf(5L to "general")))
    }

    @Test fun reportsRenamedAddedAndRemovedChannels() {
        val old = mapOf(5L to "general", 6L to "old", 7L to "gone")
        val new = mapOf(5L to "general", 6L to "renamed", 8L to "new")

        assertEquals(setOf(6L, 7L, 8L), renamedChannelIds(old, new))
    }

    @Test fun onlyRowsLinkingARenamedChannelRebind() {
        val renamed = setOf(5L)
        assertTrue(row(Message(id = 1L, content = "see <#5>")).linksChannel(renamed))
        assertFalse(row(Message(id = 1L, content = "see <#6> and #5")).linksChannel(renamed))
        assertFalse(SpacerEntry(1L).linksChannel(renamed))
    }

    @Test fun findsLinksInEmbedsRepliesAndSnapshots() {
        val renamed = setOf(5L)
        val linked = com.discord.api.message.Message(id = 2L, channelId = 1L, content = "<#5>")

        assertTrue(row(Message(id = 1L, embeds = listOf(embed("x <#5>")))).linksChannel(renamed))
        assertTrue(row(Message(id = 1L, referencedMessage = linked)).linksChannel(renamed))
        assertTrue(row(Message(id = 1L, messageSnapshots = listOf(MessageSnapshot(linked)))).linksChannel(renamed))
    }

    private fun row(message: Message) =
        ForwardedMessageEntry(message, snapshotIndex = 0, state = ForwardedMessageEntry.State.CONTENT)

    private fun embed(fieldValue: String) = MessageEmbed(
        title = null,
        type = null,
        description = null,
        url = null,
        timestamp = null,
        color = null,
        footer = null,
        image = null,
        thumbnail = null,
        video = null,
        provider = null,
        author = null,
        fields = listOf(EmbedField(name = "f", value = fieldValue, inline = null)),
    )
}
