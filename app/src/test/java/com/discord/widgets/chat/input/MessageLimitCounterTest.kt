package com.discord.widgets.chat.input

import com.discord.api.premium.PremiumTier
import com.discord.utilities.messagesend.MessageSendCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageLimitCounterTest {
    @Test
    fun counterUsesAccountMessageLimit() {
        assertEquals("300/2000", messageLimitText(300, MessageSendCoordinator.characterLimit(PremiumTier.NONE)))
        assertEquals("1299/4000", messageLimitText(1299, MessageSendCoordinator.characterLimit(PremiumTier.TIER_2)))
    }

    @Test
    fun counterStateReplaysToOverlay() {
        val viewModel = AppFlexInputViewModel()
        val state = AppFlexInputViewModel.MessageLimitCounter("42/2000", visible = true)

        viewModel.updateMessageLimitCounter(state)

        assertEquals(state, viewModel.observeMessageLimitCounter().toBlocking().first())
    }
}
