package com.discord.widgets.chat.list.model

import com.discord.stores.StoreMessagesLoader
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.entries.SpacerEntry
import com.discord.widgets.chat.list.entries.StartOfChatEntry
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChatListModelTopTest {
    private val welcome = StartOfChatEntry(
        1, "general", true, false, false, false, null, null, "", false, false,
        false, null, null,
    )

    @Test fun oldestLoadedUsesWelcomeEntry() {
        val state = StoreMessagesLoader.ChannelLoadedState(isOldestMessagesLoaded = true)
        assertTrue(WidgetChatListModelTop.topEntry(1, state, welcome) === welcome)
    }

    @Test fun completedJumpWindowUsesSpacerOtherwiseLoading() {
        val spacer = WidgetChatListModelTop.topEntry(
            1,
            StoreMessagesLoader.ChannelLoadedState(isInitialMessagesLoaded = true),
            welcome,
        )
        val loading = WidgetChatListModelTop.topEntry(
            1,
            StoreMessagesLoader.ChannelLoadedState(
                isInitialMessagesLoaded = true,
                isTouchedSinceLastJump = true,
            ),
            welcome,
        )

        assertTrue(spacer is SpacerEntry)
        assertTrue(loading is LoadingEntry)
    }
}
