package com.discord.widgets.user

import com.discord.stores.StoreNavigation
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetUserMentionsNavigationTest {
    @Test
    fun `guild mention jumps before selecting Home`() {
        assertNavigation(channelId = 20L, messageId = 30L, isEmbedded = false)
    }

    @Test
    fun `dm mention preserves exact channel and embedded dismissal order`() {
        assertNavigation(channelId = 40L, messageId = 50L, isEmbedded = true)
    }

    @Test
    fun `missing channel is still delegated to the message loader`() {
        assertNavigation(channelId = 60L, messageId = 70L, isEmbedded = true)
    }

    private fun assertNavigation(channelId: Long, messageId: Long, isEmbedded: Boolean) {
        val calls = mutableListOf<String>()

        navigateToMention(
            channelId = channelId,
            messageId = messageId,
            isEmbedded = isEmbedded,
            jumpToMessage = { channel, message -> calls += "jump:$channel:$message" },
            selectHomeTab = { action, dismissDialogs -> calls += "home:$action:$dismissDialogs" },
            dismiss = { calls += "dismiss" },
        )

        assertEquals(
            listOf(
                "jump:$channelId:$messageId",
                "home:${StoreNavigation.PanelAction.CLOSE}:$isEmbedded",
                "dismiss",
            ),
            calls,
        )
    }
}
