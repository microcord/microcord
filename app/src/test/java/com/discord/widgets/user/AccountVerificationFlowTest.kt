package com.discord.widgets.user

import com.discord.api.permission.Permission
import com.discord.models.guild.Guild
import com.discord.models.requiredaction.RequiredAction
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreUser
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase.Mode
import com.discord.widgets.user.phone.WidgetUserPhoneManage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parts of the account-verification cluster that are pure logic: the enum orderings that
 * travel through intents, the dismiss rule that decides whether a locked-out user may leave, the
 * prune dialog's state machine, and — most importantly — that no request body in these flows can
 * print a credential.
 */
class AccountVerificationFlowTest {

    // region enum wire shapes

    /**
     * [Mode] crosses an [android.content.Intent] as an ordinal, so the declaration order is part of
     * the contract, not a style choice. Verified against the enum's `<clinit>` in the reference DEX.
     */
    @Test
    fun `mode ordinals match the reference`() {
        assertEquals(0, Mode.UNFORCED.ordinal)
        assertEquals(1, Mode.FORCED.ordinal)
        assertEquals(2, Mode.NO_HISTORY_FROM_USER_SETTINGS.ordinal)
        assertEquals(3, Mode.values().size)
    }

    /** [WidgetUserPasswordVerify.Action] is put on the intent as a `Serializable` enum constant. */
    @Test
    fun `password verify action ordinals match the reference`() {
        assertEquals(0, WidgetUserPasswordVerify.Action.UpdateAccountInfo.ordinal)
        assertEquals(1, WidgetUserPasswordVerify.Action.RemovePhoneNumber.ordinal)
        assertEquals(2, WidgetUserPasswordVerify.Action.UpdatePhoneNumber.ordinal)
    }

    /** The `source` strings are an API contract; note DEFAULT is `update_phone`, not `default`. */
    @Test
    fun `phone manage sources use the reference wire values`() {
        assertEquals(
            listOf(
                "user_action_required",
                "user_settings_update",
                "guild_phone_required",
                "mfa_phone_update",
                "contact_sync",
                "update_phone",
            ),
            WidgetUserPhoneManage.Source.values().map { it.source },
        )
    }

    // endregion

    // region dismiss rule

    @Test
    fun `an unforced or settings-launched gate never traps the user`() {
        for (action in RequiredAction.values()) {
            assertTrue(
                WidgetUserAccountVerifyBase.computeShouldDismiss(
                    Mode.UNFORCED,
                    isMissingPhone = true,
                    isMissingEmailOrUnverified = true,
                    requiredAction = action,
                ),
            )
            assertTrue(
                WidgetUserAccountVerifyBase.computeShouldDismiss(
                    Mode.NO_HISTORY_FROM_USER_SETTINGS,
                    isMissingPhone = true,
                    isMissingEmailOrUnverified = true,
                    requiredAction = action,
                ),
            )
        }
    }

    /**
     * While a verified phone is demanded, an unverified e-mail alone is not what is standing in the
     * way — the screen must let go. This asymmetry is the reference's and is easy to "tidy" away.
     */
    @Test
    fun `a forced phone gate is held only by a missing phone`() {
        assertFalse(
            WidgetUserAccountVerifyBase.computeShouldDismiss(
                Mode.FORCED,
                isMissingPhone = false,
                isMissingEmailOrUnverified = true,
                requiredAction = RequiredAction.REQUIRE_VERIFIED_PHONE,
            ),
        )
        assertTrue(
            WidgetUserAccountVerifyBase.computeShouldDismiss(
                Mode.FORCED,
                isMissingPhone = true,
                isMissingEmailOrUnverified = false,
                requiredAction = RequiredAction.REQUIRE_VERIFIED_PHONE,
            ),
        )
    }

    @Test
    fun `a forced email gate is held by either missing factor`() {
        assertTrue(
            WidgetUserAccountVerifyBase.computeShouldDismiss(
                Mode.FORCED,
                isMissingPhone = true,
                isMissingEmailOrUnverified = false,
                requiredAction = RequiredAction.REQUIRE_VERIFIED_EMAIL,
            ),
        )
        assertTrue(
            WidgetUserAccountVerifyBase.computeShouldDismiss(
                Mode.FORCED,
                isMissingPhone = false,
                isMissingEmailOrUnverified = true,
                requiredAction = RequiredAction.REQUIRE_VERIFIED_EMAIL,
            ),
        )
        assertFalse(
            WidgetUserAccountVerifyBase.computeShouldDismiss(
                Mode.FORCED,
                isMissingPhone = false,
                isMissingEmailOrUnverified = false,
                requiredAction = RequiredAction.REQUIRE_VERIFIED_EMAIL,
            ),
        )
    }

