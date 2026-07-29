package com.discord.widgets.channels.threads.browser

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.models.guild.Guild
import com.discord.stores.ArchivedThreadsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadBrowserViewModelsTest {
    @Test fun archivedViewModeSelectsTheReferenceEndpoint() {
        assertEquals(
            ArchivedThreadsStore.ThreadListingType.ALL_ARCHIVED_PUBLIC_THREADS,
            WidgetThreadBrowserArchivedViewModel.ViewMode(
                WidgetThreadBrowserArchivedViewModel.VisibilityMode.PublicThreads,
                true,
            ).threadListingType,
        )
        assertEquals(
            ArchivedThreadsStore.ThreadListingType.MY_ARCHIVED_PRIVATE_THREADS,
            WidgetThreadBrowserArchivedViewModel.ViewMode(
                WidgetThreadBrowserArchivedViewModel.VisibilityMode.PrivateThreads,
                false,
            ).threadListingType,
        )
        assertEquals(
            ArchivedThreadsStore.ThreadListingType.ALL_ARCHIVED_PRIVATE_THREADS,
            WidgetThreadBrowserArchivedViewModel.ViewMode(
                WidgetThreadBrowserArchivedViewModel.VisibilityMode.PrivateThreads,
                true,
            ).threadListingType,
        )
    }

    @Test fun threadCreationRequiresARealEligibleChannelAndGuild() {
        val channel = channel(Channel.GUILD_TEXT)
        val guild = Guild(id = channel.guildId)
        assertFalse(WidgetThreadBrowserViewModel.canCreateThread(null, channel, guild))
        assertFalse(WidgetThreadBrowserViewModel.canCreateThread(Permission.CREATE_PUBLIC_THREADS, null, guild))
        assertFalse(WidgetThreadBrowserViewModel.canCreateThread(Permission.CREATE_PUBLIC_THREADS, channel, null))
        assertFalse(WidgetThreadBrowserViewModel.canCreateThread(Permission.CREATE_PUBLIC_THREADS, channel(Channel.GUILD_VOICE), guild))
    }

    @Test fun archivedRowsKeepHeadersAndUseCreatedAtOnlyForMyPrivateThreads() {
        val thread = channel(Channel.PRIVATE_THREAD)
        val listing = ArchivedThreadsStore.ThreadListingState.Listing(listOf(thread), hasMore = false, isLoadingMore = false)

        val mine = WidgetThreadBrowserArchivedViewModel.buildItems(
            WidgetThreadBrowserArchivedViewModel.ViewMode(
                WidgetThreadBrowserArchivedViewModel.VisibilityMode.PrivateThreads,
                false,
            ),
            listing,
            emptyMap(),
            emptyMap(),
            isForumChannel = true,
        )
        val moderator = WidgetThreadBrowserArchivedViewModel.buildItems(
            WidgetThreadBrowserArchivedViewModel.ViewMode(
                WidgetThreadBrowserArchivedViewModel.VisibilityMode.PrivateThreads,
                true,
            ),
            listing,
            emptyMap(),
            emptyMap(),
            isForumChannel = false,
        )

        assertTrue(mine[0] is WidgetThreadBrowserAdapter.Item.Warning)
        assertTrue(mine[1] is WidgetThreadBrowserAdapter.Item.Header)
        assertEquals(
            ThreadBrowserThreadView.TimestampMode.CreatedAt,
            ((mine[2] as WidgetThreadBrowserAdapter.Item.Thread).threadData as ThreadBrowserThreadView.ThreadData.ArchivedThread).timestampMode,
        )
        assertEquals(
            ThreadBrowserThreadView.TimestampMode.ArchivedAt,
            ((moderator[1] as WidgetThreadBrowserAdapter.Item.Thread).threadData as ThreadBrowserThreadView.ThreadData.ArchivedThread).timestampMode,
        )
    }

    private fun channel(type: Int) = Channel(
        topic = null,
        type = type,
        guildId = 1,
        name = "threads",
        lastMessageId = 0,
        id = 2,
        ownerId = 3,
        recipientIds = null,
        recipients = null,
        position = 0,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0,
        applicationId = 0,
        nicks = null,
        nsfw = false,
        parentId = 0,
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
