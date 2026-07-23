package com.discord.utilities.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUtilsTest {
    @Test
    fun adaptiveDetectionMatchesPhoneFragments() {
        assertFalse(PhoneUtils.isLikelyToContainPhoneNumber("12"))
        assertTrue(PhoneUtils.isLikelyToContainPhoneNumber("(555) 12-34"))
        assertFalse(PhoneUtils.isLikelyToContainPhoneNumber("555@example.com"))
    }
}
