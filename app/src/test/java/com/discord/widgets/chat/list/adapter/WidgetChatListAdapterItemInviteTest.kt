package com.discord.widgets.chat.list.adapter

import com.discord.models.domain.ModelInvite
import com.discord.stores.StoreInstantInvites
import com.discord.widgets.guilds.invite.requiresApplication
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `future expiry remains resolvable`() {
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val future = gson.fromJson("""{"code":"valid","expires_at":"2099-01-01T00:00:00+00:00"}""", ModelInvite::class.java)
        val expired = gson.fromJson("""{"code":"expired","expires_at":"2020-01-01T00:00:00+00:00"}""", ModelInvite::class.java)
        val application = gson.fromJson(
            """{"guild":{"id":"1","name":"Guild","features":["MEMBER_VERIFICATION_MANUAL_APPROVAL"]}}""",
            ModelInvite::class.java,
        )

        assertTrue(future.isResolvableAt(2_000_000_000_000L))
        assertFalse(expired.isResolvableAt(2_000_000_000_000L))
        assertTrue(application.requiresApplication())
        assertFalse(future.requiresApplication())
    }
}
