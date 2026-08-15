package com.discord.utilities.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.fcm.canDisplayInApp

class NotificationUtilitiesTest {
    @Test fun foregroundDeliveryRequiresAuthAndInAppSetting() {
        assertTrue(canDisplayInApp(NotificationClient.SettingsV2(token = "token", isEnabledInApp = true)))
        assertFalse(canDisplayInApp(NotificationClient.SettingsV2(token = null, isEnabledInApp = true)))
        assertFalse(canDisplayInApp(NotificationClient.SettingsV2(token = "token", isEnabledInApp = false)))
    }

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
            NotificationActionReceiver.ACTION_MARK_READ, CHANNEL, 9, -1, "session",
        ) is NotificationCommand.MarkRead)
        assertTrue(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_REPLY, CHANNEL, -1, -1, "session", "hello",
        ) is NotificationCommand.Reply)
        assertNull(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_MARK_READ, -1, 9, -1, "session",
        ))
        assertNull(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_REPLY, CHANNEL, -1, -1, null, "hello",
        ))
        assertNull(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_REPLY, CHANNEL, -1, -1, "session", " ",
        ))
        assertNull(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_REPLY, CHANNEL, -1, -1, "session", "x".repeat(2_001),
        ))
        assertTrue(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_DELETE, CHANNEL, -1, -1, null,
        ) is NotificationCommand.Delete)
        assertTrue(NotificationActionValidator.validate(
            NotificationActionReceiver.ACTION_CANCEL, -1, -1, 9, null,
        ) is NotificationCommand.Cancel)
        assertNull(NotificationActionValidator.validate("forged", CHANNEL, 9, 1, "session"))
    }

    @Test fun actionSessionSurvivesProcessStateButNotAccountChanges() {
        val session = requireNotNull(NotificationActionSession.fromToken("account-a-token"))
        assertEquals(64, session.length)
        assertTrue(NotificationActionSession.matches(session, "account-a-token"))
        assertFalse(NotificationActionSession.matches(session, "account-b-token"))
        assertFalse(NotificationActionSession.matches(session, null))
        assertFalse(session.contains("account-a-token"))
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
