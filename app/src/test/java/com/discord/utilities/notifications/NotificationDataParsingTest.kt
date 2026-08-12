package com.discord.utilities.notifications

import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.message.activity.MessageActivityType
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerType
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.fcm.mainIconUrl
import com.discord.utilities.fcm.messageTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import com.discord.utilities.notifications.NotificationKind
import org.junit.Test

class NotificationDataParsingTest {
    @Test fun parsesTheCompleteSanitized37FieldPayload() {
        val payload = NotificationData.parsePushPayload(mapOf(
            "type" to "MESSAGE_CREATE",
            "message_id" to "101",
            "message_activity_type" to "2",
            "message_application_name" to "Example Game",
            "message_type_" to "19",
            "message_content" to "sanitized message",
            "parent_name" to "parent",
            "parent_id" to "102",
            "channel_type" to "0",
            "channel_name" to "general",
            "channel_id" to "103",
            "channel_icon" to "channel-icon",
            "channel_ids" to "103,104",
            "rtc_region" to "example-region",
            "user_id" to "105",
            "user_username" to "fixture-user",
            "user_discriminator" to "1234",
            "user_avatar" to "user-avatar",
            "user_guild_avatar" to "member-avatar",
            "rel_type" to "1",
            "deeplink" to "https://discord.com/channels/1/2",
            "guild_id" to "106",
            "guild_name" to "Fixture Guild",
            "guild_icon" to "guild-icon",
            "activity_type" to "0",
            "activity_name" to "Fixture Activity",
            "application_id" to "107",
            "application_name" to "Fixture Application",
            "application_icon" to "application-icon",
            "message" to STICKER_JSON,
            "title" to "Fixture title",
            "subtitle" to "Fixture subtitle",
            "title_resource_name" to "fixture_title",
            "subtitle_resource_name" to "fixture_subtitle",
            "stage_instance_topic" to "Fixture topic",
            "guild_scheduled_event_entity_type" to "2",
            "guild_scheduled_event_id" to "108",
            "notification_type" to "reminder",
            "tracking_type" to "fixture_tracking",
            "notif_type_id" to "1",
            "sent_at_ms" to "1420070400000",
            "expand_subtitle" to "TRUE",
            "notification_id" to "fixture-notification",
        ))

        assertEquals(39, NotificationData.PushPayload::class.java.declaredConstructors.single().parameterCount)
        assertEquals(PushType.MESSAGE_CREATE, payload.pushType)
        assertEquals(101L, payload.messageId)
        assertEquals(MessageActivityType.SPECTATE, payload.messageActivityType)
        assertEquals(19, payload.messageType)
        assertEquals("parent", payload.parentChannel?.name)
        assertEquals(102L, payload.parentChannel?.id)
        assertEquals(Channel.GUILD_TEXT, payload.channel.type)
        assertEquals(103L, payload.channel.id)
        assertEquals(listOf(103L, 104L), payload.acknowledgementChannelIds)
        assertEquals(106L, payload.normalizedGuildId)
        assertEquals(GuildScheduledEventEntityType.VOICE, payload.guildScheduledEventEntityType)
        assertEquals(PushNotificationType.REMINDER, payload.notificationType)
        assertEquals(StickerFormatType.APNG, payload.sticker?.formatType)
        assertEquals(StickerType.GUILD, payload.sticker?.type)
        assertEquals("fixture.png", payload.attachmentFilename)
        assertTrue(payload.expandSubtitle)
        assertTrue(payload.isGroupConversation)
        assertTrue(payload.isValid())
        assertFalse(payload.toString().contains("sanitized message"))
    }

