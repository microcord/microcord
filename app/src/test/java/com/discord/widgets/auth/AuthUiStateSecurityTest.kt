package com.discord.widgets.auth

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AuthUiStateSecurityTest {
    @Test
    fun credentialsAreProcessLocalAndAbsentFromSerializedState() {
        val state = AuthUiState(
            route = AuthRoute.MFA,
            identifier = "safe-form-state",
        ).withCredentials(
            phoneToken = "secret-phone-token",
            ticket = "secret-mfa-ticket",
            resetToken = "secret-reset-token",
        )

        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(state) }
            output.toByteArray()
        }
        val serialized = String(bytes, Charsets.ISO_8859_1)
        assertFalse(serialized.contains("secret-phone-token"))
        assertFalse(serialized.contains("secret-mfa-ticket"))
        assertFalse(serialized.contains("secret-reset-token"))

        val inProcess = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as AuthUiState
        }
        assertEquals("secret-phone-token", inProcess.phoneToken)
        assertEquals("secret-mfa-ticket", inProcess.ticket)
        assertEquals("secret-reset-token", inProcess.resetToken)

        state.clearCredentials()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as AuthUiState
        }
        assertEquals(AuthRoute.MFA, restored.route)
        assertEquals("safe-form-state", restored.identifier)
        assertNull(restored.phoneToken)
        assertNull(restored.ticket)
        assertNull(restored.resetToken)
    }
}
