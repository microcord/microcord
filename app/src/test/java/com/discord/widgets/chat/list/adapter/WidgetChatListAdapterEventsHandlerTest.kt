package com.discord.widgets.chat.list.adapter

import com.discord.api.botuikit.ComponentType
import com.discord.api.user.User
import com.discord.models.message.Message
import com.discord.models.member.GuildMember
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.restapi.RestAPIParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChatListAdapterEventsHandlerTest {
    @Test fun disabledServerProfilesUseAccountAuthorIdentity() {
        val author = User(8L, "account", globalName = "Account Name")
        val message = Message(id = 1L, author = author, type = 0)
        val member = GuildMember(0, 0, null, "Server Name", null, false, null, 1L, 8L,
            avatarHash = "server-avatar", bannerHash = "server-banner")
        val entry = MessageEntry(message, null, member, roles = null,
            nickOrUsernames = mapOf(8L to "Server Name"), isExpandedBlocked = false,
            permissionsForChannel = null)

        assertEquals("Server Name", entry.authorDisplayName(guildIdentityEnabled = true))
        assertEquals("Account Name", entry.authorDisplayName(guildIdentityEnabled = false))
        assertEquals(member, chatIdentityMember(member, disableServerProfiles = false))
        assertNull(chatIdentityMember(member, disableServerProfiles = true))
    }

    @Test fun componentInteractionDataMatchesButtonAndSelectPayloads() {
        assertNull(WidgetChatListAdapterEventsHandler.componentInteractionData(null, null, ComponentType.BUTTON))

        assertEquals(
            RestAPIParams.ComponentInteractionData.ButtonComponentInteractionData(
                ComponentType.BUTTON,
                "confirm",
            ),
            WidgetChatListAdapterEventsHandler.componentInteractionData("confirm", null, ComponentType.BUTTON),
        )
        assertEquals(
            RestAPIParams.ComponentInteractionData.SelectComponentInteractionData(
                ComponentType.SELECT,
                "roles",
                listOf("one", "two"),
            ),
            WidgetChatListAdapterEventsHandler.componentInteractionData(
                "roles",
                listOf("one", "two"),
                ComponentType.SELECT,
            ),
        )
    }

    @Test fun tapTapIsOffByDefaultAndTreatmentUsesPluginActions() {
        val own = Message(id = 1, author = User(7, "me"), type = 0)
        val other = Message(id = 2, author = User(8, "other"), type = 0)

        assertEquals(TapTapBehavior.Action.EDIT, TapTapBehavior.action(own, 7))
        assertEquals(TapTapBehavior.Action.REPLY, TapTapBehavior.action(other, 7))
        assertEquals(TapTapBehavior.Action.REPLY, TapTapBehavior.action(own, 7, replyToOwn = true))
        assertEquals(200L, TapTapBehavior.TIMEOUT_MS)
    }

    @Test fun tapTapLeavesNonReplyableMessageGesturesAlone() {
        assertFalse(TapTapBehavior.canHandle(Message(id = 1, type = -1)))
        assertFalse(TapTapBehavior.canHandle(Message(id = 2, type = -3)))
        assertFalse(TapTapBehavior.canHandle(Message(id = 3, type = 0, flags = 64)))
        assertFalse(TapTapBehavior.canHandle(Message(id = 4, type = 0, flags = 128)))
        assertTrue(TapTapBehavior.canHandle(Message(id = 5, type = 0)))
    }

    @Test fun quickActionsOnlyOpenForStableOrdinaryRows() {
        assertTrue(QuickMessageActionsBehavior.canShow(Message(id = 1, type = 0), false, false))
        assertFalse(QuickMessageActionsBehavior.canShow(Message(id = 2, type = -1), false, false))
        assertFalse(QuickMessageActionsBehavior.canShow(Message(id = 3, type = -3), false, false))
        assertFalse(QuickMessageActionsBehavior.canShow(Message(id = 4, type = 0, flags = 64), false, false))
        assertFalse(QuickMessageActionsBehavior.canShow(Message(id = 5, type = 0), true, false))
        assertFalse(QuickMessageActionsBehavior.canShow(Message(id = 6, type = 0), false, true))
    }
}
