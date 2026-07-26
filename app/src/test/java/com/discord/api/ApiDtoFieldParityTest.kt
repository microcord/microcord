package com.discord.api

import com.discord.api.channel.Channel
import com.discord.api.channel.ForumTag
import com.discord.api.commands.ApplicationCommand
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.CommandTypeAdapter
import com.discord.api.forum.ForumUnreads
import com.discord.api.guildhash.GuildHashes
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedTypeTypeAdapter
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.ClientStatuses
import com.discord.api.thread.ThreadMembersUpdate
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.api.utcdatetime.UtcDateTimeTypeAdapter
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field-level parity for the DTOs the chat, thread and command surfaces parse on every payload.
 * Fixtures are hand-written and carry no real ids, tokens or user data.
 */
class ApiDtoFieldParityTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerTypeAdapter(EmbedType::class.java, EmbedTypeTypeAdapter())
        .registerTypeAdapter(ApplicationCommandType::class.java, CommandTypeAdapter())
        .registerTypeAdapter(UtcDateTime::class.java, UtcDateTimeTypeAdapter())
        .create()

    @Test
    fun richEmbedKeepsItsTypeInsteadOfDeserialisingToNull() {
        val embed = gson.fromJson(
            """
            {
              "title": "Title",
              "type": "rich",
              "description": "Description",
              "url": "https://example.invalid/a",
              "color": 5793266,
              "footer": {"text": "Footer", "icon_url": "https://example.invalid/f.png"},
              "image": {"url": "https://example.invalid/i.png", "proxy_url": "https://example.invalid/p.png",
                        "width": 100, "height": 50},
              "provider": {"name": "Example", "url": "https://example.invalid"},
              "fields": [{"name": "Field", "value": "Value", "inline": true}]
            }
            """.trimIndent(),
            MessageEmbed::class.java,
        )

        assertEquals(EmbedType.RICH, embed.type)
        assertEquals("Title", embed.title)
        assertEquals(5793266, embed.color)
        assertEquals("Footer", embed.footer?.text)
        assertEquals(100, embed.image?.width)
        assertEquals("https://example.invalid/p.png", embed.image?.proxyUrl)
        assertEquals("Example", embed.provider?.name)
        assertEquals(1, embed.fields?.size)
        assertEquals(true, embed.fields?.first()?.inline)
        assertNull(embed.video)
    }

    @Test
    fun forumChannelCarriesTagsAndAcceptsAGuildHashRewrite() {
        val channel = gson.fromJson(
            """
            {
              "id": "1", "guild_id": "2", "type": 15, "name": "forum", "position": 3,
              "available_tags": [{"id": "10", "name": "Bug", "emoji_id": "20", "emoji_name": null}],
              "applied_tags": ["10"],
              "message_count": 4
            }
            """.trimIndent(),
            Channel::class.java,
        )

        assertEquals(Channel.GUILD_FORUM, channel.type)
        assertEquals(listOf(10L), channel.appliedTags)
        assertEquals(ForumTag(10L, "Bug", 20L, null), channel.availableTags?.first())

        // The store rewrites this field in place after reconciling GUILD_CREATE hashes.
        channel.guildHashes = GuildHashes(null, null, null, 1)
        assertNotNull(channel.guildHashes)
    }

    @Test
    fun forumTagCustomEmojiIdIsStoredNotRecomputed() {
        // Stored, so it is fixed at construction and excluded from data-class equality — exactly
        // like the reference body-level property.
        assertEquals("20", ForumTag(10L, "Bug", 20L, null).customEmojiId)
        assertNull(ForumTag(10L, "Unicode", null, "🐛").customEmojiId)
        assertEquals(ForumTag(1L, "a", 2L, null), ForumTag(1L, "a", 2L, null))
    }

    @Test
    fun threadMembersUpdateKeepsEveryField() {
        val update = gson.fromJson(
            """
            {
              "id": "1", "guild_id": "2", "member_count": 5,
              "removed_member_ids": ["3"],
              "member_ids_preview": ["4", "5"],
              "added_members": [
                {"id": "1", "user_id": "6", "flags": 1, "join_timestamp": "2022-01-01T00:00:00+00:00",
                 "muted": false, "mute_config": null}
              ]
            }
            """.trimIndent(),
            ThreadMembersUpdate::class.java,
        )

        assertEquals(1L, update.id)
        assertEquals(2L, update.guildId)
        assertEquals(5, update.memberCount)
        assertEquals(listOf(3L), update.removedMemberIds)
        assertEquals(listOf(4L, 5L), update.memberIdsPreview)
        assertEquals(1, update.addedMembers?.size)
        assertEquals(6L, update.addedMembers?.first()?.userId)
        assertEquals(1, update.addedMembers?.first()?.flags)
    }

    @Test
    fun forumUnreadRowsKeepMissingAndCountApart() {
        val unreads = gson.fromJson(
            """
            {
              "channel_id": "1", "permission_denied": false,
              "threads": [
                {"thread_id": "2", "missing": false, "count": 7},
                {"thread_id": "3", "missing": true}
              ]
            }
            """.trimIndent(),
            ForumUnreads::class.java,
        )

        assertEquals(1L, unreads.channelId)
        assertEquals(7, unreads.threads[0].count)
        assertEquals(false, unreads.threads[0].missing)
        assertNull(unreads.threads[1].count)
        assertTrue(unreads.threads[1].missing)
    }

    @Test
    fun applicationCommandParsesItsOptionTree() {
        val command = gson.fromJson(
            """
            {
              "id": "1", "application_id": "2", "name": "ban", "description": "Ban a member",
              "version": "3", "guild_id": "4", "default_permissions": false,
              "options": [
                {"type": 6, "name": "user", "description": "Who", "required": true, "default": false,
                 "choices": [], "autocomplete": false},
                {"type": 1, "name": "sub", "description": "Sub", "required": false, "default": false,
                 "choices": [], "autocomplete": false,
                 "options": [{"type": 3, "name": "reason", "description": "Why", "required": false,
                              "default": false, "choices": [{"name": "Spam", "value": "spam"}],
                              "autocomplete": true}]}
              ]
            }
            """.trimIndent(),
            ApplicationCommand::class.java,
        )

        assertEquals(1L, command.id)
        assertEquals(2L, command.applicationId)
        assertEquals("4", command.guildId)
        assertEquals(false, command.defaultPermissions)
        assertEquals(ApplicationCommandType.USER, command.options?.get(0)?.type)
        assertEquals(ApplicationCommandType.SUBCOMMAND, command.options?.get(1)?.type)
        val nested = command.options?.get(1)?.options?.first()
        assertEquals(ApplicationCommandType.STRING, nested?.type)
        assertEquals(true, nested?.autocomplete)
        assertEquals("spam", nested?.choices?.first()?.value)
    }

    @Test
    fun clientStatusesMapEachPlatformToItsOwnAccessor() {
        val statuses = gson.fromJson(
            """{"desktop": "online", "mobile": "idle", "web": "dnd"}""",
            ClientStatuses::class.java,
        )

        assertEquals(ClientStatus.ONLINE, statuses.desktopStatus())
        assertEquals(ClientStatus.IDLE, statuses.mobileStatus())
        assertEquals(ClientStatus.DND, statuses.webStatus())
        assertEquals(
            ClientStatus.OFFLINE,
            gson.fromJson("""{"desktop": "online"}""", ClientStatuses::class.java).mobileStatus(),
        )
    }
}
