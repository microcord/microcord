package com.discord.utilities.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationUtilitiesTest {
    @Test fun cacheGroupsDeduplicatesCapsAndCancelsByChannel() {
        val cache = NotificationCache(20)
        (1L..21L).forEach { cache.add(message(it)) }
        cache.add(message(21, body = "updated"))

        val snapshot = requireNotNull(cache.get(CHANNEL))
        assertEquals(20, snapshot.messages.size)
        assertEquals(2L, snapshot.messages.first().messageId)
        assertEquals("updated", snapshot.messages.last().body)
        assertEquals(NotificationCache.notificationId(CHANNEL), cache.removeChannel(CHANNEL))
        assertNull(cache.get(CHANNEL))
    }

    @Test fun ackDrivenClearIsSkippedExactlyOnceAfterAReply() {
        val cache = NotificationCache(20)
        cache.add(message(1))
        cache.setIgnoreNextClearForAck(CHANNEL, true)

        // The reply's own ack must not cancel the notification...
        assertNull(cache.removeChannel(CHANNEL, isAckRequest = true))
        assertNotNull(cache.get(CHANNEL))
        // ...but the exemption is spent, so the next ack does.
        assertEquals(NotificationCache.notificationId(CHANNEL), cache.removeChannel(CHANNEL, isAckRequest = true))
        assertNull(cache.get(CHANNEL))
    }

    @Test fun userDrivenClearIgnoresTheAckExemption() {
        val cache = NotificationCache(20)
        cache.add(message(1))
        cache.setIgnoreNextClearForAck(CHANNEL, true)

        assertEquals(NotificationCache.notificationId(CHANNEL), cache.removeChannel(CHANNEL, isAckRequest = false))
        assertNull(cache.get(CHANNEL))
    }

    @Test fun actionValidationRejectsForgedOrStaleShapes() {
        assertTrue(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_MARK_READ, CHANNEL, 9, -1, 3,
        ) is NotificationCommand.MarkRead)
        assertTrue(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_REPLY, CHANNEL, -1, -1, 3, "hello",
        ) is NotificationCommand.Reply)
        assertNull(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_MARK_READ, -1, 9, -1, 3,
        ))
        assertNull(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_REPLY, CHANNEL, -1, -1, -1, "hello",
        ))
        assertNull(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_REPLY, CHANNEL, -1, -1, 3, " ",
        ))
        assertNull(NotificationActionValidator.validate("forged", CHANNEL, 9, 1, 3))
    }

    private fun message(id: Long, body: String = "body") = NotificationData(
        NotificationKind.MESSAGE,
        CHANNEL,
        id,
        title = "title",
        body = body,
        authGeneration = 3,
    )

    companion object { private const val CHANNEL = 42L }
}
