package com.discord.widgets.friends

import com.discord.R
import com.discord.api.presence.ClientStatus
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.friendsuggestions.FriendSuggestion
import com.discord.models.presence.Presence
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure half of [FriendsListViewModel]: the store fold and the collapse toggles. Both run
 * without Android, which is the reason the reference keeps them out of the fragment.
 */
class FriendsListViewModelTest {

    private fun user(id: Long, name: String): User = CoreUser(id = id, username = name)

    /**
     * Row keys mix ids and string-resource ids, so matching one by suffix collides with whatever
     * resource id a header happens to carry.
     */
    private fun FriendsListViewModel.Item.userId(): Long? = when (this) {
        is FriendsListViewModel.Item.Friend -> user.id
        is FriendsListViewModel.Item.PendingFriendRequest -> user.id
        is FriendsListViewModel.Item.SuggestedFriend -> suggestion.user.id
        else -> null
    }

    private fun presence(status: ClientStatus) = Presence(status, null, null)

    private fun sections() = FriendsListViewModel.buildListSections(
        relationships = mapOf(
            5L to ModelUserRelationship.TYPE_BLOCKED,
            4L to ModelUserRelationship.TYPE_INVITE_OUTGOING,
            3L to ModelUserRelationship.TYPE_INVITE_INCOMING,
            2L to ModelUserRelationship.TYPE_FRIEND,
            1L to ModelUserRelationship.TYPE_FRIEND,
        ),
        users = mapOf(
            1L to user(1, "Online"),
            2L to user(2, "Offline"),
            3L to user(3, "Amy"),
            4L to user(4, "Zed"),
            BLOCKED_USER_ID to user(BLOCKED_USER_ID, "Blocked"),
        ),
        presences = mapOf(1L to presence(ClientStatus.ONLINE)),
        applicationStreams = emptyMap(),
        showContactSyncUpsell = false,
        friendSuggestions = listOf(7L, 6L, 8L).associateWith {
            FriendSuggestion(user(it, "S$it"), null)
        },
    )

    @Test
    fun `blocked relationships never become rows`() {
        val listSections = sections()
        val allItems = listSections.pendingItems +
            listSections.friendsItemsWithHeaders +
            listSections.suggestedFriendItems
        assertTrue(allItems.none { it.userId() == BLOCKED_USER_ID })
    }

    @Test
    fun `pending requests put incoming first then order by name`() {
        val listSections = sections()
        assertEquals(
            listOf(3L, 4L),
            listSections.pendingItems
                .filterIsInstance<FriendsListViewModel.Item.PendingFriendRequest>()
                .map { it.user.id },
        )
    }

    @Test
    fun `friends split into online and offline sections`() {
        val items = sections().friendsItemsWithHeaders
        val headers = items.filterIsInstance<FriendsListViewModel.Item.Header>()
        assertEquals(
            listOf(R.string.friends_online_header, R.string.friends_offline_header),
            headers.map { it.titleStringResId },
        )
        assertEquals(
            listOf(1L, 2L),
            items.filterIsInstance<FriendsListViewModel.Item.Friend>().map { it.user.id },
        )
    }

    @Test
    fun `suggestions are ordered by user id`() {
        assertEquals(
            listOf(6L, 7L, 8L),
            sections().suggestedFriendItems.map { it.suggestion.user.id },
        )
    }

    @Test
    fun `collapsed sections show two rows and expanding shows the rest`() {
        val listSections = sections()

        val collapsed = FriendsListViewModel.getVisibleItems(
            listSections,
            isSuggestedSectionExpanded = false,
            isPendingSectionExpanded = false,
        )
        assertEquals(
            2,
            collapsed.filterIsInstance<FriendsListViewModel.Item.SuggestedFriend>().size,
        )

        val expanded = FriendsListViewModel.getVisibleItems(
            listSections,
            isSuggestedSectionExpanded = true,
            isPendingSectionExpanded = false,
        )
        assertEquals(
            3,
            expanded.filterIsInstance<FriendsListViewModel.Item.SuggestedFriend>().size,
        )
    }

    @Test
    fun `a section header is dropped when its body is empty`() {
        val empty = FriendsListViewModel.buildListSections(
            relationships = emptyMap(),
            users = emptyMap(),
            presences = emptyMap(),
            applicationStreams = emptyMap(),
            showContactSyncUpsell = false,
            friendSuggestions = emptyMap(),
        )
        assertNull(empty.pendingHeaderItem)
        assertNull(empty.suggestionsHeaderItem)
        assertTrue(
            FriendsListViewModel.getVisibleItems(empty, false, false).isEmpty(),
        )
    }

    private companion object {
        const val BLOCKED_USER_ID = 5L
    }

    @Test
    fun `the contact sync upsell is the first row when it is present`() {
        val withUpsell = FriendsListViewModel.buildListSections(
            relationships = mapOf(1L to ModelUserRelationship.TYPE_FRIEND),
            users = mapOf(1L to user(1, "Online")),
            presences = emptyMap(),
            applicationStreams = emptyMap(),
            showContactSyncUpsell = true,
            friendSuggestions = emptyMap(),
        )
        assertTrue(
            FriendsListViewModel.getVisibleItems(withUpsell, false, false).first()
                is FriendsListViewModel.Item.ContactSyncUpsell,
        )
    }
}

class BlockedUsersBuilderTest {
    @Test
    fun `blocked builder filters and sorts`() {
        val users = listOf(
            FriendRelationshipUi(FriendUserUi(2, "zed"), FriendRelationship.BLOCKED),
            FriendRelationshipUi(FriendUserUi(1, "Amy"), FriendRelationship.BLOCKED),
            FriendRelationshipUi(FriendUserUi(3, "Friend"), FriendRelationship.FRIEND),
        )
        val state = BlockedUsersBuilder.build(users) as BlockedUsersState.Loaded
        assertEquals(listOf(1L, 2L), state.users.map { it.user.id })
    }
}

class WidgetFriendsAddByIdTest {
    @Test
    fun `a username is only accepted with exactly four trailing digits`() {
        val tagged = WidgetFriendsAddById.extractUsernameAndDiscriminator("name#0042")
        assertEquals("name", tagged.username)
        assertEquals(42, tagged.discriminator)

        assertNull(WidgetFriendsAddById.extractUsernameAndDiscriminator("name").discriminator)
        assertNull(WidgetFriendsAddById.extractUsernameAndDiscriminator("name#12345").discriminator)
    }
}

class NearbyManagerTest {
    @Test
    fun `only well formed user payloads are parsed`() {
        assertEquals(123L, NearbyManager.parseUserId("u:123".toByteArray()))
        assertNull(NearbyManager.parseUserId("x:123".toByteArray()))
        assertNull(NearbyManager.parseUserId("u:not-a-number".toByteArray()))
    }
}
