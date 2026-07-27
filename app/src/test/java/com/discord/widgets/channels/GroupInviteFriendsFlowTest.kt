package com.discord.widgets.channels

import com.discord.api.channel.Channel
import com.discord.models.user.CoreUser
import com.discord.widgets.channels.invite.GroupInviteFriendsSheetAdapter
import com.discord.widgets.channels.invite.GroupInviteFriendsSheetViewModel
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteFriendsFlowTest {
    private val amy = CoreUser(id = 1L, username = "Amy")
    private val bob = CoreUser(id = 2L, username = "Bob")

    @Test
    fun `legacy screen chooses full empty and friend list states`() {
        assertEquals(
            WidgetGroupInviteFriends.VIEW_INDEX_GROUP_FULL,
            WidgetGroupInviteFriends.getChildToDisplay(10, 10, emptyList()),
        )
        assertEquals(
            WidgetGroupInviteFriends.VIEW_INDEX_NO_FRIENDS,
            WidgetGroupInviteFriends.getChildToDisplay(10, 2, emptyList()),
        )
        assertEquals(
            WidgetGroupInviteFriends.VIEW_INDEX_FRIENDS_LIST,
            WidgetGroupInviteFriends.getChildToDisplay(
                10,
                2,
                listOf(WidgetGroupInviteFriends.Model.FriendItem(amy)),
            ),
        )
    }

    @Test
    fun `sheet rows mirror checked users`() {
        val rows = GroupInviteFriendsSheetAdapter.FriendItem.createItems(listOf(amy, bob), setOf(bob))

        assertFalse(rows[0].isSelected)
        assertTrue(rows[1].isSelected)
        assertEquals(listOf("1", "2"), rows.map { it.key })
    }

    @Test
    fun `friends already in the group are excluded`() {
        val channel = Gson().fromJson(
            """{"id":"9","type":3,"recipients":[{"id":"1","username":"Amy","discriminator":"0001"}]}""",
            Channel::class.java,
        )

        assertEquals(
            listOf(bob),
            GroupInviteFriendsSheetViewModel.excludeUsersAlreadyInChannel(
                linkedMapOf(amy.id to amy, bob.id to bob),
                channel,
            ),
        )
    }

    @Test
    fun `recipient total includes self existing recipients and new selections`() {
        val channel = Gson().fromJson(
            """{"id":"9","type":3,"recipients":[{"id":"1","username":"Amy","discriminator":"0001"}]}""",
            Channel::class.java,
        )
        val model = WidgetGroupInviteFriends.Model(
            channel = channel,
            selectedUsers = listOf(bob),
            potentialAdditions = emptyList(),
            maxGroupMemberCount = 10,
        )

        assertEquals(3, model.totalNumRecipients)
    }
}
