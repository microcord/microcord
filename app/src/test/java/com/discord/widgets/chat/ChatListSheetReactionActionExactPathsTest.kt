package com.discord.widgets.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Modifier

class ChatListSheetReactionActionExactPathsTest {
    @Test
    fun `126 21 sheet reaction and action classes keep their exact paths`() {
        listOf(
            "com.discord.widgets.chat.list.sheet.WidgetApplicationCommandBottomSheet",
            "com.discord.widgets.chat.list.sheet.WidgetApplicationCommandBottomSheetKt",
            "com.discord.widgets.chat.list.sheet.WidgetApplicationCommandBottomSheetViewModel",
            "com.discord.widgets.chat.list.sheet.WidgetApplicationCommandBottomSheetViewModelKt",
            "com.discord.widgets.chat.managereactions.ManageReactionsEmojisAdapter",
            "com.discord.widgets.chat.managereactions.ManageReactionsModel",
            "com.discord.widgets.chat.managereactions.ManageReactionsModelProvider",
            "com.discord.widgets.chat.managereactions.ManageReactionsResultsAdapter",
            "com.discord.widgets.chat.managereactions.WidgetManageReactions",
            "com.discord.widgets.chat.list.actions.EmojiItem",
            "com.discord.widgets.chat.list.actions.EmojiViewHolder",
            "com.discord.widgets.chat.list.actions.MessageActionDialogs",
            "com.discord.widgets.chat.list.actions.MoreEmojisViewHolder",
            "com.discord.widgets.chat.list.actions.WidgetChatListActions",
            "com.discord.widgets.chat.list.actions.WidgetChatListActionsEmojisAdapter",
        ).forEach { assertNotNull(Class.forName(it)) }
    }

    @Test
    fun `sheet facades expose the reference top level functions`() {
        val copyMethods = publicMethods(
            "com.discord.widgets.chat.list.sheet.WidgetApplicationCommandBottomSheetKt",
            "toSlashCommandCopyString",
        )
        val viewModelFacade = Class.forName(
            "com.discord.widgets.chat.list.sheet.WidgetApplicationCommandBottomSheetViewModelKt",
        )

        assertEquals(2, copyMethods.size)
        assertEquals(2, viewModelFacade.declaredMethods.count { it.name == "flattenOptions" })
        assertEquals(1, viewModelFacade.declaredMethods.count { it.name == "toStringTruncateZeroDecimal" })
    }

    private fun publicMethods(className: String, name: String) = Class.forName(className)
        .declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && it.name == name }
}