    @Test fun recognizesEvery12621PushTypeAndItsValidityRules() {
        val alwaysValid = listOf(
            PushType.APPLICATION_LIBRARY_INSTALL_COMPLETE,
            PushType.CALL_RING,
            PushType.GENERIC_PUSH_NOTIFICATION_SENT,
            PushType.MESSAGE_CREATE,
            PushType.RELATIONSHIP_ADD,
            PushType.STAGE_INSTANCE_CREATE,
        )
        alwaysValid.forEach { assertTrue(parse(it).isValid()) }

        assertTrue(parse(PushType.ACTIVITY_START, "activity_type" to "0").isValid())
        assertFalse(parse(PushType.ACTIVITY_START, "activity_type" to "1").isValid())
        assertTrue(parse(PushType.FORUM_THREAD_CREATED).isValid())
        listOf("1", "2", "3").forEach { entityType ->
            assertTrue(parse(
                PushType.GUILD_SCHEDULED_EVENT_UPDATE,
                "guild_scheduled_event_entity_type" to entityType,
            ).isValid())
        }
        assertFalse(parse(
            PushType.GUILD_SCHEDULED_EVENT_UPDATE,
            "guild_scheduled_event_entity_type" to "0",
        ).isValid())
    }

    @Test fun malformedScalarsUseReferenceSentinelsWithoutThrowing() {
        val payload = NotificationData.parsePushPayload(mapOf(
            "type" to "message_create",
            "message_id" to "not-a-long",
            "message_activity_type" to "not-an-int",
            "message_type_" to "",
            "channel_type" to "invalid",
            "channel_id" to "invalid",
            "channel_ids" to "1,bad,-1,-2, 3",
            "user_discriminator" to "invalid",
            "guild_scheduled_event_entity_type" to "99",
            "guild_scheduled_event_id" to "invalid",
            "expand_subtitle" to "yes",
            "message" to "{not-json}",
        ))

        assertNull(payload.pushType)
        assertEquals(NotificationData.UNSET_LONG, payload.messageId)
        assertNull(payload.messageActivityType)
        assertEquals(NotificationData.UNSET_INT, payload.messageType)
        assertEquals(NotificationData.UNSET_INT, payload.channel.type)
        assertEquals(listOf(1L, -2L), payload.acknowledgementChannelIds)
        assertNull(payload.userDiscriminator)
        assertEquals(GuildScheduledEventEntityType.UNKNOWN, payload.guildScheduledEventEntityType)
        assertNull(payload.guildScheduledEventId)
        assertNull(payload.sticker)
        assertNull(payload.attachmentFilename)
        assertFalse(payload.expandSubtitle)
        assertFalse(payload.isValid())
    }

    @Test fun absentValuesMatchReferenceDefaultsAndDmGuildNormalization() {
        val empty = NotificationData.parsePushPayload(emptyMap())
        assertEquals("", empty.type)
        assertEquals(NotificationData.UNSET_LONG, empty.messageId)
        assertEquals(NotificationData.UNSET_INT, empty.messageType)
        assertEquals(NotificationData.UNSET_LONG, empty.guildId)
        assertEquals(NotificationData.UNSET_LONG, empty.normalizedGuildId)
        assertEquals(NotificationData.UNSET_LONG, empty.parentChannel?.id)
        assertTrue(empty.acknowledgementChannelIds.isEmpty())
        assertFalse(empty.expandSubtitle)
        assertFalse(empty.isValid())

        val dm = NotificationData.parsePushPayload(mapOf(
            "type" to PushType.MESSAGE_CREATE.wireValue,
            "channel_type" to Channel.DM.toString(),
        ))
        assertEquals(0L, dm.guildId)
        assertEquals(0L, dm.normalizedGuildId)
        assertFalse(dm.isGroupConversation)
    }

    @Test fun messageTitlesAndIconsFollowConversationType() {
        val dm = NotificationData.parsePushPayload(mapOf(
            "type" to PushType.MESSAGE_CREATE.wireValue,
            "channel_type" to Channel.DM.toString(),
            "channel_id" to "10",
            "user_id" to "20",
            "user_username" to "sender",
            "user_avatar" to "avatar",
        ))
        val guild = NotificationData.parsePushPayload(mapOf(
            "type" to PushType.MESSAGE_CREATE.wireValue,
            "channel_type" to Channel.GUILD_TEXT.toString(),
            "channel_id" to "11",
            "channel_name" to "general",
            "guild_id" to "30",
            "guild_name" to "Guild",
            "guild_icon" to "icon",
        ))

        assertEquals("sender", dm.messageTitle())
        assertTrue(dm.mainIconUrl()?.contains("/avatars/20/avatar") == true)
        assertEquals("Guild #general", guild.messageTitle())
        assertTrue(guild.mainIconUrl()?.contains("/icons/30/icon") == true)
    }

