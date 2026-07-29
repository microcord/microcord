package com.discord.widgets.status

import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Observable

class WidgetForumPostStatusViewModelTest {
    @Test
    fun `loaded store state maps every render field`() {
        val reaction = MessageReaction(3, MessageReactionEmoji(null, "heart", false), true)
        val viewModel = WidgetForumPostStatusViewModel(Observable.never())

        viewModel.handleStoreState(
            WidgetForumPostStatusViewModel.StoreState.Loaded(
                myUserId = 1,
                channelId = 2,
                guildId = 3,
                messageId = 4,
                isFollowedThread = true,
                isArchivedThread = false,
                messageCount = null,
                hasAddReactionPermission = true,
                isDefaultReaction = true,
                mostCommonReaction = reaction,
            ),
        )

        val state = viewModel.requireViewState() as WidgetForumPostStatusViewModel.ViewState.Valid
        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(state.myUserId, state.channelId, state.guildId, state.messageId))
        assertTrue(state.isFollowedThread)
        assertFalse(state.isArchivedThread)
        assertEquals(0, state.messageCount)
        assertTrue(state.hasAddReactionPermission)
        assertTrue(state.isDefaultReaction)
        assertEquals(reaction, state.reaction)
        assertFalse(state.followRequestInProgress)
    }

    @Test
    fun `invalid store state hides the status`() {
        val viewModel = WidgetForumPostStatusViewModel(Observable.never())
        viewModel.handleStoreState(WidgetForumPostStatusViewModel.StoreState.Invalid)
        assertEquals(WidgetForumPostStatusViewModel.ViewState.Invalid, viewModel.requireViewState())
    }
}
