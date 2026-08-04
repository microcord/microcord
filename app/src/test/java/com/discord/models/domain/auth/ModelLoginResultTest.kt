package com.discord.models.domain.auth

import com.discord.api.auth.mfa.MfaMethodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLoginResultTest {

    @Test
    fun `flags become methods in the order the reference presents them`() {
        val challenge = result(totp = true, sms = true, backup = true, webauthn = "webauthn-challenge").mfaChallenge()

        assertEquals("mfa-ticket", challenge?.ticket)
        assertEquals(
            listOf(MfaMethodType.WEBAUTHN, MfaMethodType.TOTP, MfaMethodType.BACKUP, MfaMethodType.SMS),
            challenge?.methods?.map { it.type },
        )
    }

    @Test
    fun `backup doubles as backup_codes_allowed on the totp prompt`() {
        val withBackup = result(totp = true, backup = true).mfaChallenge()
        assertTrue(withBackup!!.methods.first { it.type == MfaMethodType.TOTP }.backupCodesAllowed)

        val withoutBackup = result(totp = true).mfaChallenge()
        assertFalse(withoutBackup!!.methods.single().backupCodesAllowed)
    }

    @Test
    fun `a blank webauthn challenge is not a method`() {
        val challenge = result(totp = true, webauthn = "  ").mfaChallenge()

        assertEquals(listOf(MfaMethodType.TOTP), challenge?.methods?.map { it.type })
    }

    @Test
    fun `no ticket or no method is not a challenge`() {
        assertNull(result(totp = true, ticket = null).mfaChallenge())
        assertNull(result(ticket = "mfa-ticket").mfaChallenge())
    }

    @Test
    fun `rendering leaks no credential`() {
        val rendered = result(totp = true).copy(token = "token-secret", suspendedUserToken = "suspended-secret").toString()

        assertFalse(rendered.contains("mfa-ticket"))
        assertFalse(rendered.contains("token-secret"))
        assertFalse(rendered.contains("suspended-secret"))
    }

    private fun result(
        ticket: String? = "mfa-ticket",
        totp: Boolean = false,
        sms: Boolean = false,
        backup: Boolean = false,
        webauthn: String? = null,
    ) = ModelLoginResult(
        mfa = true,
        ticket = ticket,
        token = null,
        userSettings = null,
        requiredActions = null,
        totp = totp,
        sms = sms,
        backup = backup,
        webauthn = webauthn,
    )
}
