package com.discord.utilities.experiments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentRegistryTest {
    @Test
    fun `registry contains the shipped user and guild experiments`() {
        assertEquals(37, ExperimentRegistry.registeredExperiments.size)
        assertTrue("2021-10_premium_guild_member_profiles" in ExperimentRegistry.registeredExperiments)
        assertEquals(
            RegisteredExperiment.Type.GUILD,
            ExperimentRegistry.registeredExperiments.getValue("2022-03_android_forum_channel_redesign").type,
        )
    }
}
