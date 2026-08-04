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

/** The experiments the developer settings screen can override. */
object ExperimentRegistry {
    val registeredExperiments: LinkedHashMap<String, RegisteredExperiment> = listOf(
        user("TapTap", "manticord_local_taptap", "Control: Standard message gestures", "Treatment 1: Double tap to reply or edit"),
        user("Compact Invite Widget", "2020-01_mobile_invite_suggestion_compact", "Control", "Treatment 1: Use compact view"),
        user("Disable mentions in landscape", "2020-12_android_disable_landscape_mentions", "Control", "Treatment 1: Hide mentions in landscape"),
        user("Stop Offscreen Video Streams", "2021-03_stop_offscreen_video_streams", "Control", "Treatment 1: Stop offscreen video streams"),
        user("Enabled Discord Hub Study Groups", "2021-10_study_group", "Control", "Treatment 1: Hub Study Groups Enabled"),
        user("Enable Impression Logging", "2021-08_impression_logging_enabled_android", "Control", "Treatment 1: Enable Impression Logging"),
        user("AudioManager V2 and OpenSL ES", "2021-05_opensl_default_enable_android", "Control", "Treatment 1: Default enable OpenSL", "Treatment 2: Use StoreAudioManagerV2 for output routing"),
        user("Phone Registration - Bailout to Email", "2021-06_reg_bailout_to_email_android", "Control: Just the back button", "Treatment 1: Static button to bailout to the email tab"),
        user("Enable Network Action Logging", "2021-07_network_action_logging_android", "Off: No Action logging", "On: Enable Action Logging"),
        user("Preview Promotions", "2021-06_preview_promotions", "Off: Hit normal endpoint", "On: Hit preview endpoint"),
        user("Route audio to speakerphone by default", "2021-08_android_speakerphone_default", "Control", "Treatment 1: Always default to speakerphone"),
    ).associateByTo(linkedMapOf(), RegisteredExperiment::name)

    private fun user(readableName: String, name: String, vararg buckets: String) =
        RegisteredExperiment(readableName, name, RegisteredExperiment.Type.USER, buckets.toList())
}
