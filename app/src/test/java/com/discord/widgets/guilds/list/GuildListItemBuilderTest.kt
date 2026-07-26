package com.discord.widgets.guilds.list

import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.stores.StoreGuildsSorted
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuildListItemBuilderTest {

    @After fun resetHooks() {
        GuildsListStoreHooks.isHubDiscoveryEnabled = { false }
        GuildsListStoreHooks.isHubDiscoverySparkleEnabled = { false }
    }

    @Test fun emitsTheFixedRowsAroundTheGuilds() {
        val items = GuildListItemBuilder.buildItems(storeState(sortedGuilds = listOf(singleton(1))))

        assertEquals(
            listOf(
                GuildListItem.FriendsItem::class,
                GuildListItem.DividerItem::class,
                GuildListItem.GuildItem::class,
                GuildListItem.CreateItem::class,
                GuildListItem.SpaceItem::class,
            ),
            items.map { it::class },
        )
    }

    @Test fun putsTheOutageRowBeforeTheCreateRowOnlyWhenGuildsAreUnavailable() {
        val without = GuildListItemBuilder.buildItems(storeState())
        assertTrue(without.none { it is GuildListItem.UnavailableItem })

        val with = GuildListItemBuilder.buildItems(storeState(unavailableGuilds = setOf(7L, 8L)))
        val outage = with.filterIsInstance<GuildListItem.UnavailableItem>().single()
        assertEquals(2, outage.unavailableGuildCount)
        assertTrue(with.indexOf(outage) < with.indexOf(GuildListItem.CreateItem))
    }

    @Test fun givesNewAccountsAHelpRowBehindItsOwnDivider() {
        val items = GuildListItemBuilder.buildItems(storeState(isNewUser = true))

        val helpIndex = items.indexOf(GuildListItem.HelpItem)
        assertTrue(helpIndex > 0)
        assertEquals(GuildListItem.DividerItem, items[helpIndex - 1])
        assertEquals(GuildListItem.SpaceItem, items.last())
    }

    @Test fun showsTheHubRowOnlyWhenDiscoveryIsEnabled() {
        assertTrue(GuildListItemBuilder.buildItems(storeState()).none { it is GuildListItem.HubItem })

        GuildsListStoreHooks.isHubDiscoveryEnabled = { true }
        GuildsListStoreHooks.isHubDiscoverySparkleEnabled = { true }
        val hub = GuildListItemBuilder.buildItems(storeState(showHubSparkle = true))
            .filterIsInstance<GuildListItem.HubItem>()
            .single()

        assertTrue(hub.showSparkle)
    }

    @Test fun pinsLurkedGuildsAboveTheDivider() {
        val items = GuildListItemBuilder.buildItems(
            storeState(
                sortedGuilds = listOf(singleton(1), singleton(2)),
                lurkingGuildIds = setOf(2L),
            ),
        )

        val dividerIndex = items.indexOf(GuildListItem.DividerItem)
        val lurked = items.filterIsInstance<GuildListItem.GuildItem>().single { it.isLurkingGuild }
        val joined = items.filterIsInstance<GuildListItem.GuildItem>().single { !it.isLurkingGuild }

        assertTrue(items.indexOf(lurked) < dividerIndex)
        assertTrue(items.indexOf(joined) > dividerIndex)
    }

    @Test fun collapsedFolderAggregatesItsGuildsAndHidesTheirRows() {
        val items = GuildListItemBuilder.buildItems(
            storeState(
                sortedGuilds = listOf(folder(id = 100, guildIds = listOf(1, 2))),
                selectedGuildId = 2L,
                unreadGuildIds = setOf(1L),
                channelIds = mapOf(1L to listOf(11L), 2L to listOf(22L)),
                mentionCounts = mapOf(11L to 3, 22L to 4),
            ),
        )

        val folderItem = items.filterIsInstance<GuildListItem.FolderItem>().single()
        assertFalse(folderItem.isOpen)
        assertTrue(folderItem.isAnyGuildSelected)
        assertTrue(folderItem.isUnread)
        assertEquals(7, folderItem.mentionCount)
        assertTrue(items.none { it is GuildListItem.GuildItem })
    }

    @Test fun expandedFolderDefersEverythingToItsChildren() {
        val items = GuildListItemBuilder.buildItems(
            storeState(
                sortedGuilds = listOf(folder(id = 100, guildIds = listOf(1, 2))),
                openFolderIds = setOf(100L),
                selectedGuildId = 2L,
                unreadGuildIds = setOf(1L),
                channelIds = mapOf(1L to listOf(11L)),
                mentionCounts = mapOf(11L to 3),
            ),
        )

        val folderItem = items.filterIsInstance<GuildListItem.FolderItem>().single()
        assertTrue(folderItem.isOpen)
        assertFalse(folderItem.isAnyGuildSelected)
        assertFalse(folderItem.isUnread)
        assertEquals(0, folderItem.mentionCount)

        val children = items.filterIsInstance<GuildListItem.GuildItem>()
        assertEquals(listOf(100L, 100L), children.map { it.folderId })
        assertEquals(listOf(false, true), children.map { it.isLastGuildInFolder })
        assertEquals(listOf(3, 0), children.map { it.mentionCount })
        assertEquals(listOf(false, true), children.map { it.isSelected })
    }

    @Test fun mutedGuildsAreNeverUnread() {
        val items = GuildListItemBuilder.buildItems(
            storeState(sortedGuilds = listOf(singleton(1)), unreadGuildIds = setOf(1L)),
        )
        assertTrue(items.filterIsInstance<GuildListItem.GuildItem>().single().isUnread)

        val muted = GuildListItemBuilder.buildItems(
            storeState(
                sortedGuilds = listOf(singleton(1)),
                unreadGuildIds = setOf(1L),
                guildSettings = mapOf(1L to mutedSettings()),
            ),
        )
        assertFalse(muted.filterIsInstance<GuildListItem.GuildItem>().single().isUnread)
    }

    private fun singleton(guildId: Long) = StoreGuildsSorted.Entry.SingletonGuild(guild(guildId))

    private fun folder(id: Long, guildIds: List<Long>) =
        StoreGuildsSorted.Entry.Folder(id, guildIds.map(::guild), null, "Folder $id")

    private fun guild(id: Long) = Guild(id = id, name = "Guild $id")

    private fun mutedSettings() = ModelNotificationSettings(muted = true, guildId = 1L)

    private fun storeState(
        sortedGuilds: List<StoreGuildsSorted.Entry> = emptyList(),
        selectedGuildId: Long = 0L,
        openFolderIds: Set<Long> = emptySet(),
        unreadGuildIds: Set<Long> = emptySet(),
        mentionCounts: Map<Long, Int> = emptyMap(),
        channelIds: Map<Long, List<Long>> = emptyMap(),
        unavailableGuilds: Set<Long> = emptySet(),
        lurkingGuildIds: Set<Long> = emptySet(),
        guildSettings: Map<Long, ModelNotificationSettings> = emptyMap(),
        isNewUser: Boolean = false,
        showHubSparkle: Boolean = false,
    ) = WidgetGuildsListViewModel.StoreState(
        selectedGuildId = selectedGuildId,
        selectedVoiceChannelId = 0L,
        guildSettings = guildSettings,
        unreadGuildIds = unreadGuildIds,
        mentionCounts = mentionCounts,
        channelIds = channelIds,
        unavailableGuilds = unavailableGuilds,
        privateChannels = emptyMap(),
        sortedGuilds = sortedGuilds,
        guildJoinRequests = emptyMap(),
        pendingGuilds = emptyList(),
        guildIds = emptySet(),
        lurkingGuildIds = lurkingGuildIds,
        guildIdsWithActiveStageEvents = emptySet(),
        guildIdsWithActiveScheduledEvents = emptySet(),
        channels = emptyMap(),
        openFolderIds = openFolderIds,
        isNewUser = isNewUser,
        applicationStreams = emptyList(),
        allChannelPermissions = emptyMap(),
        isLeftPanelOpened = false,
        isOnHomeTab = true,
        showHubSparkle = showHubSparkle,
    )
}
