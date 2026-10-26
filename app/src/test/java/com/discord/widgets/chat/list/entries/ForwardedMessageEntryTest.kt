package com.discord.widgets.chat.list.entries

import com.discord.api.message.Message as ApiMessage
import com.discord.api.message.MessageFlags
import com.discord.api.message.MessageReference
import com.discord.api.message.MessageSnapshot
import com.discord.models.message.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardedMessageEntryTest {
    @Test
    fun distinguishesLoadingDeletedAndUnavailableSnapshots() {
        val loading = ForwardedMessageEntry.resolve(forward(messageSnapshots = null)).single()
        val deleted = ForwardedMessageEntry.resolve(
            forward(flags = MessageFlags.HAS_SNAPSHOT or MessageFlags.SOURCE_MESSAGE_DELETED, messageSnapshots = emptyList()),
        ).single()
        val unavailable = ForwardedMessageEntry.resolve(forward(messageSnapshots = emptyList())).single()

        assertEquals(ForwardedMessageEntry.State.LOADING, loading.state)
        assertEquals(ForwardedMessageEntry.State.DELETED, deleted.state)
        assertEquals(ForwardedMessageEntry.State.UNAVAILABLE, unavailable.state)
    }

    @Test
    fun snapshotUsesParentIdentityAndStopsAtOneNestedLevel() {
        val nested = ApiMessage(
            id = 2,
            channelId = 3,
            content = "nested-sanitized",
            flags = MessageFlags.HAS_SNAPSHOT,
            messageReference = MessageReference(1, 3, 1, type = 1),
            messageSnapshots = emptyList(),
        )
        val parent = forward(
            messageSnapshots = listOf(MessageSnapshot(nested)),
        )

        val resolved = ForwardedMessageEntry.resolve(parent).single()
        assertEquals(ForwardedMessageEntry.State.CONTENT, resolved.state)
        assertEquals(parent.author, resolved.message!!.author)
        assertEquals("nested-sanitized", resolved.message!!.content)
        assertEquals(
            ForwardedMessageEntry.State.DEPTH_LIMIT,
            ForwardedMessageEntry.resolve(resolved.message!!, ForwardedMessageEntry.MAX_RENDER_DEPTH).single().state,
        )
        assertTrue(resolved.key.contains(parent.id.toString()))
    }

    @Test
    fun sourceFooterDoesNotExposeUnknownOrInaccessibleChannels() {
        assertEquals(
            ForwardedSourcePrivacy.Visibility.CHANNEL,
            ForwardedSourcePrivacy.visibility(1, 1, channelKnown = true, channelAccessible = true, guildKnown = true),
        )
        assertEquals(
            ForwardedSourcePrivacy.Visibility.CHANNEL,
            ForwardedSourcePrivacy.visibility(2, 1, channelKnown = true, channelAccessible = true, guildKnown = true),
        )
        assertEquals(
            ForwardedSourcePrivacy.Visibility.HIDDEN,
            ForwardedSourcePrivacy.visibility(1, 1, channelKnown = true, channelAccessible = false, guildKnown = true),
        )
        assertEquals(
            ForwardedSourcePrivacy.Visibility.GUILD,
            ForwardedSourcePrivacy.visibility(2, 1, channelKnown = true, channelAccessible = false, guildKnown = true),
        )
        assertEquals(
            "Sanitized Guild",
            ForwardedSourcePrivacy.label(
                ForwardedSourcePrivacy.Visibility.GUILD,
                channelName = "private-channel",
                guildName = "Sanitized Guild",
            ),
        )
        assertEquals(
            "Sanitized Guild",
            ForwardedSourcePrivacy.label(
                ForwardedSourcePrivacy.Visibility.CHANNEL,
                channelName = "private-channel",
                guildName = "Sanitized Guild",
            ),
        )
        assertEquals(
            ForwardedSourcePrivacy.Visibility.HIDDEN,
            ForwardedSourcePrivacy.visibility(2, 1, channelKnown = false, channelAccessible = false, guildKnown = false),
        )
        assertTrue(
            ForwardedSourcePrivacy.isNavigable(
                ForwardedSourcePrivacy.Visibility.CHANNEL,
                channelKnown = true,
                messageKnown = true,
                sourceDeleted = false,
            ),
        )
        assertFalse(
            ForwardedSourcePrivacy.isNavigable(
                ForwardedSourcePrivacy.Visibility.GUILD,
                channelKnown = true,
                messageKnown = true,
                sourceDeleted = false,
            ),
        )
        // An archived thread is never cached, but the jump can still fetch it.
        assertTrue(
            ForwardedSourcePrivacy.isNavigable(
                ForwardedSourcePrivacy.Visibility.GUILD,
                channelKnown = false,
                messageKnown = true,
                sourceDeleted = false,
            ),
        )
        assertFalse(
            ForwardedSourcePrivacy.isNavigable(
                ForwardedSourcePrivacy.Visibility.CHANNEL,
                channelKnown = true,
                messageKnown = true,
                sourceDeleted = true,
            ),
        )
    }

    private fun forward(
        flags: Long = MessageFlags.HAS_SNAPSHOT,
        messageSnapshots: List<MessageSnapshot>?,
    ) = Message(
        id = 10,
        channelId = 20,
        author = com.discord.api.user.User(30, "sender"),
        type = 0,
        flags = flags,
        messageReference = MessageReference(1, 2, 3, type = 1),
        messageSnapshots = messageSnapshots,
    )
}
