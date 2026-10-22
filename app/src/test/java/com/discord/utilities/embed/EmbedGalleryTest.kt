package com.discord.utilities.embed

import com.discord.api.channel.Channel
import com.discord.api.message.Message as ApiMessage
import com.discord.api.message.MessageFlags
import com.discord.api.message.MessageReference
import com.discord.api.message.MessageSnapshot
import com.discord.api.message.embed.EmbedImage
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedVideo
import com.discord.api.message.embed.MessageEmbed
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.message.Message
import com.discord.utilities.embed.EmbedResourceUtils.getGalleryImages
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.model.ChatListRows
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Test
import com.discord.widgets.chat.list.entries.MediaMosaicEntry
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.api.message.attachment.MessageAttachment

class EmbedGalleryTest {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters().create()
    private val channel = gson.fromJson("""{"id":"20","guild_id":"30","type":0}""", Channel::class.java)

    private fun wireEmbeds(): List<MessageEmbed> {
        val json = (1..4).joinToString(prefix = "[", postfix = "]") { index ->
            val caption = if (index == 1) """"description":"Sanitized post","author":{"name":"Fixture author"},""" else ""
            """{"type":"rich","url":"https://example.com/post",$caption"image":{"url":"https://example.com/$index.jpg?name=orig","proxy_url":"https://proxy.example.com/$index.jpg","width":${1000 + index},"height":${1500 + index}}}"""
        }
        return gson.fromJson(json, Array<MessageEmbed>::class.java).toList()
    }

    @Test fun `four wire embeds share one card while preserving all images and primary metadata`() {
        val embeds = wireEmbeds()
        val message = Message(id = 10, channelId = 20, type = 0, embeds = embeds)
        val entry = ChatListEntry.createEmbedEntries(message, null, false, false, false, true,
            false, channel, null, null, emptyMap()).single() as EmbedEntry
        assertSame(embeds[0], entry.embed)
        assertEquals("Sanitized post", entry.embed.description)
        assertEquals("Fixture author", entry.embed.author?.name)
        assertEquals(0, entry.embedIndex)
        assertEquals(embeds.map { it.image }, entry.embed.getGalleryImages(entry.message.embeds.orEmpty()))
        assertEquals(4, message.embeds?.size)
        assertEquals(embeds.map { it.image }, embeds[0].getGalleryImages(embeds + embeds[0] + embeds[1]))
    }

    @Test fun `forwarded snapshots retain every image for their separate renderer`() {
        val embeds = wireEmbeds()
        val parent = Message(id = 10, channelId = 20, type = 0, flags = MessageFlags.HAS_SNAPSHOT,
            messageReference = MessageReference(11, 21, 30, type = 1),
            messageSnapshots = listOf(MessageSnapshot(ApiMessage(id = 11, channelId = 21, type = 0, embeds = embeds))))
        val entries = ChatListRows.forwardedRows(ChatListRows.State(channel), parent).single().rows.filterIsInstance<EmbedEntry>()
        assertEquals(4, entries.size)
        assertEquals(embeds.map { it.image }, entries.map { it.embed.image })
    }

    @Test fun `only valid image companions join a rich card and null URL cards remain independent`() {
        val embeds = wireEmbeds()
        val primary = embeds[0]
        val companion = embeds[1]
        val unrelated = listOf(
            companion.copy(url = "https://example.com/other"), companion.copy(title = "Another card"),
            companion.copy(description = "Another description"), companion.copy(type = EmbedType.IMAGE),
            companion.copy(video = EmbedVideo("https://example.com/video", null, 20, 20)),
            companion.copy(image = EmbedImage(null, null, 20, 20)),
        )
        assertEquals(listOf(primary.image), primary.getGalleryImages(unrelated))
        val noUrls = embeds.map { it.copy(url = null) }
        assertEquals(4, ChatListEntry.createEmbedEntries(Message(id = 10, channelId = 20, type = 0, embeds = noUrls),
            null, false, false, false, true, false, channel, null, null, emptyMap()).size)
        assertEquals(listOf(noUrls[0].image), noUrls[0].getGalleryImages(noUrls))
        assertTrue(primary.copy(video = EmbedVideo("video", null, 20, 20)).getGalleryImages(embeds).isEmpty())
        val missingPrimary = primary.copy(image = null)
        assertEquals(listOf(companion.image), missingPrimary.getGalleryImages(listOf(missingPrimary, companion)))
        val proxyOnly = companion.copy(image = EmbedImage(null, "https://proxy.example.com/only", 20, 20))
        assertEquals(listOf(primary.image, proxyOnly.image), primary.getGalleryImages(listOf(proxyOnly)))
    }

    @Test fun `images and videos group into one mosaic ahead of other files`() {
        fun attachment(id: Long, name: String, size: Int?) =
            MessageAttachment("https://cdn/$name", 1, id, "https://proxy/$name", name, size, size)
        val image = attachment(1, "a.png", 100)
        val file = attachment(2, "notes.txt", null)
        val video = attachment(3, "b.mp4", 100)
        val message = Message(id = 10, channelId = 20, type = 0, attachments = listOf(image, file, video))
        fun entries(group: Boolean) = ChatListEntry.createEmbedEntries(message, null, false, false, false, true,
            false, channel, null, null, emptyMap(), groupMedia = group)

        val grouped = entries(group = true)
        assertEquals(listOf(image, video), (grouped[0] as MediaMosaicEntry).items.map { it.attachment })
        assertEquals(listOf(0, 2), (grouped[0] as MediaMosaicEntry).items.map { it.embedIndex })
        assertEquals(file, (grouped[1] as AttachmentEntry).attachment)
        assertEquals(3, entries(group = false).filterIsInstance<AttachmentEntry>().size)
    }
}
