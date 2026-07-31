package com.discord.widgets.channels

import com.discord.utilities.collections.SparseMutableList
import com.discord.widgets.channels.memberlist.GuildMemberListItems
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import java.io.File
import java.util.TreeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelExactJvmPathsTest {
    @Test
    fun allReferencePathsExist() {
        val sourceRoot = sequenceOf(
            File("app/src/main/java/com/discord/widgets/channels"),
            File("src/main/java/com/discord/widgets/channels"),
        ).first(File::isDirectory)

        EXACT_PATHS.forEach { assertTrue(it, File(sourceRoot, it).isFile) }
    }

    @Test
    fun adapterContractsMatchRecoveredOrdinalsAndIdentity() {
        assertEquals(
            listOf("ROLE_HEADER", "PLACEHOLDER_HEADER", "HEADER", "MEMBER", "ADD_MEMBER", "LOADING", "JOIN_LEAVE_THREAD"),
            ChannelMembersListAdapter.ViewType.values().map { it.name },
        )
        assertEquals(
            listOf("ONLINE", "OFFLINE", "GROUP_DM"),
            ChannelMembersListAdapter.Item.Header.Type.values().map { it.name },
        )
        assertEquals(42L, ChannelMembersListAdapter.Item.Member(
            42L, null, "name", false, null, false, null, null, null, false, null, false, true, 0,
        ).rowId)
    }

    @Test
    fun guildMemberListUsesSparsePlaceholdersAndNearestHeader() {
        val rows = SparseMutableList<ChannelMembersListAdapter.Item>(4)
        rows[1] = ChannelMembersListAdapter.Item.Header(
            "online",
            ChannelMembersListAdapter.Item.Header.Type.ONLINE,
            2,
        )
        val list = GuildMemberListItems("guild", 4, rows, TreeMap(mapOf(1 to "online")))

        assertNotNull(list[0])
        assertEquals(1, list.getHeaderPositionForItem(3))
        assertNull(list.getHeaderPositionForItem(0))
    }

    private companion object {
        val EXACT_PATHS = listOf(
            "TextInVoiceOnboardingManagerKt.kt",
            "WidgetChannelTopicViewModelKt.kt",
            "memberlist/GuildMemberListItemGeneratorKt.kt",
            "memberlist/GuildMemberListItems.kt",
            "memberlist/PrivateChannelMemberListItemGeneratorKt.kt",
            "memberlist/PrivateChannelMemberListItems.kt",
            "memberlist/ThreadMemberListItemGeneratorKt.kt",
            "memberlist/ThreadMemberListItems.kt",
            "memberlist/WidgetChannelMembersListKt.kt",
            "memberlist/adapter/ChannelMembersListAdapter.kt",
            "memberlist/adapter/ChannelMembersListAdapterKt.kt",
            "memberlist/adapter/ChannelMembersListViewHolderAdd.kt",
            "memberlist/adapter/ChannelMembersListViewHolderHeader.kt",
            "memberlist/adapter/ChannelMembersListViewHolderJoinLeaveThread.kt",
            "memberlist/adapter/ChannelMembersListViewHolderLoading.kt",
            "memberlist/adapter/ChannelMembersListViewHolderMember.kt",
            "memberlist/adapter/ChannelMembersListViewHolderPlaceholderHeader.kt",
            "memberlist/adapter/ChannelMembersListViewHolderRoleHeader.kt",
            "permissions/AddMemberAdapterKt.kt",
            "permissions/WidgetChannelSettingsPermissionsOverviewViewModelKt.kt",
            "threads/browser/WidgetThreadBrowserActiveViewModel.kt",
            "threads/browser/WidgetThreadBrowserArchivedViewModel.kt",
            "threads/browser/WidgetThreadBrowserViewModel.kt",
        )
    }
}
