package com.discord.widgets.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsUiTest {
    @Test fun `builder filters blocked and orders collapsed sections`() {
        fun user(id: Long, name: String) = FriendUserUi(id, name)
        val state = FriendsListBuilder.build(
            FriendsListInput(
                relationships = listOf(
                    FriendRelationshipUi(user(5, "Blocked"), FriendRelationship.BLOCKED),
                    FriendRelationshipUi(user(4, "Zed"), FriendRelationship.OUTGOING),
                    FriendRelationshipUi(user(3, "Amy"), FriendRelationship.INCOMING),
                    FriendRelationshipUi(user(2, "Offline"), FriendRelationship.FRIEND),
                    FriendRelationshipUi(user(1, "Online"), FriendRelationship.FRIEND, FriendPresence.ONLINE),
                ),
                suggestions = listOf(7L, 6L, 8L).map { FriendSuggestionUi(user(it, "S$it")) },
            ),
        ) as FriendsListState.Loaded

        assertEquals(listOf(6L, 7L), state.rows.filterIsInstance<FriendRow.Suggestion>().map { it.value.user.id })
        assertEquals(listOf(3L, 4L), state.rows.filterIsInstance<FriendRow.Pending>().map { it.value.user.id })
        assertEquals(listOf(1L, 2L), state.rows.filterIsInstance<FriendRow.Friend>().map { it.value.user.id })
        assertTrue(state.rows.none { it.stableId == 5L shl 3 })
    }

    @Test fun `username tag requires exactly four digits`() {
        assertEquals(UsernameTag("name", 42), FriendUsernameValidator.parse("name#0042"))
        assertNull(FriendUsernameValidator.parse("name"))
        assertNull(FriendUsernameValidator.parse("name#12345"))
    }

    @Test fun `blocked builder filters and sorts`() {
        val users = listOf(
            FriendRelationshipUi(FriendUserUi(2, "zed"), FriendRelationship.BLOCKED),
            FriendRelationshipUi(FriendUserUi(1, "Amy"), FriendRelationship.BLOCKED),
            FriendRelationshipUi(FriendUserUi(3, "Friend"), FriendRelationship.FRIEND),
        )
        val state = BlockedUsersBuilder.build(users) as BlockedUsersState.Loaded
        assertEquals(listOf(1L, 2L), state.users.map { it.user.id })
    }
}
