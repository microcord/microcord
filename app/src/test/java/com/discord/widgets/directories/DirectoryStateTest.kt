package com.discord.widgets.directories

import com.discord.api.channel.Channel
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.api.guild.preview.GuildPreview
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.hubs.DirectoryEntryCategory
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.Failure
import com.discord.stores.utilities.Loading
import com.discord.stores.utilities.Success
import com.discord.utilities.error.RestApiError
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryStateTest {

    @Test
    fun viewTypeOrdinalsMatchTheReference() {
        // Load bearing: the ordinals are the recycler's view types, and `findType` indexes by them.
        assertEquals(0, DirectoryChannelItem.ViewType.Item.ordinal)
        assertEquals(1, DirectoryChannelItem.ViewType.AddServer.ordinal)
        assertEquals(2, DirectoryChannelItem.ViewType.SectionHeader.ordinal)
        assertEquals(
            DirectoryChannelItem.ViewType.SectionHeader,
            DirectoryChannelItem.ViewType.findType(2),
        )
        assertEquals(1, DirectoryChannelItem.AddServer.type)
    }

    @Test
    fun rowsKeyOnTheThingTheyRender() {
        val first = DirectoryChannelItem.DirectoryItem(entryData(guildId = 7L))
        val second = DirectoryChannelItem.DirectoryItem(entryData(guildId = 8L))
        assertEquals(first.key, DirectoryChannelItem.DirectoryItem(entryData(guildId = 7L)).key)
        assertTrue(first.key != second.key)
    }

    @Test
    fun shortNameReducesEachWordToItsInitial() {
        assertEquals("MSS", computeShortName("Mantik's Study Server"))
        assertEquals("HW", computeShortName("Hello World"))
        assertEquals("D", computeShortName("Discord"))
    }

    @Test
    fun newestSectionKeepsOnlyFeaturableServersOverTheMemberFloor() {
        val featurableAndBigEnough = entryData(guildId = 1L, members = 50, featurable = true, createdAt = 100)
        val notFeaturable = entryData(guildId = 2L, members = 50, featurable = false, createdAt = 200)
        val tooSmall = entryData(guildId = 3L, members = 4, featurable = true, createdAt = 300)
        val newer = entryData(guildId = 4L, members = 5, featurable = true, createdAt = 400)

        val newest = listOf(featurableAndBigEnough, notFeaturable, tooSmall, newer).sortByAdded()

        assertEquals(listOf(4L, 1L), newest.map { it.entry.guild.id })
    }

    @Test
    fun newestSectionIsCappedAtFiveEntries() {
        val entries = (1..9).map { entryData(guildId = it.toLong(), members = 10, featurable = true, createdAt = it.toLong()) }
        assertEquals(5, entries.sortByAdded().size)
    }

    @Test
    fun entriesAreBucketedByCategoryAndJoinedWithTheViewersRelationship() {
        val state = WidgetDirectoriesViewModel.ViewState(
            joinedGuildIds = setOf(1L),
            adminGuildIds = setOf(2L),
            directories = Success(
                listOf(
                    entry(guildId = 1L, categoryId = 3),
                    entry(guildId = 2L, categoryId = 3),
                    entry(guildId = 3L, categoryId = 5),
                ),
            ),
        )

        assertEquals(setOf(3, 5), state.directoryEntryData.keys)
        assertEquals(2, state.directoryEntryData.getValue(3).size)
        assertTrue(state.directoryEntryData.getValue(3).first { it.entry.guild.id == 1L }.hasJoinedGuild)
        assertTrue(state.directoryEntryData.getValue(3).first { it.entry.guild.id == 2L }.hasEditPermissions)
        assertEquals(3, state.allDirectoryEntryData.size)
    }

    @Test
    fun tabStripAlwaysLeadsWithHomeAndCarriesTheServerCounts() {
        val state = WidgetDirectoriesViewModel.ViewState(
            channel = channel(),
            tabs = Success(mapOf(DirectoryEntryCategory.Class.key to 4)),
        )

        assertEquals(
            listOf(DirectoryEntryCategory.Home to 0, DirectoryEntryCategory.Class to 4),
            state.mappedTabs,
        )
    }

    @Test
    fun loadStateDistinguishesNotAskedLoadingEmptyAndFailed() {
        assertEquals(
            DirectoryLoadState.Loading,
            WidgetDirectoriesViewModel.ViewState(directories = Default).loadState,
        )
        assertEquals(
            DirectoryLoadState.Loading,
            WidgetDirectoriesViewModel.ViewState(directories = Loading).loadState,
        )
        assertEquals(
            DirectoryLoadState.Error,
            WidgetDirectoriesViewModel.ViewState(
                directories = Failure(RestApiError.from(IOException("network down"))),
            ).loadState,
        )
        assertEquals(
            DirectoryLoadState.Empty,
            WidgetDirectoriesViewModel.ViewState(directories = Success(emptyList())).loadState,
        )
        assertEquals(
            DirectoryLoadState.Content,
            WidgetDirectoriesViewModel.ViewState(directories = Success(listOf(entry()))).loadState,
        )
    }

    @Test
    fun searchKeepsThePreviousResultsWhileTheNextSearchIsInFlight() {
        val state = WidgetDirectoriesSearchViewModel.ViewState(
            currentSearchTerm = "study",
            directories = listOf(entry(guildId = 1L)),
            directoriesState = Loading,
        )
        assertEquals(DirectoryLoadState.Content, state.loadState)
        assertEquals(1, state.directoryEntryData.size)
    }

    @Test
    fun realNamePromptOnlyShowsForAnUnseenBlankNickname() {
        assertTrue(shouldPromptForHubName(null, wasSeen = false))
        assertTrue(shouldPromptForHubName(" ", wasSeen = false))
        assertFalse(shouldPromptForHubName("Manti", wasSeen = false))
        assertFalse(shouldPromptForHubName(null, wasSeen = true))
        assertFalse(shouldPromptForHubName(null, wasSeen = false, killSwitchEnabled = true))
    }

    private fun entryData(
        guildId: Long = 1L,
        members: Int = 10,
        featurable: Boolean = true,
        createdAt: Long = 0L,
    ) = DirectoryEntryData(
        entry = entry(guildId = guildId, members = members, featurable = featurable, createdAt = createdAt),
        hasJoinedGuild = false,
        hasEditPermissions = false,
    )

    private fun entry(
        guildId: Long = 1L,
        categoryId: Int = 0,
        members: Int = 10,
        featurable: Boolean = true,
        createdAt: Long = 0L,
    ) = DirectoryEntryGuild(
        directoryChannelId = 99L,
        entityId = guildId,
        authorId = 5L,
        description = "description",
        primaryCategoryId = categoryId,
        guild = GuildPreview(
            id = guildId,
            name = "Guild $guildId",
            description = null,
            splash = null,
            banner = null,
            icon = null,
            approximatePresenceCount = members / 2,
            approximateMemberCount = members,
            emojis = emptyList(),
            features = emptyList(),
            featurableInDirectory = featurable,
        ),
        createdAt = UtcDateTime(createdAt),
    )

    private fun channel() = Channel(
        topic = null,
        type = Channel.DIRECTORY,
        guildId = 1L,
        name = "directory",
        lastMessageId = 0,
        id = 42L,
        ownerId = 0,
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
