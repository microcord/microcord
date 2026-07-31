package com.discord.api.guild

import com.discord.api.channel.Channel
import com.discord.api.thread.ThreadMember
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyGuildChannelDtosTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    @Test
    fun parsesCompleteReadyGuildAndChannelSurface() {
        val guild = gson.fromJson(READY_GUILD, Guild::class.java)
        val channel = guild.channels!!.single()

        assertEquals(46, Guild::class.java.declaredFields.count { !Modifier.isStatic(it.modifiers) })
        assertEquals(32, Channel::class.java.declaredFields.count { !Modifier.isStatic(it.modifiers) })
        assertEquals(GuildVerificationLevel.HIGH, guild.verificationLevel)
        assertEquals(GuildExplicitContentFilter.ALL, guild.explicitContentFilter)
        assertEquals(GuildHubType.COLLEGE, guild.hubType)
        assertEquals(GuildMaxVideoChannelUsers.Limited(25), guild.maxVideoChannelUsers)
        assertEquals("metadata", guild.guildHashes!!.metadata!!.hash)
        assertEquals(43L, guild.channelUpdates!!.single().id)
        assertEquals(44L, guild.threads!!.single().id)
        assertTrue(channel.threadMetadata!!.archived)
        assertEquals(55L, channel.availableTags!!.single().emojiId)
        // Gson bypasses Kotlin body initializers, matching the reference's R8-stripped DTO.
        assertNull(channel.availableTags!!.single().customEmojiId)
        assertEquals(9L, guild.embeddedActivities!!.single().embeddedActivity.applicationId)

        assertNull(channel.guildHashes)
        assertNull(channel.memberCount)
        assertNull(channel.memberIdsPreview)
        assertNull(channel.member)
        val serialized = gson.toJson(channel)
        assertFalse(serialized.contains("guild_hashes"))
        assertFalse(serialized.contains("member_ids_preview"))
        assertFalse(serialized.contains("member_count"))
        assertFalse(serialized.contains("\"member\""))
    }

    @Test
    fun preservesReferenceAdapterFallbacksAndIdentityThreadMembers() {
        assertEquals(GuildVerificationLevel.NONE, gson.fromJson("99", GuildVerificationLevel::class.java))
        assertEquals(GuildExplicitContentFilter.NONE, gson.fromJson("99", GuildExplicitContentFilter::class.java))
        assertNull(gson.fromJson("99", GuildHubType::class.java))
        assertEquals(GuildMaxVideoChannelUsers.Unlimited, gson.fromJson("-1", GuildMaxVideoChannelUsers::class.java))
        assertEquals(UtcDateTime(0), gson.fromJson("\"invalid\"", UtcDateTime::class.java))
        assertEquals(1_640_995_200_000L, channelMember().joinTimestamp.dateTimeMillis)
        assertNotEquals(channelMember(), channelMember())
    }

    private fun channelMember() = gson.fromJson(
        "{\"id\":1,\"user_id\":2,\"flags\":3,\"join_timestamp\":\"2022-01-01T00:00:00\",\"muted\":false,\"mute_config\":null}",
        ThreadMember::class.java,
    )

    private companion object {
        val READY_GUILD = """
            {
              "roles":[],"emojis":[],"stickers":[],"name":"Guild","description":"ready",
              "default_message_notifications":1,"id":1,"region":"us-west","owner_id":2,"icon":"icon",
              "verification_level":3,"explicit_content_filter":2,"presences":[],
              "channels":[{"topic":"topic","type":15,"guild_id":1,"name":"forum","last_message_id":4,"id":42,"owner_id":2,"recipient_ids":[2],"recipients":[],"position":1,"permission_overwrites":[],"user_limit":10,"bitrate":64000,"icon":"channel-icon","origin_channel_id":3,"application_id":9,"nicks":[{"id":2,"nick":"nick"}],"nsfw":true,"parent_id":8,"member_list_id":"list","rate_limit_per_user":5,"default_auto_archive_duration":1440,"rtc_region":"us-west","flags":2,"guild_hashes":{"metadata":{"hash":"ignored","omitted":false},"channels":null,"roles":null,"version":1},"available_tags":[{"id":7,"name":"tag","emoji_id":55,"emoji_name":null}],"applied_tags":[7],"thread_metadata":{"archived":true,"archive_timestamp":"2022-01-01T00:00:00Z","auto_archive_duration":1440,"locked":false},"message_count":6,"member_count":7,"member_ids_preview":[2],"member":{"id":1,"user_id":2,"flags":3,"join_timestamp":"2022-01-01T00:00:00","muted":false,"mute_config":null}}],
              "members":[],"voice_states":[],"unavailable":false,"mfa_level":1,"afk_timeout":300,
              "afk_channel_id":3,"system_channel_id":4,"features":["HUB","COMMUNITY"],"member_count":1,
              "banner":"banner","splash":"splash","premium_tier":2,"premium_subscription_count":3,
              "system_channel_flags":4,"joined_at":"2022-01-01T00:00:00Z","rules_channel_id":5,
              "public_updates_channel_id":6,"preferred_locale":"en-US","welcome_screen":{"description":"welcome","welcome_channels":[{"channel_id":42,"description":"hello","emoji_id":55,"emoji_name":null}]},
              "max_video_channel_users":25,"vanity_url_code":"code","approximate_member_count":2,
              "approximate_presence_count":1,"permissions":8,"guild_hashes":{"metadata":{"hash":"metadata","omitted":false},"channels":{"hash":"channels","omitted":false},"roles":{"hash":"roles","omitted":true},"version":1},
              "channel_updates":[{"type":0,"guild_id":1,"id":43,"last_message_id":0,"owner_id":0,"position":0,"user_limit":0,"bitrate":0,"origin_channel_id":0,"application_id":0,"nsfw":false,"parent_id":0,"rate_limit_per_user":0}],
              "threads":[{"type":11,"guild_id":1,"id":44,"last_message_id":0,"owner_id":2,"position":0,"user_limit":0,"bitrate":0,"origin_channel_id":42,"application_id":0,"nsfw":false,"parent_id":42,"rate_limit_per_user":0}],
              "nsfw":false,"stage_instances":[],"guild_scheduled_events":[],
              "embedded_activities":[{"channel_id":42,"embedded_activity":{"application_id":9,"assets":null,"created_at":1,"details":"details","name":"activity","secrets":null,"state":"state","timestamps":null,"type":0},"users":[2]}],"hub_type":2
            }
        """.trimIndent()
    }
}
