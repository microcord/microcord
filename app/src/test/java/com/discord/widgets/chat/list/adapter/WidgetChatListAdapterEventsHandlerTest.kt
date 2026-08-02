package com.discord.widgets.chat.list.adapter

import com.discord.api.botuikit.ComponentType
import com.discord.api.user.User
import com.discord.models.message.Message
import com.discord.restapi.RestAPIParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChatListAdapterEventsHandlerTest {
    @Test fun componentInteractionDataMatchesButtonAndSelectPayloads() {
        assertNull(WidgetChatListAdapterEventsHandler.componentInteractionData(null, null))

        assertEquals(
            RestAPIParams.ComponentInteractionData.ButtonComponentInteractionData(
                ComponentType.BUTTON,
                "confirm",
            ),
            WidgetChatListAdapterEventsHandler.componentInteractionData("confirm", null),
        )
        assertEquals(
            RestAPIParams.ComponentInteractionData.SelectComponentInteractionData(
                ComponentType.SELECT,
                "roles",
                listOf("one", "two"),
            ),
            WidgetChatListAdapterEventsHandler.componentInteractionData(
                "roles",
                listOf("one", "two"),
            ),
        )
    }

    @Test fun tapTapIsOffByDefaultAndTreatmentUsesPluginActions() {
        val own = Message(id = 1, author = User(7, "me"), type = 0)
        val other = Message(id = 2, author = User(8, "other"), type = 0)

        assertFalse(TapTapBehavior.isEnabled(null))
        assertFalse(TapTapBehavior.isEnabled(0))
        assertTrue(TapTapBehavior.isEnabled(1))
        assertEquals(TapTapBehavior.Action.EDIT, TapTapBehavior.action(own, 7))
        assertEquals(TapTapBehavior.Action.REPLY, TapTapBehavior.action(other, 7))
        assertEquals(200L, TapTapBehavior.TIMEOUT_MS)
    }

    @Test fun tapTapLeavesNonReplyableMessageGesturesAlone() {
        assertFalse(TapTapBehavior.canHandle(Message(id = 1, type = -1)))
        assertFalse(TapTapBehavior.canHandle(Message(id = 2, type = -3)))
        assertFalse(TapTapBehavior.canHandle(Message(id = 3, type = 0, flags = 64)))
        assertFalse(TapTapBehavior.canHandle(Message(id = 4, type = 0, flags = 128)))
        assertTrue(TapTapBehavior.canHandle(Message(id = 5, type = 0)))
    }
}
