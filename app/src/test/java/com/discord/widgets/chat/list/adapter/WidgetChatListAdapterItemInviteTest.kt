package com.discord.widgets.chat.list.adapter

import com.discord.models.domain.ModelInvite
import com.discord.stores.StoreInstantInvites
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetChatListAdapterItemInviteTest {

    @Test
    fun `store outcomes preserve the reference card states`() {
        assertEquals(InviteCardState.LOADING, StoreInstantInvites.InviteState.Loading.inviteCardState())
        assertEquals(InviteCardState.LOADING, StoreInstantInvites.InviteState.LoadFailed.inviteCardState())
        assertEquals(InviteCardState.INVALID, StoreInstantInvites.InviteState.Invalid.inviteCardState())
        assertEquals(
            InviteCardState.RESOLVED,
            StoreInstantInvites.InviteState.Resolved(ModelInvite.createForTesting(null)).inviteCardState(),
        )
    }
}
