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
        user("Guild Delete Feedback", "2020-12_guild_delete_feedback", "Control: Do not show the feedback modal", "Treatment 1: Show the feedback modal"),
        user("Disable Camera 2", "2021-02_android_webrtc_camera2", "Control: Use Camera 2 API if supported", "Treatment 1: Force Camera 1 API"),
        user("MediaSinkWants", "2021-03_android_media_sink_wants", "Control: disabled", "Treatment 1: use MediaSinkWants"),
        user("Attachments Bottom Sheet", "2021-10_android_attachment_bottom_sheet", "Control", "Treatment 1: Show attachments bottom sheet"),
        user("Contact Sync: Base Experiment", "2021-04_contact_sync_android_main", "Control", "Treatment 1: Enable core Contact Sync features"),
        user("Stop Offscreen Video Streams", "2021-03_stop_offscreen_video_streams", "Control", "Treatment 1: Stop offscreen video streams"),
        user("Hub Multiple Domains", "2021-08_hub_multi_domain_mobile", "Control", "Treatment 1: Enable multiple domains for hubs"),
        user("Enabled Discord Hub Directories", "2021-06_desktop_school_hubs", "Control", "Treatment 1: See Discord Hub Directories"),
        user("Enabled Discord Hub Emails", "2021-06_hub_email_connection", "Control", "Treatment 1: See Discord Hub Emails"),
        user("Enabled Discord Hub Study Groups", "2021-10_study_group", "Control", "Treatment 1: Hub Study Groups Enabled"),
        user("Enabled Hub Guild Events", "2021-11_hub_events", "Control", "Treatment 1: Enabled hub Guild events"),
        user("Enable Impression Logging", "2021-08_impression_logging_enabled_android", "Control", "Treatment 1: Enable Impression Logging"),
        user("AudioManager V2 and OpenSL ES", "2021-05_opensl_default_enable_android", "Control", "Treatment 1: Default enable OpenSL", "Treatment 2: Use StoreAudioManagerV2 for output routing"),
        user("Phone Registration - Bailout to Email", "2021-06_reg_bailout_to_email_android", "Control: Just the back button", "Treatment 1: Static button to bailout to the email tab"),
        user("Enable Network Action Logging", "2021-07_network_action_logging_android", "Off: No Action logging", "On: Enable Action Logging"),
        user("Preview Promotions", "2021-06_preview_promotions", "Off: Hit normal endpoint", "On: Hit preview endpoint"),
        user("Route audio to speakerphone by default", "2021-08_android_speakerphone_default", "Control", "Treatment 1: Always default to speakerphone"),
        user("SMS Autofill", "2021-09_android_sms_autofill", "Control: No SMS Autofill", "Treatment 1: SMS Code autofills"),
        user("Playstation Connected Accounts", "2021-12_connected_accounts_playstation", "Control: User can not link and see linked playstation accounts and can not be shown upsells", "Treatment 1: User can link and see linked playstation accounts and can be shown upsells", "Treatment 2: User can link and see linked playstation accounts but can not be shown upsells"),
        user("Display highlights notification settings", "2022-03_highlights_settings", "Control: Highlights settings not visible", "Treatment 1: Highlights settings visible"),
    ).associateByTo(linkedMapOf(), RegisteredExperiment::name)

    private fun user(readableName: String, name: String, vararg buckets: String) =
        RegisteredExperiment(readableName, name, RegisteredExperiment.Type.USER, buckets.toList())
}
