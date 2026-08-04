package com.discord.widgets.share

import com.discord.api.channel.Channel
import com.discord.api.message.Message as ApiMessage
import com.discord.api.message.MessageFlags
import com.discord.api.permission.Permission
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.message.Message
import com.discord.widgets.chat.list.entries.ForwardedMessageEntry
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class ForwardedMessageTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    @Test
    fun gatewayAndRestSnapshotsRemainTyped() {
        val api = gson.fromJson(FORWARD_FIXTURE, ApiMessage::class.java)
        val snapshot = api.messageSnapshots!!.single().message

        assertEquals(1, api.messageReference!!.type)
        assertEquals("sanitized", snapshot.content)
        assertEquals("asset.bin", snapshot.attachments!!.single().filename)
        assertEquals("embed", snapshot.embeds!!.single().title)
        assertEquals("sticker", snapshot.stickerItems!!.single().name)
        assertEquals(1, snapshot.components!!.size)
        assertEquals("question", snapshot.poll!!.question.text)
        assertEquals(9L, snapshot.resolved!!.users!!.getValue(9).id)
        assertTrue(snapshot.messageSnapshots!!.single().message.content == "nested-sanitized")
        val entry = ForwardedMessageEntry.resolve(Message(api)).single()
        assertEquals("Resolved Nick", entry.message!!.mentions!!.single().member!!.nick)
        assertEquals(3L, entry.sourceGuildId)
        assertEquals(2L, entry.sourceChannelId)
        assertEquals(1L, entry.sourceMessageId)
        listOf(null, 0L).forEach { missingId ->
            val fallback = ForwardedMessageEntry.resolve(
                Message(
                    api.copy(
                        messageReference = api.messageReference!!.copy(
                            guildId = missingId,
                            channelId = missingId,
                            messageId = missingId,
                        ),
                    ),
                ),
            ).single()
            assertEquals(3L, fallback.sourceGuildId)
            assertEquals(2L, fallback.sourceChannelId)
            assertEquals(1L, fallback.sourceMessageId)
        }

        val body = ForwardMessageSender.body(Message(api), channel(2, 3, Channel.GUILD_TEXT))
        val json = JsonParser.parseString(gson.toJson(body)).asJsonObject
        assertEquals(setOf("message_reference"), json.keySet())
        assertEquals(1, json["message_reference"].asJsonObject["type"].asInt)
        assertEquals(2L, json["message_reference"].asJsonObject["channel_id"].asLong)
        assertFalse(json.toString().contains("sanitized"))
    }

    @Test
    fun sourceAndDestinationRulesFailClosed() {
        val source = channel(2, 3, Channel.GUILD_TEXT)
        val destination = channel(4, 5, Channel.GUILD_TEXT)
        val message = Message(gson.fromJson(FORWARDABLE_FIXTURE, ApiMessage::class.java))

        assertTrue(ForwardingPolicy.canForward(message, source, Permission.READ_MESSAGE_HISTORY))
        assertFalse(ForwardingPolicy.canForward(message, null, Permission.ALL))
        assertFalse(ForwardingPolicy.canForward(message.copy(poll = gson.fromJson(POLL, com.discord.api.message.MessagePoll::class.java)), source, Permission.ALL))
        assertFalse(ForwardingPolicy.canForward(message.copy(flags = MessageFlags.HAS_SNAPSHOT), source, Permission.ALL))
        assertTrue(ForwardingPolicy.canForward(message, source.copy(guildId = 0, type = Channel.DM), null))

        assertEquals(
            ForwardingPolicy.DestinationIssue.ATTACHMENTS,
            ForwardingPolicy.destinationIssue(
                message.copy(attachments = listOf(requireNotNull(message.attachments).single())),
                source,
                destination,
                Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES,
            ),
        )
        assertNull(
            ForwardingPolicy.destinationIssue(
                message,
                source,
                destination,
                Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES or Permission.ATTACH_FILES,
            ),
        )
    }

    @Test
    fun shareSendReportsSuccessAndFailureWithoutIncludingSourceContent() {
        val server = MockWebServer()
        try {
            val api = RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
                .build(RestAPIInterface::class.java)
            val source = Message(gson.fromJson(FORWARDABLE_FIXTURE, ApiMessage::class.java))
            val sourceChannel = channel(2, 3, Channel.GUILD_TEXT)

            server.enqueue(MockResponse().setBody("{\"id\":\"11\",\"channel_id\":\"4\"}"))
            val sent = ForwardMessageSender.send(4, source, sourceChannel, api).toBlocking().single()
            assertEquals(11L, sent.id)
            assertFalse(server.takeRequest().body.readUtf8().contains("sanitized"))

            server.enqueue(MockResponse().setResponseCode(403).setBody("{\"message\":\"forbidden\"}"))
            var failure: Throwable? = null
            ForwardMessageSender.send(4, source, sourceChannel, api).subscribe({}, { failure = it })
            assertTrue(failure is HttpException)
        } finally {
            server.shutdown()
        }
    }

    private fun channel(id: Long, guildId: Long, type: Int) = Channel(
        topic = null, type = type, guildId = guildId, name = "channel", lastMessageId = 0,
        id = id, ownerId = 0, recipientIds = null, recipients = null, position = 0,
        permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false,
        parentId = 0, memberListId = null, rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null, rtcRegion = null, flags = null, guildHashes = null,
        availableTags = null, appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )

    private companion object {
        const val POLL = """{"question":{"text":"question"},"answers":[],"expiry":null,"allow_multiselect":false,"layout_type":1}"""
        const val FORWARDABLE_FIXTURE = """
            {"id":"1","channel_id":"2","guild_id":"3","type":0,"content":"sanitized",
             "attachments":[{"url":"https://cdn.invalid/asset","size":1,"id":"4","proxy_url":"https://proxy.invalid/asset","filename":"asset.bin"}]}
        """
        const val FORWARD_FIXTURE = """
            {"id":"10","channel_id":"20","guild_id":"30","type":0,"flags":16384,
             "message_reference":{"type":1,"guild_id":"3","channel_id":"2","message_id":"1"},
             "message_snapshots":[{"message":{"id":"1","channel_id":"2","guild_id":"3","type":0,"flags":16384,
               "content":"sanitized","timestamp":"2026-01-01T00:00:00Z",
               "attachments":[{"url":"https://cdn.invalid/asset","size":1,"id":"4","proxy_url":"https://proxy.invalid/asset","filename":"asset.bin"}],
               "embeds":[{"title":"embed","type":"rich"}],
               "sticker_items":[{"id":"5","format_type":1,"name":"sticker"}],
               "components":[{"type":1,"components":[]}],
               "poll":{"question":{"text":"question"},"answers":[],"expiry":null,"allow_multiselect":false,"layout_type":1},
               "resolved":{"users":{"9":{"id":"9","username":"resolved"}},"members":{"9":{"roles":[],"nick":"Resolved Nick"}}},
               "message_reference":{"type":1,"guild_id":"3","channel_id":"2","message_id":"0"},
               "message_snapshots":[{"message":{"id":"0","channel_id":"2","content":"nested-sanitized"}}]
             }}]}
        """
    }
}
