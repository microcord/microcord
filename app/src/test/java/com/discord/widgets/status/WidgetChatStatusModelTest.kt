package com.discord.widgets.status

import com.discord.models.application.Unread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChatStatusModelTest {
    @Test
    fun `banner requires a valid unread marker`() {
        val unread = unread(channelId = 10, count = 1)

        assertFalse(WidgetChatStatus.Model.createModel(false, emptySet(), unread).unreadVisible)
        assertTrue(WidgetChatStatus.Model.createModel(true, emptySet(), unread).unreadVisible)
        assertFalse(WidgetChatStatus.Model.createModel(true, emptySet(), unread(channelId = 0, count = 1)).unreadVisible)
    }

    @Test
    fun `attached history caps estimated unread count at fifty`() {
        val model = WidgetChatStatus.Model.createModel(true, emptySet(), unread(channelId = 10, count = 50))

        assertEquals(WidgetChatStatus.Model.ATTACHED_COUNT_CAP, model.unreadCount)
        assertTrue(model.isUnreadEstimate)
    }

    @Test
    fun `detached history caps estimated unread count at twenty five`() {
        val model = WidgetChatStatus.Model.createModel(true, setOf(10), unread(channelId = 10, count = 30))

        assertEquals(WidgetChatStatus.Model.DETACHED_COUNT_CAP, model.unreadCount)
        assertTrue(model.isUnreadEstimate)
    }

    private fun unread(channelId: Long, count: Int) = Unread(
        marker = Unread.Marker(channelId, messageId = 20),
        count = count,
    )
}