    @Test fun keepsPushTypesThisBuildDoesNotKnowWhenTheyCarrySomethingToShow() {
        val unknown = NotificationData.parsePushPayload(
            mapOf("type" to "FRIEND_ONLINE", "title" to "Alex is online", "deeplink" to "discord://app/channels/@me/1"),
        )
        assertTrue(unknown.isValid())
        assertEquals("discord://app/channels/@me/1", unknown.deepLink)

        // Nothing renderable: dropping it is right, otherwise it shows as an empty notification.
        assertFalse(NotificationData.parsePushPayload(mapOf("type" to "FRIEND_ONLINE")).isValid())
    }

    @Test fun channellessNotificationsDoNotShareAnId() {
        fun standalone(type: String, title: String) = NotificationData(
            kind = NotificationKind.GENERAL,
            channelId = 0L,
            title = title,
            body = "body",
            authGeneration = 0,
            type = type,
        ).standaloneNotificationId()

        assertNotEquals(standalone("FRIEND_ONLINE", "Alex"), standalone("PROFILE_UPDATE", "Sam"))
        assertEquals(standalone("FRIEND_ONLINE", "Alex"), standalone("FRIEND_ONLINE", "Alex"))
    }

    @Test fun dismissalTypesAreValidAndFlagged() {
        listOf(PushType.CALL_ACK, PushType.CALL_RING_END, PushType.CHANNEL_ACK).forEach { type ->
            assertTrue(type.isDismissal)
            assertTrue(parse(type, "channel_id" to "42").isValid())
        }
        listOf(PushType.CALL_CONNECT, PushType.FRIEND_SUGGESTION_CREATE, PushType.GUILD_STREAM_START).forEach {
            assertFalse(it.isDismissal)
            assertTrue(parse(it, "channel_id" to "42").isValid())
        }
    }

    @Test fun notificationChannelComesFromTheNotificationType() {
        fun channelFor(type: PushType, vararg fields: Pair<String, String>) = parse(type, *fields).let {
            PushChannel.forPush(it.pushType, it.notifTypeId, it.channel.type)
        }

        assertEquals(PushChannel.REACTIONS, channelFor(PushType.GENERIC_PUSH_NOTIFICATION_SENT, "notif_type_id" to "23"))
        assertEquals(PushChannel.MESSAGES, channelFor(PushType.MESSAGE_CREATE, "notif_type_id" to "1", "channel_type" to "0"))
        assertEquals(PushChannel.DIRECT_MESSAGES, channelFor(PushType.MESSAGE_CREATE, "notif_type_id" to "1", "channel_type" to "1"))
        assertEquals(PushChannel.CALLS, channelFor(PushType.CALL_RING))
        assertNull(channelFor(PushType.GENERIC_PUSH_NOTIFICATION_SENT, "notif_type_id" to "9999"))
    }

    @Test fun dismissalsCarryTheirAckTargetsAndWatermark() {
        val ack = parse(PushType.CHANNEL_ACK, "channel_ids" to "103,104", "sent_at_ms" to "1420070400000")
        assertEquals(listOf(103L, 104L), ack.ackChannelIds)
        assertEquals(SnowflakeUtils.fromTimestamp(1420070400000L), ack.ackMessageId)

        val single = parse(PushType.CHANNEL_ACK, "channel_id" to "42")
        assertEquals(listOf(42L), single.ackChannelIds)
        assertEquals(0L, single.ackMessageId)
    }

    private fun parse(type: PushType, vararg fields: Pair<String, String>) =
        NotificationData.parsePushPayload(mapOf("type" to type.wireValue, *fields))

    companion object {
        private const val STICKER_JSON = """{"attachments":[{"id":"200","filename":"fixture.png","size":1,"url":"https://example.invalid/fixture.png","proxy_url":"https://example.invalid/fixture.png"}],"stickers":[{"id":"201","pack_id":"202","guild_id":"203","name":"fixture-sticker","description":"sanitized","format_type":2,"tags":"fixture","type":2}]}"""
    }
}
