package com.discord.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun onlyDedicatedFcmProcessSkipsTheAppGraph() {
        assertTrue(isFcmProcess("dev.mantikafasi.manticord:fcm", "dev.mantikafasi.manticord"))
        assertFalse(isFcmProcess("dev.mantikafasi.manticord", "dev.mantikafasi.manticord"))
        assertFalse(isFcmProcess(null, "dev.mantikafasi.manticord"))
    }
}
