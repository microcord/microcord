package com.discord

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationConfigTest {
    @Test
    fun usesManticordApplicationId() {
        assertEquals("dev.mantikafasi.manticord", BuildConfig.APPLICATION_ID)
    }
}
