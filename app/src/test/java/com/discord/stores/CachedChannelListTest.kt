package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelRecipientNick
import com.discord.api.channel.ForumTag
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.thread.ThreadMetadata
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Observable

class CachedChannelListTest {
    private val directory = Files.createTempDirectory("cached-channels").toFile()

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun roundTripsEveryFieldAndReadsTheOldPlainListFormat() {
        // Gson hands the store ArrayLists; Kryo cannot rebuild Kotlin's `listOf` (Arrays$ArrayList).
        val overwrites = arrayListOf(
            PermissionOverwrite(1_234_567_890_123_456_789L, PermissionOverwrite.Type.ROLE, 0L, 1L shl 10),
            PermissionOverwrite(42L, PermissionOverwrite.Type.MEMBER, Long.MAX_VALUE, -1L),
        )
        val channels = listOf(populated(1L, overwrites), bare(2L, null), bare(3L, arrayListOf()))

        Persister.init(directory, Observable.never())
        Persister("old", emptyList<Channel>()).set(ArrayList(channels), persist = true)
        Persister("new", emptyList<Channel>()).set(CachedChannelList().apply { addAll(channels) }, persist = true)

        Persister.init(directory, Observable.never())
        assertEquals(channels, Persister("old", emptyList<Channel>()).get())
        val restored = Persister("new", emptyList<Channel>()).get()
        assertEquals(channels, restored)
        assertTrue(restored is CachedChannelList)
    }

    @Test
    fun discardsACacheFromAnotherModelLayout() {
        Persister.init(directory, Observable.never())
        Persister("channels", emptyList<Channel>()).set(CachedChannelList().apply { add(bare(1L, null)) }, persist = true)
        val file = directory.resolve("channels")
        val bytes = file.readBytes()
        // Stands in for a model change: the layout hash follows the magic after Kryo's class header.
        val hashEnd = bytes.indexOfMagic() + 7
        bytes[hashEnd] = (bytes[hashEnd] + 1).toByte()
        file.writeBytes(bytes)

        Persister.init(directory, Observable.never())
        assertEquals(emptyList<Channel>(), Persister("channels", emptyList<Channel>()).get())
    }

    private fun ByteArray.indexOfMagic(): Int {
        val format = CachedChannelList.MAGIC
        val pattern = byteArrayOf((format ushr 24).toByte(), (format ushr 16).toByte(), (format ushr 8).toByte(), format.toByte())
        return (0..size - 4).first { start -> pattern.indices.all { this[start + it] == pattern[it] } }
    }

    private fun populated(id: Long, overwrites: List<PermissionOverwrite>) = bare(id, overwrites).copy(
        topic = "topic $id",
        type = Channel.GUILD_FORUM,
        lastMessageId = 900L,
        ownerId = 901L,
        recipientIds = arrayListOf(5L, 6L),
        userLimit = 10,
        bitrate = 64_000,
        icon = "icon",
        originChannelId = 902L,
        applicationId = 903L,
        nicks = arrayListOf(ChannelRecipientNick(5L, "nick")),
        nsfw = true,
        parentId = 904L,
        memberListId = "everyone",
        rateLimitPerUser = 30,
        defaultAutoArchiveDuration = 1440,
        rtcRegion = "rotterdam",
        flags = Channel.FLAG_REQUIRE_TAG,
        availableTags = arrayListOf(ForumTag(77L, "tag", 78L, "emoji", moderated = true)),
        appliedTags = arrayListOf(77L),
        defaultForumLayout = 1,
        defaultSortOrder = 0,
        defaultTagSetting = "match_some",
        threadMetadata = ThreadMetadata(archived = true, archiveTimestamp = "2026-01-01", autoArchiveDuration = 60, locked = true, invitable = false),
        messageCount = 12,
        memberCount = 3,
        memberIdsPreview = arrayListOf(5L),
        isMessageRequest = true,
        isMessageRequestTimestamp = "2026-01-02",
        isSpam = true,
        defaultThreadRateLimitPerUser = 5,
        lastPinTimestamp = UtcDateTime(1_700_000_000_000L),
        videoQualityMode = 2,
    )

    private fun bare(id: Long, overwrites: List<PermissionOverwrite>?) = Channel(
        topic = null,
        type = Channel.GUILD_TEXT,
        guildId = 7L,
        name = "channel-$id",
        lastMessageId = 0L,
        id = id,
        ownerId = 0L,
        recipientIds = null,
        recipients = null,
        position = id.toInt(),
        permissionOverwrites = overwrites,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0L,
        applicationId = 0L,
        nicks = null,
        nsfw = false,
        parentId = 0L,
        memberListId = null,
        rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null,
        rtcRegion = null,
        flags = null,
        guildHashes = null,
        availableTags = null,
        appliedTags = null,
        threadMetadata = null,
        messageCount = null,
        memberCount = null,
        memberIdsPreview = null,
        member = null,
    )
}
