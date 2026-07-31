package com.discord.utilities.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChannelSelectorDirectMessageTest {

    @Test
    fun `existing dm selects at me channel then closes without creating`() {
        val events = mutableListOf<String>()

        routeDirectMessage(
            userId = 42L,
            findExistingChannelId = { userId -> events += "find:$userId"; 99L },
            createChannel = { _, _ -> events += "create" },
            selectChannel = { guildId, channelId -> events += "select:$guildId:$channelId" },
            onSelected = { events += "close" },
        )

        assertEquals(listOf("find:42", "select:0:99", "close"), events)
    }

    @Test
    fun `new dm waits for response before selecting and closing`() {
        val events = mutableListOf<String>()
        var complete: ((Long) -> Unit)? = null

        routeDirectMessage(
            userId = 42L,
            findExistingChannelId = { userId -> events += "find:$userId"; null },
            createChannel = { userId, completed ->
                events += "create:$userId"
                complete = completed
            },
            selectChannel = { guildId, channelId -> events += "select:$guildId:$channelId" },
            onSelected = { events += "close" },
        )

        assertEquals(listOf("find:42", "create:42"), events)
        assertFalse(events.any { it.startsWith("select:") })

        events += "response:123"
        checkNotNull(complete)(123L)

        assertEquals(
            listOf("find:42", "create:42", "response:123", "select:0:123", "close"),
            events,
        )
    }
}