    /** A captcha requirement is not cleared by this screen, so it must not hold it open. */
    @Test
    fun `a forced gate releases for an unrelated required action`() {
        assertFalse(
            WidgetUserAccountVerifyBase.computeShouldDismiss(
                Mode.FORCED,
                isMissingPhone = true,
                isMissingEmailOrUnverified = true,
                requiredAction = RequiredAction.REQUIRE_CAPTCHA,
            ),
        )
    }

    // endregion

    // region prune dialog state

    @Test
    fun `prune day windows are the values the api takes`() {
        assertEquals(7, WidgetPruneUsersViewModel.PruneDays.Seven.count)
        assertEquals(30, WidgetPruneUsersViewModel.PruneDays.Thirty.count)
    }

    @Test
    fun `prune is not offered for zero members or while a prune is running`() {
        val loaded = WidgetPruneUsersViewModel.ViewState.Loaded(
            WidgetPruneUsersViewModel.PruneDays.Thirty,
            pruneCount = 0,
        )
        assertFalse(loaded.pruneButtonEnabled)

        val withMembers = WidgetPruneUsersViewModel.ViewState.Loaded(
            WidgetPruneUsersViewModel.PruneDays.Thirty,
            pruneCount = 4,
        )
        assertTrue(withMembers.pruneButtonEnabled)
        assertFalse(withMembers.withPruneInProgress(true).pruneButtonEnabled)
        assertTrue(withMembers.withPruneInProgress(true).withPruneInProgress(false).pruneButtonEnabled)
    }

    @Test
    fun `prune requires kick permission, and mfa when the guild demands it`() {
        val me = StoreUser.EMPTY_ME_USER
        val plainGuild = Guild(mfaLevel = 0)
        val mfaGuild = Guild(mfaLevel = 1)

        assertFalse(
            "no permissions loaded yet",
            WidgetPruneUsersViewModel.StoreData(null, plainGuild, me).canPrune,
        )
        assertFalse(
            "no guild loaded yet",
            WidgetPruneUsersViewModel.StoreData(Permission.KICK_MEMBERS, null, me).canPrune,
        )
        assertFalse(
            "lacks kick",
            WidgetPruneUsersViewModel.StoreData(0L, plainGuild, me).canPrune,
        )
        assertTrue(
            WidgetPruneUsersViewModel.StoreData(Permission.KICK_MEMBERS, plainGuild, me).canPrune,
        )
        assertFalse(
            "guild requires mfa and the user has none",
            WidgetPruneUsersViewModel.StoreData(Permission.KICK_MEMBERS, mfaGuild, me).canPrune,
        )
        assertTrue(
            WidgetPruneUsersViewModel.StoreData(
                Permission.KICK_MEMBERS,
                mfaGuild,
                me.copy(mfaEnabled = true),
            ).canPrune,
        )
    }

    // endregion

    // region secrets

    /**
     * Project contract: no password, verification code, single-use token or phone number may reach
     * a log, a crash report or a saved bundle. Every body these screens send is rendered by its
     * `toString` in error paths, so each one is pinned here.
     */
    @Test
    fun `no request body in these flows prints a credential`() {
        val secrets = listOf("hunter2", "123456", "phone-token-abc", "+15551234567")

        val bodies = listOf(
            RestAPIParams.UserInfo.createForEmail("wumpus@example.com", "hunter2"),
            RestAPIParams.DeletePhone("hunter2"),
            RestAPIParams.VerificationPhoneCode("phone-token-abc", "hunter2", "update_phone"),
            RestAPIParams.VerificationCode("+15551234567", "123456"),
            RestAPIParams.Phone("+15551234567", "update_phone"),
        )

        for (body in bodies) {
            val rendered = body.toString()
            for (secret in secrets) {
                assertFalse("$rendered leaked $secret", rendered.contains(secret))
            }
        }
    }

    /** The non-secret `source` is deliberately kept so a failed request is still diagnosable. */
    @Test
    fun `phone bodies keep their non-secret source`() {
        assertTrue(
            RestAPIParams.Phone("+15551234567", "update_phone")
                .toString()
                .contains("update_phone"),
        )
        assertTrue(
            RestAPIParams.VerificationPhoneCode("token", "hunter2", "update_phone")
                .toString()
                .contains("update_phone"),
        )
    }

    /** The e-mail screen renders the address, but the account record it comes from redacts it. */
    @Test
    fun `the account record redacts its credentials`() {
        val rendered = StoreUser.EMPTY_ME_USER
            .copy(email = "wumpus@example.com", token = "mfa.secret", phoneNumber = "+15551234567")
            .toString()
        assertFalse(rendered, rendered.contains("wumpus@example.com"))
        assertFalse(rendered, rendered.contains("mfa.secret"))
        assertFalse(rendered, rendered.contains("+15551234567"))
    }

    // endregion
}
