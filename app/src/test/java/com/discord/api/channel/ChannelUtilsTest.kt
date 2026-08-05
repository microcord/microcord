package com.discord.api.channel

import com.discord.api.permission.PermissionOverwrite
import com.discord.api.thread.ThreadMetadata
import com.discord.api.user.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelUtilsTest {

    @Test fun `private covers DM and group DM only`() {
        assertTrue(channel(type = Channel.DM).isPrivate())
        assertTrue(channel(type = Channel.GROUP_DM).isPrivate())
        assertFalse(channel(type = Channel.GUILD_TEXT).isPrivate())
        assertFalse(channel(type = Channel.PRIVATE_THREAD).isPrivate())
    }

    @Test fun `thread covers all three thread types and nothing else`() {
        assertTrue(channel(type = Channel.ANNOUNCEMENT_THREAD).isThread())
        assertTrue(channel(type = Channel.PUBLIC_THREAD).isThread())
        assertTrue(channel(type = Channel.PRIVATE_THREAD).isThread())
        assertFalse(channel(type = Channel.GUILD_TEXT).isThread())
        assertFalse(channel(type = Channel.GUILD_FORUM).isThread())
        assertTrue(channel(type = Channel.PRIVATE_THREAD).isPrivateThread())
        assertFalse(channel(type = Channel.PUBLIC_THREAD).isPrivateThread())
    }

    @Test fun `threaded covers the channels threads can be created in`() {
        assertTrue(channel(type = Channel.GUILD_TEXT).isThreaded())
        assertTrue(channel(type = Channel.GUILD_ANNOUNCEMENT).isThreaded())
        assertTrue(channel(type = Channel.GUILD_FORUM).isThreaded())
        assertTrue(channel(type = Channel.GUILD_MEDIA).isThreaded())
        assertFalse(channel(type = Channel.GUILD_VOICE).isThreaded())
        assertFalse(channel(type = Channel.PUBLIC_THREAD).isThreaded())
    }

    @Test fun `guild texty includes threads directories and forums`() {
        val texty = listOf(
            Channel.GUILD_TEXT, Channel.GUILD_ANNOUNCEMENT, Channel.ANNOUNCEMENT_THREAD,
            Channel.PUBLIC_THREAD, Channel.PRIVATE_THREAD, Channel.DIRECTORY, Channel.GUILD_FORUM,
            Channel.GUILD_MEDIA,
        )
        texty.forEach { assertTrue("type $it", channel(type = it).isGuildTextyChannel()) }
        val notTexty = listOf(
            Channel.DM, Channel.GROUP_DM, Channel.GUILD_VOICE, Channel.CATEGORY,
            Channel.GUILD_STAGE_VOICE, Channel.GUILD_STORE,
        )
        notTexty.forEach { assertFalse("type $it", channel(type = it).isGuildTextyChannel()) }
    }

    @Test fun `message requests distinguish pending and spam DMs`() {
        val request = channel(type = Channel.DM).copy(isMessageRequest = true)
        val spam = request.copy(isSpam = true)

        assertTrue(request.isMessageRequestChannel())
        assertFalse(request.isSpamMessageRequestChannel())
        assertTrue(spam.isSpamMessageRequestChannel())
        assertTrue(spam.isPendingMessageRequestChannel())
        assertFalse(channel(type = Channel.GROUP_DM).copy(isMessageRequest = true).isPendingMessageRequestChannel())
    }

    @Test fun `thread only covers forum and media channels`() {
        assertTrue(channel(type = Channel.GUILD_FORUM).isThreadOnlyChannel())
        assertTrue(channel(type = Channel.GUILD_MEDIA).isThreadOnlyChannel())
        assertTrue(channel(type = Channel.GUILD_MEDIA).isGuildMediaChannel())
        assertFalse(channel(type = Channel.GUILD_TEXT).isThreadOnlyChannel())
    }

    @Test fun `text or voice excludes categories and stages`() {
        assertTrue(channel(type = Channel.GUILD_TEXT).isTextOrVoiceChannel())
        assertTrue(channel(type = Channel.DM).isTextOrVoiceChannel())
        assertTrue(channel(type = Channel.GUILD_VOICE).isTextOrVoiceChannel())
        assertFalse(channel(type = Channel.CATEGORY).isTextOrVoiceChannel())
        assertFalse(channel(type = Channel.GUILD_STAGE_VOICE).isTextOrVoiceChannel())
    }

    @Test fun `guild vocal covers voice and stage`() {
        assertTrue(channel(type = Channel.GUILD_VOICE).isGuildVocalChannel())
        assertTrue(channel(type = Channel.GUILD_STAGE_VOICE).isGuildVocalChannel())
        assertFalse(channel(type = Channel.GUILD_STAGE_VOICE).isVoiceChannel())
        assertTrue(channel(type = Channel.GUILD_STAGE_VOICE).isStageVoiceChannel())
    }

    @Test fun `managed means owned by an application`() {
        assertFalse(channel(type = Channel.GROUP_DM).isManaged())
        assertTrue(channel(type = Channel.GROUP_DM, applicationId = 42).isManaged())
    }

    @Test fun `system DM is a DM whose recipient is a system account`() {
        assertTrue(channel(type = Channel.DM, recipients = listOf(user(1, system = true))).isSystemDM())
        assertFalse(channel(type = Channel.DM, recipients = listOf(user(1))).isSystemDM())
        assertFalse(
            channel(type = Channel.GROUP_DM, recipients = listOf(user(1, system = true))).isSystemDM(),
        )
    }

    @Test fun `display name falls back to recipients only for unnamed private channels`() {
        val recipients = listOf(user(1, "ada", globalName = "Ada Display"), user(2, "grace"))
        assertEquals(
            "Ada Display, grace",
            channel(type = Channel.GROUP_DM, name = null, recipients = recipients).getDisplayName(),
        )
        assertEquals(
            "squad",
            channel(type = Channel.GROUP_DM, name = "squad", recipients = recipients).getDisplayName(),
        )
        // A guild channel never borrows recipient names, and never returns null.
        assertEquals("", channel(type = Channel.GUILD_TEXT, name = null).getDisplayName())
        assertEquals("", channel(type = Channel.DM, name = "").getDisplayName())
    }

    @Test fun `a friend nickname overrides the recipient's own name`() {
        val recipients = listOf(user(1, "ada"), user(2, "grace"))
        val previous = friendNicknameResolver
        friendNicknameResolver = { id -> if (id == 1L) "Countess" else null }
        try {
            assertEquals(
                "Countess, grace",
                channel(type = Channel.GROUP_DM, name = null, recipients = recipients).getDisplayName(),
            )
            // An explicitly named channel is never rebuilt from its recipients.
            assertEquals(
                "squad",
                channel(type = Channel.GROUP_DM, name = "squad", recipients = recipients).getDisplayName(),
            )
        } finally {
            friendNicknameResolver = previous
        }
    }

    @Test fun `pinned reads the channel flag`() {
        assertFalse(channel(type = Channel.PUBLIC_THREAD).isPinned())
        assertFalse(channel(type = Channel.PUBLIC_THREAD, flags = Channel.FLAGS_NONE).isPinned())
        assertTrue(channel(type = Channel.PUBLIC_THREAD, flags = Channel.FLAG_PINNED).isPinned())
    }

    @Test fun `archived thread reads the thread metadata`() {
        assertFalse(channel(type = Channel.PUBLIC_THREAD).isArchivedThread())
        assertTrue(
            channel(
                type = Channel.PUBLIC_THREAD,
                threadMetadata = ThreadMetadata(true, null, 1440, false),
            ).isArchivedThread(),
        )
    }

    @Test fun `permission overwrite lookup matches by id`() {
        val overwrite = PermissionOverwrite(5, PermissionOverwrite.Type.ROLE, 1, 0)
        val target = channel(type = Channel.GUILD_TEXT, overwrites = listOf(overwrite))
        assertSame(overwrite, target.getPermissionOverwriteForId(5))
        assertNull(target.getPermissionOverwriteForId(6))
        assertNull(channel(type = Channel.GUILD_TEXT).getPermissionOverwriteForId(5))
    }

    @Test fun `dm recipient is only resolved for one-to-one DMs`() {
        assertEquals(1L, channel(type = Channel.DM, recipients = listOf(user(1))).getDMRecipient()?.id)
        assertNull(channel(type = Channel.DM, recipients = listOf(user(1), user(2))).getDMRecipient())
        assertNull(channel(type = Channel.GROUP_DM, recipients = listOf(user(1))).getDMRecipient())
        assertNull(channel(type = Channel.DM).getDMRecipient())
    }

    @Test fun `deleted message resource depends on the channel kind`() {
        assertNull(channel(type = Channel.DM).getDeletedMessage())
        assertEquals(
            channel(type = Channel.CATEGORY).getDeletedMessage(),
            channel(type = Channel.CATEGORY).getDeletedMessage(),
        )
        // Category, thread, and plain channel each map to a distinct resource.
        val ids = listOf(Channel.CATEGORY, Channel.PUBLIC_THREAD, Channel.GUILD_TEXT)
            .map { channel(type = it).getDeletedMessage() }
        assertEquals(3, ids.toSet().size)
    }

    @Test fun `sort by name and type puts categories first then text then voice`() {
        val category = channel(id = 1, type = Channel.CATEGORY, position = 5)
        val text = channel(id = 2, type = Channel.GUILD_TEXT, position = 9)
        val voice = channel(id = 3, type = Channel.GUILD_VOICE, position = 0)
        val sorted = listOf(voice, text, category).sortedWith(Channel.getSortByNameAndType())
        assertEquals(listOf(1L, 2L, 3L), sorted.map(Channel::id))
    }

    @Test fun `sort by most recent prefers the newest known message`() {
        val a = channel(id = 1, type = Channel.DM)
        val b = channel(id = 2, type = Channel.DM)
        val sorted = listOf(b, a)
            .sortedWith(Channel.getSortByMostRecent(mapOf(1L to 100L, 2L to 50L)))
        assertEquals(listOf(1L, 2L), sorted.map(Channel::id))
    }

    private fun user(
        id: Long,
        username: String = "user",
        system: Boolean? = null,
        globalName: String? = null,
    ) = User(id = id, username = username, system = system, globalName = globalName)

    private fun channel(
        id: Long = 10,
        type: Int,
        name: String? = "channel",
        position: Int = 0,
        applicationId: Long = 0,
        flags: Long? = null,
        recipients: List<User>? = null,
        overwrites: List<PermissionOverwrite>? = null,
        threadMetadata: ThreadMetadata? = null,
    ) = Channel(
        topic = null, type = type, guildId = 1, name = name, lastMessageId = 0, id = id,
        ownerId = 0, recipientIds = null, recipients = recipients, position = position,
        permissionOverwrites = overwrites, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = applicationId, nicks = null, nsfw = false,
        parentId = 0, memberListId = null, rateLimitPerUser = 0, defaultAutoArchiveDuration = null,
        rtcRegion = null, flags = flags, guildHashes = null, availableTags = null,
        appliedTags = null, threadMetadata = threadMetadata, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )
}
