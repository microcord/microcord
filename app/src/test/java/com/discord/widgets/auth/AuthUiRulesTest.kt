package com.discord.widgets.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUiRulesTest {
    @Test
    fun validationAndRequiredActionRoutesMatchAuthFlow() {
        assertEquals(setOf("identifier", "password"), AuthUiRules.login("", "").keys)
        assertTrue(AuthUiRules.identity(AuthIdentityMode.EMAIL, "user@example.com").isEmpty())
        assertTrue(AuthUiRules.identity(AuthIdentityMode.PHONE, "+1 555 123 4567").isEmpty())
        assertEquals(AuthRoute.EMAIL_VERIFICATION, AuthUiRules.routeFor(AuthRequiredAction.VERIFY_EMAIL))
        assertEquals(AuthRoute.PHONE_VERIFICATION, AuthUiRules.routeFor(AuthRequiredAction.VERIFY_PHONE))
        assertEquals(AuthRoute.FORCE_PASSWORD, AuthUiRules.routeFor(AuthRequiredAction.UPDATE_PASSWORD))
        assertEquals("VerifyMfa(ticket=<redacted>, code=<redacted>)", AuthAction.VerifyMfa("ticket", "123456").toString())
        assertEquals("Server", (AuthLandingContent.GuildInvite(1, "Server", null, null, 500)).name)
    }
}
