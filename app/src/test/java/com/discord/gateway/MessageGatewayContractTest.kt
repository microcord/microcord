package com.discord.gateway

import com.discord.api.channel.ChannelPinsUpdate
import com.discord.api.message.Message
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.api.message.reaction.MessageReactionRemoveAll
import com.discord.api.message.reaction.MessageReactionRemoveEmoji
import com.discord.api.user.TypingUser
import com.discord.gateway.io.IncomingParser
import com.discord.gateway.io.MessageDeleteEvent
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageGatewayContractTest {
    private val parser = IncomingParser(
        GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create(),
    )

    @Test
    fun parsesMessageCreateAndPartialUpdate() {
        val create = parse(
            "MESSAGE_CREATE",
            """{"id":101,"channel_id":202,"guild_id":303,"author":{"id":404,"username":"fixture-user","discriminator":"0001"},"content":"sanitized fixture","timestamp":"2021-08-01T12:00:00.000000+00:00","edited_timestamp":null,"tts":false,"mention_everyone":false,"mentions":[],"mention_roles":[],"attachments":[],"embeds":[],"reactions":[],"pinned":false,"type":0}""",
        ) as Message
        assertEquals(101L, create.id)
        assertEquals(202L, create.channelId)
        assertEquals("sanitized fixture", create.content)

        val update = parse(
            "MESSAGE_UPDATE",
            """{"id":101,"channel_id":202,"content":null,"edited_timestamp":"2021-08-01T12:01:00.000000+00:00"}""",
        ) as Message
        assertEquals(101L, update.id)
        assertNull(update.content)
        assertNull(update.author)
        assertTrue(update.editedTimestamp != null)
    }

    @Test
    fun parsesSingleAndBulkDeletesWithCompatibleMessageIds() {
        val single = parse("MESSAGE_DELETE", """{"id":101,"channel_id":202,"guild_id":303}""") as MessageDeleteEvent
        val bulk = parse("MESSAGE_DELETE_BULK", """{"ids":[101,102],"channel_id":202,"guild_id":303}""") as MessageDeleteEvent

        assertEquals(listOf(101L), single.messageIds)
        assertEquals(listOf(101L, 102L), bulk.messageIds)
        assertEquals(303L, single.guildId)
    }

    @Test
    fun parsesEveryReactionEventShape() {
        val add = parse(
            "MESSAGE_REACTION_ADD",
            """{"user_id":404,"channel_id":202,"message_id":101,"guild_id":303,"member":{"guild_id":303,"user":{"id":404,"username":"fixture-user"},"roles":[],"pending":false},"emoji":{"id":505,"name":"wave","animated":false}}""",
        ) as MessageReactionUpdate
        val remove = parse(
            "MESSAGE_REACTION_REMOVE",
            """{"user_id":404,"channel_id":202,"message_id":101,"guild_id":303,"emoji":{"id":null,"name":"wave","animated":false}}""",
        ) as MessageReactionUpdate
        val removeAll = parse(
            "MESSAGE_REACTION_REMOVE_ALL",
            """{"channel_id":202,"message_id":101,"guild_id":303}""",
        ) as MessageReactionRemoveAll
        val removeEmoji = parse(
            "MESSAGE_REACTION_REMOVE_EMOJI",
            """{"channel_id":202,"message_id":101,"guild_id":303,"emoji":{"id":505,"name":"wave","animated":false}}""",
        ) as MessageReactionRemoveEmoji

        assertEquals(404L, add.userId)
        assertEquals("wave", remove.emoji?.name)
        assertEquals(101L, removeAll.messageId)
        assertEquals("505", removeEmoji.emoji?.id)
    }

    @Test
    fun parsesTypingAndChannelPinsUpdates() {
        val typing = parse(
            "TYPING_START",
            """{"channel_id":202,"guild_id":303,"user_id":404,"timestamp":1627819200,"member":{"guild_id":303,"user":{"id":404,"username":"fixture-user"},"roles":[],"pending":false}}""",
        ) as TypingUser
        val pins = parse(
            "CHANNEL_PINS_UPDATE",
            """{"guild_id":303,"channel_id":202,"last_pin_timestamp":"2021-08-01T12:00:00.000000+00:00"}""",
        ) as ChannelPinsUpdate

        assertEquals(404L, typing.userId)
        assertEquals(404L, typing.member?.user?.id)
        assertEquals(202L, pins.channelId)
        assertTrue(pins.lastPinTimestamp != null)
    }

    private fun parse(type: String, data: String): Any =
        requireNotNull(parser.parse("""{"op":0,"s":7,"t":"$type","d":$data}""").data)
}
