package com.discord.utilities.experiments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentRegistryTest {
    @Test
    fun `registry contains the still-gated experiments`() {
        assertEquals(11, ExperimentRegistry.registeredExperiments.size)
        assertTrue("2021-10_study_group" in ExperimentRegistry.registeredExperiments)
        assertEquals(
            RegisteredExperiment.Type.USER,
            ExperimentRegistry.registeredExperiments.getValue("2021-05_opensl_default_enable_android").type,
        )
        assertEquals(
            listOf("Control: Standard message gestures", "Treatment 1: Double tap to reply or edit"),
            ExperimentRegistry.registeredExperiments.getValue("manticord_local_taptap").buckets,
        )
    }
}
