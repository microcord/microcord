package com.discord.widgets.channels.threads.browser

import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.stores.ArchivedThreadsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadBrowserViewModelsTest {
    @Test fun threadCreationRequiresARealEligibleChannelAndGuild() {
        val channel = channel(Channel.GUILD_TEXT)
        val guild = Guild(id = channel.guildId)
        assertFalse(WidgetThreadBrowserViewModel.canCreateThread(null, channel, guild))
        assertFalse(WidgetThreadBrowserViewModel.canCreateThread(Permission.CREATE_PUBLIC_THREADS, null, guild))
        assertFalse(WidgetThreadBrowserViewModel.canCreateThread(Permission.CREATE_PUBLIC_THREADS, channel, null))
        assertFalse(
            WidgetThreadBrowserViewModel.canCreateThread(
                Permission.CREATE_PUBLIC_THREADS,
                channel(Channel.GUILD_VOICE),
                guild,
            ),
        )
    }

    @Test fun oneListCarriesJoinedActiveAndOlderSections() {
        val joined = channel(Channel.PUBLIC_THREAD, id = 10)
        val live = channel(Channel.PUBLIC_THREAD, id = 11)
        val older = channel(Channel.PUBLIC_THREAD, id = 12)
        val items = WidgetThreadBrowserListViewModel.buildState(
            storeState(
                joinedThreads = mapOf(joined.id to joined),
                liveThreads = mapOf(joined.id to joined, live.id to live),
                older = ArchivedThreadsStore.ThreadListingState.Listing(
                    listOf(older),
                    hasMore = false,
                    isLoadingMore = false,
                ),
            ),
        ).listItems

        assertEquals(
            listOf(
                R.string.thread_browser_joined_count,
                R.string.thread_browser_active_count,
                R.string.thread_browser_older_header,
            ),
            items.filterIsInstance<WidgetThreadBrowserAdapter.Item.Header>().map { it.stringResId },
        )
        // A joined thread is not repeated in the active section.
        assertEquals(
            listOf(joined.id, live.id, older.id),
            items.filterIsInstance<WidgetThreadBrowserAdapter.Item.Thread>().map { it.threadData.channel.id },
        )
    }

    @Test fun emptyChannelReportsEmptyRatherThanBareSections() {
        val state = WidgetThreadBrowserListViewModel.buildState(
            storeState(
                older = ArchivedThreadsStore.ThreadListingState.Listing(
                    emptyList(),
                    hasMore = false,
                    isLoadingMore = false,
                ),
            ),
        )
        assertTrue(state.isEmpty)
        assertTrue(state.listItems.isEmpty())
    }

    private fun storeState(
        joinedThreads: Map<Long, Channel> = emptyMap(),
        liveThreads: Map<Long, Channel> = emptyMap(),
        older: ArchivedThreadsStore.ThreadListingState,
    ) = WidgetThreadBrowserListViewModel.StoreState(
        meUser = me(),
        joinedThreads = joinedThreads,
        liveThreads = liveThreads,
        threadStates = emptyMap(),
        guildMembers = emptyMap(),
        users = emptyMap(),
        guildRoles = emptyMap(),
        channelNames = emptyMap(),
        permissions = null,
        blockedUsers = emptyMap(),
        channel = channel(Channel.GUILD_TEXT),
        guild = null,
        olderThreads = older,
    )

    private fun me() = MeUser(
        1, "me", null, null, false, false, 0, PremiumTier.NONE,
        null, false, true, null, 0, 0, null, NsfwAllowance.UNKNOWN, null, null, null,
    )

    private fun channel(type: Int, id: Long = 2) = Channel(
        topic = null,
        type = type,
        guildId = 1,
        name = "threads",
        lastMessageId = 0,
        id = id,
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
