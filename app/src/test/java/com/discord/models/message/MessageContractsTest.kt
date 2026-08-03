package com.discord.models.message

import com.discord.api.message.LocalAttachment
import com.discord.api.message.Message as ApiMessage
import com.discord.api.message.allowedmentions.MessageAllowedMentions
import com.discord.api.message.allowedmentions.MessageAllowedMentionsTypes
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelMessageDelete
import com.discord.utilities.captcha.CaptchaSolution
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageContractsTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    @Test
    fun preservesCompleteMessageContracts() {
        val api = gson.fromJson(FULL_MESSAGE, ApiMessage::class.java)

        assertEquals(36, ApiMessage::class.java.declaredFields.count { !Modifier.isStatic(it.modifiers) })
        assertEquals(42, Message::class.java.declaredFields.count { !Modifier.isStatic(it.modifiers) })
        assertEquals(1L, api.id)
        assertEquals(2L, api.channelId)
        assertEquals(3L, api.guildId)
        assertEquals("message", api.content)
        assertEquals("nonce", api.nonce)
        assertEquals(7L, api.messageReference!!.messageId)
        assertEquals(8L, api.call!!.participants.single())
        assertEquals(9L, api.roleSubscriptionData!!.roleSubscriptionListingId)
        assertEquals(10L, api.interaction!!.id)
        assertTrue(api.hit == true)
        assertTrue(api.member != null)

        val local = Message(api).copy(
            allowedMentions = MessageAllowedMentions(
                parse = listOf(MessageAllowedMentionsTypes.USERS, MessageAllowedMentionsTypes.EVERYONE),
                users = listOf(4),
                repliedUser = true,
            ),
            numRetries = 2,
            lastManualAttemptTimestamp = 11,
            initialAttemptTimestamp = 12,
            localAttachments = listOf(LocalAttachment(13, "content://private/file", "private.txt")),
            captchaSolution = CaptchaSolution("secret-key", "secret-token"),
        )
        assertEquals(api.copy(member = null, hit = null), local.synthesizeApiMessage())
        assertEquals(2, local.component39())
        assertEquals("secret-key", local.component43()!!.key)
        assertEquals(local, local.copy())
        assertTrue(local.hasAttachments())
        assertTrue(local.hasEmbeds())
        assertTrue(local.hasStickers())
        assertTrue(local.hasThread())
        assertTrue(local.isEphemeralMessage())
        assertTrue(local.isLoading())
        assertTrue(local.isUserMessage())
        assertFalse(local.isSystemMessage())

        val update = gson.fromJson(
            """{"id":"1","channel_id":"0","content":"edited","attachments":[],"unknown":{"safe":true}}""",
            ApiMessage::class.java,
        )
        val merged = local.merge(update)
        assertEquals("edited", merged.content)
        assertEquals(2L, merged.channelId)
        assertEquals(emptyList<Any>(), merged.attachments)
        assertEquals(local.author, merged.author)
        assertEquals(local.guildId, merged.guildId)
        assertEquals(local.localAttachments, merged.localAttachments)
        assertEquals(local.captchaSolution, merged.captchaSolution)

        val mentionsJson = gson.toJson(local.allowedMentions)
        assertTrue(mentionsJson.contains("\"users\""))
        assertTrue(mentionsJson.contains("\"everyone\""))
        assertEquals("roles", MessageAllowedMentionsTypes.ROLES.serialize())

        assertEquals(listOf(20L), gson.fromJson("""{"channel_id":"2","id":"20","ignored":1}""", ModelMessageDelete::class.java).messageIds)
        assertEquals(listOf(20L, 21L), gson.fromJson("""{"channel_id":"2","ids":["20","21"]}""", ModelMessageDelete::class.java).messageIds)
        assertNull(gson.fromJson("""{"channel_id":"2"}""", ModelMessageDelete::class.java).messageIds)

        val rendered = local.toString()
        assertFalse(rendered.contains("message"))
        assertFalse(rendered.contains("content://private/file"))
        assertFalse(rendered.contains("private.txt"))
        assertFalse(rendered.contains("secret-key"))
        assertFalse(rendered.contains("secret-token"))
        assertFalse(api.toString().contains("message"))
        assertFalse(local.captchaSolution.toString().contains("secret"))
    }

    private companion object {
        val FULL_MESSAGE = """
            {
              "id":"1","channel_id":"2","guild_id":"3",
              "author":{"id":"4","username":"author","discriminator":"0001"},
              "content":"message","timestamp":"2022-01-01T00:00:00Z","edited_timestamp":"2022-01-01T00:01:00Z",
              "tts":false,"mention_everyone":true,"mentions":[],"mention_roles":["5"],
              "attachments":[{"url":"https://cdn.example/file.png","size":6,"id":"6","proxy_url":"https://proxy.example/file.png","filename":"file.png","width":10,"height":20}],
              "embeds":[{"title":"embed","type":"rich","description":null,"url":null,"timestamp":null,"color":null,"footer":null,"image":null,"thumbnail":null,"video":null,"provider":null,"author":null,"fields":null}],
              "reactions":[{"count":1,"emoji":{"id":null,"name":"ok","animated":false},"me":true}],
              "nonce":"nonce","pinned":true,"webhook_id":"6","type":0,
              "activity":{"type":3,"party_id":"spotify:party"},
              "application":{"id":"5","name":"app","description":null,"splash":null,"cover_image":null,"icon":null,"third_party_skus":null,"deeplink_uri":null,"type":null,"guild_id":null,"team":null},
              "application_id":"5","message_reference":{"guild_id":"3","channel_id":"2","message_id":"7"},
              "flags":224,"stickers":[{"id":"7","pack_id":null,"guild_id":null,"name":"sticker","description":"","format_type":1,"tags":"tag","type":1,"available":true}],
              "sticker_items":[{"id":"7","format_type":1,"name":"sticker"}],
              "referenced_message":null,
              "interaction":{"id":"10","type":2,"name":"command","user":null,"member":{"roles":[],"joined_at":"now","mute":false,"deaf":false}},
              "thread":null,"components":[],"call":{"participants":["8"],"ended_timestamp":"2022-01-01T00:02:00Z"},
              "member":{"guild_id":"3","user":{"id":"4","username":"author"},"roles":[],"nick":null,"premium_since":null,"joined_at":null,"pending":false,"presence":null,"user_id":"4","avatar":null},
              "hit":true,"role_subscription_data":{"role_subscription_listing_id":"9","tier_name":"Tier","total_months_subscribed":2,"is_renewal":true},
              "unknown_field":"ignored"
            }
        """.trimIndent()
    }
}
