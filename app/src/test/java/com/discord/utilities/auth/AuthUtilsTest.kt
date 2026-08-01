package com.discord.utilities.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUtilsTest {

    @Test fun `password length is inclusive from 6 through 128`() {
        assertFalse(AuthUtils.isValidPasswordLength("a".repeat(5)))
        assertTrue(AuthUtils.isValidPasswordLength("a".repeat(6)))
        assertTrue(AuthUtils.isValidPasswordLength("a".repeat(128)))
        assertFalse(AuthUtils.isValidPasswordLength("a".repeat(129)))
    }

    @Test fun `discriminator distinguishes format from non-positive values`() {
        assertEquals(AuthUtils.DiscriminatorError.FORMAT, AuthUtils.discriminatorError("123"))
        assertEquals(AuthUtils.DiscriminatorError.FORMAT, AuthUtils.discriminatorError("12a4"))
        assertEquals(AuthUtils.DiscriminatorError.VALUE, AuthUtils.discriminatorError("0000"))
        assertEquals(AuthUtils.DiscriminatorError.VALUE, AuthUtils.discriminatorError("-001"))
        assertNull(AuthUtils.discriminatorError("0001"))
        assertNull(AuthUtils.discriminatorError("9999"))
    }
}
