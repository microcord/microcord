package com.discord.stores

import android.content.SharedPreferences
import com.discord.api.channel.Channel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.application.Unread
import com.discord.utilities.time.Clock
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreReadStatesUnreadTest {
    private val store = StoreReadStates(Clock { NOW })

    @Test
    fun `unread projection excludes private channels`() {
        val dm = channel(1, type = Channel.DM)
        val result = compute(
            channels = mapOf(dm.id to dm),
            acks = mapOf(dm.id to StoreMessageAck.Ack(10, false, false)),
            recent = mapOf(dm.id to 20),
            permissions = mapOf(dm.id to VIEW_CHANNEL),
        )

        assertEquals(emptySet<Long>(), result.first)
        assertEquals(emptySet<Long>(), result.second)
    }

    @Test
    fun `unread projection applies joined thread forum and voice gates`() {
        val text = channel(1)
        val thread = channel(2, type = Channel.PUBLIC_THREAD, parentId = text.id)
        val forum = channel(3, type = Channel.GUILD_FORUM)
        val post = channel(4, type = Channel.PUBLIC_THREAD, parentId = forum.id)
        val voice = channel(5, type = Channel.GUILD_VOICE)
        val channels = listOf(text, thread, forum, post, voice).associateBy(Channel::id)
        val acks = mapOf(
            thread.id to StoreMessageAck.Ack(10, false, false),
            post.id to StoreMessageAck.Ack(10, false, false),
            voice.id to StoreMessageAck.Ack(10, false, false),
        )
        val recent = mapOf(thread.id to 20L, post.id to 20L, voice.id to snowflake(NOW - 1))

        assertEquals(emptySet<Long>(), compute(channels, acks, recent).first)

        val joined = mapOf(
            thread.id to StoreThreadsActiveJoined.ActiveJoinedThread(thread, UtcDateTime(0), false),
        )
        val activeForum = mapOf(post.id to post)
        val result = compute(channels, acks, recent, joined, activeForum, selectedVoiceChannelId = voice.id)

        assertEquals(setOf(thread.id, post.id, voice.id), result.first)
        assertEquals(setOf(GUILD_ID), result.second)
    }

    @Test
    fun `marker collapses an ack at the newest message`() {
        assertNull(Unread.Marker(1, 20, 20).messageId)
        assertEquals(10L, Unread.Marker(1, 10, 20).messageId)
    }

    @Test
    fun `marker keeps ack at switch unless the live ack is locked`() {
        val atSwitch = StoreMessageAck.Ack(10, false, false)
        val live = StoreMessageAck.Ack(20, true, false)
        val locked = StoreMessageAck.Ack(20, false, true)

        assertEquals(10L, StoreReadStates.markerFor(1, atSwitch, live, 30).messageId)
        assertEquals(20L, StoreReadStates.markerFor(1, atSwitch, locked, 30).messageId)
    }

    private fun compute(
        channels: Map<Long, Channel>,
        acks: Map<Long, StoreMessageAck.Ack>,
        recent: Map<Long, Long>,
        joined: Map<Long, StoreThreadsActiveJoined.ActiveJoinedThread> = emptyMap(),
        activeForum: Map<Long, Channel> = emptyMap(),
        selectedVoiceChannelId: Long = 0,
        permissions: Map<Long, Long> = channels.mapValues { (_, channel) ->
            if (channel.type == Channel.GUILD_VOICE) VIEW_CHANNEL or CONNECT else VIEW_CHANNEL
        },
    ) = store.computeUnreadIds(
        permissions,
        channels,
        mapOf(GUILD_ID to DISCORD_EPOCH),
        emptyMap(),
        acks,
        recent,
        selectedVoiceChannelId,
        activeForum,
        joined,
    )

    private fun channel(
        id: Long,
        type: Int = Channel.GUILD_TEXT,
        parentId: Long = 0,
    ) = Channel(
        topic = null, type = type, guildId = if (type == Channel.DM) 0 else GUILD_ID,
        name = "channel", lastMessageId = 0, id = id, ownerId = 0, recipientIds = null,
        recipients = null, position = 0, permissionOverwrites = null, userLimit = 0,
        bitrate = 0, icon = null, originChannelId = 0, applicationId = 0, nicks = null,
        nsfw = false, parentId = parentId, memberListId = null, rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null, rtcRegion = null, flags = null, guildHashes = null,
        availableTags = null, appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )

    companion object {
        private const val GUILD_ID = 100L
        private const val DISCORD_EPOCH = 1_420_070_400_000L
        private const val NOW = 1_700_000_000_000L
        private const val VIEW_CHANNEL = 1_024L
        private const val CONNECT = 1_048_576L
        private val directory = Files.createTempDirectory("read-state-projection")

        @JvmStatic
        @BeforeClass
        fun initialize() {
            val preferences = memoryPreferences()
            StoreStream.initializeForTesting(directory.toFile(), preferences, preferences, TestScheduler(), Clock { NOW })
        }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            directory.toFile().deleteRecursively()
        }

        private fun snowflake(timestamp: Long) = (timestamp - DISCORD_EPOCH) shl 22

        private fun memoryPreferences(): SharedPreferences {
            val values = linkedMapOf<String, Any?>()
            lateinit var editor: Any
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet" -> editor.also { values[args[0] as String] = args[1] }
                    "remove" -> editor.also { values.remove(args[0]) }
                    "clear" -> editor.also { values.clear() }
                    "commit" -> true
                    "apply" -> null
                    else -> editor
                }
            }
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getAll" -> values.toMap()
                    "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" -> values[args[0]] ?: args[1]
                    "contains" -> values.containsKey(args[0])
                    "edit" -> editor
                    else -> null
                }
            } as SharedPreferences
        }
    }
}
