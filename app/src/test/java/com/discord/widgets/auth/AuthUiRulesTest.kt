package com.discord.widgets.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUiRulesTest {
    @Test
    fun validationAndRequiredActionRoutesMatchAuthFlow() {
        assertEquals(setOf("identifier", "password"), AuthUiRules.login("", "").keys)
        assertEquals(setOf("token"), AuthUiRules.token(" ").keys)
        assertTrue(AuthUiRules.token("token").isEmpty())
        assertTrue(AuthUiRules.identity(AuthIdentityMode.EMAIL, "user@example.com").isEmpty())
        assertTrue(AuthUiRules.identity(AuthIdentityMode.PHONE, "+1 555 123 4567").isEmpty())
        assertEquals(AuthRoute.EMAIL_VERIFICATION, AuthUiRules.routeFor(AuthRequiredAction.VERIFY_EMAIL))
        assertEquals(AuthRoute.PHONE_VERIFICATION, AuthUiRules.routeFor(AuthRequiredAction.VERIFY_PHONE))
        assertEquals(AuthRoute.FORCE_PASSWORD, AuthUiRules.routeFor(AuthRequiredAction.UPDATE_PASSWORD))
        assertEquals("VerifyMfa(ticket=<redacted>, code=<redacted>)", AuthAction.VerifyMfa("ticket", "123456").toString())
        assertEquals("TokenLogin(token=<redacted>)", AuthAction.TokenLogin("secret").toString())
        assertEquals("Server", (AuthLandingContent.GuildInvite(1, "Server", null, null, 500)).name)
    }

    @Test
    fun verifiedPhoneContinuesRegistrationOrPasswordRecoveryWithoutLeakingTokens() {
        val registration = AuthRequests.verifiedPhoneResult(
            AuthPhonePurpose.REGISTRATION,
            "+15551234567",
            "phone-token",
        )
        assertEquals(AuthRoute.REGISTER_ACCOUNT, registration.route)
        assertEquals("phone-token", registration.state.phoneToken)

        val recovery = AuthRequests.verifiedPhoneResult(
            AuthPhonePurpose.PASSWORD_RESET,
            "+15551234567",
            "reset-token",
        )
        assertEquals(AuthRoute.RESET_PASSWORD, recovery.route)
        assertEquals("reset-token", recovery.state.resetToken)
        assertFalse(registration.state.toString().contains("phone-token"))
        assertFalse(recovery.state.toString().contains("reset-token"))
        assertEquals(
            "VerifyPhone(phone=<redacted>, code=<redacted>, purpose=LOGIN)",
            AuthAction.VerifyPhone("+15551234567", "123456", AuthPhonePurpose.LOGIN).toString(),
        )
    }
}
