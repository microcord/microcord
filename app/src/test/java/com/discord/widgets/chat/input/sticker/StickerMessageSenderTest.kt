package com.discord.widgets.chat.input.sticker

import com.discord.api.channel.Channel
import com.discord.api.message.MessageReference
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerType
import com.discord.api.user.User
import com.discord.models.message.Message
import com.discord.stores.StorePendingReplies
import com.discord.utilities.messagesend.MessageSendLocation
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StickerMessageSenderTest {
    @Test
    fun stickerSendCarriesThePendingReply() {
        val channel = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
            .fromJson("""{"id":"2","guild_id":"3","type":0}""", Channel::class.java)
        val sticker = Sticker(
            id = 4,
            packId = null,
            guildId = null,
            name = "wave",
            description = "wave",
            formatType = StickerFormatType.PNG,
            tags = "wave",
            type = StickerType.STANDARD,
        )
        val reply = StorePendingReplies.PendingReply(
            messageReference = MessageReference(channel.guildId, channel.id, 5),
            originalMessage = Message(id = 5, channelId = channel.id),
            shouldMention = false,
        )

        val request = stickerSendRequest(channel, sticker, User(6, "sender"), reply)

        assertSame(reply, request.reply)
        assertEquals(listOf(sticker), request.stickers)
        assertEquals(MessageSendLocation.CHAT_INPUT, request.location)
    }
}
