package com.discord.utilities.experiments

data class RegisteredExperiment(
    val readableName: String,
    val name: String,
    val type: Type,
    val buckets: List<String>,
    val cacheExperiment: Boolean = true,
) {
    enum class Type { GUILD, USER }
}

/** The experiments the developer settings screen can override; every entry here still gates code. */
object ExperimentRegistry {
    val registeredExperiments: LinkedHashMap<String, RegisteredExperiment> = listOf(
        user("Compact Invite Widget", "2020-01_mobile_invite_suggestion_compact", "Control", "Treatment 1: Use compact view"),
        user("Stop Offscreen Video Streams", "2021-03_stop_offscreen_video_streams", "Control", "Treatment 1: Stop offscreen video streams"),
        user("Enabled Discord Hub Study Groups", "2021-10_study_group", "Control", "Treatment 1: Hub Study Groups Enabled"),
    ).associateByTo(linkedMapOf(), RegisteredExperiment::name)

    private fun user(readableName: String, name: String, vararg buckets: String) =
        RegisteredExperiment(readableName, name, RegisteredExperiment.Type.USER, buckets.toList())
}
