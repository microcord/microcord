package com.discord.widgets.chat.list.adapter

import com.discord.api.botuikit.ComponentType
import com.discord.restapi.RestAPIParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
