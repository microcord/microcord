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

/** The developer-visible experiment registry shipped by Discord Android 126.21. */
object ExperimentRegistry {
    val registeredExperiments: LinkedHashMap<String, RegisteredExperiment> = listOf(
        user("Compact Invite Widget", "2020-01_mobile_invite_suggestion_compact", "Control", "Treatment 1: Use compact view"),
        user("Guild Invite Sheet", "2020-12_android_guild_channel_invite_sheet", "Control: Use the full-screen guild invite UI", "Treatment 1: Use the bottom sheet guild invite UI"),
        user("Disable mentions in landscape", "2020-12_android_disable_landscape_mentions", "Control", "Treatment 1: Hide mentions in landscape"),
        user("Invite to GDM Sheet", "2020-12_invite_to_gdm", "Control: Use the full-screen GDM invite UI", "Treatment 1: Use the bottom sheet GDM invite UI"),
        user("Guild Delete Feedback", "2020-12_guild_delete_feedback", "Control: Do not show the feedback modal", "Treatment 1: Show the feedback modal"),
        user("View Threads", "2021-02_view_threads", "Control: Do not display anything threads-related, except if receiving a notification", "Treatment 1: Show view-only threads features in channel list, in chat view, etc."),
        guild("Create Threads", "2020-09_threads", "Control: Do not show thread creation entrypoints, or Thread Browser", "Treatment 1: Show thread creation buttons, and show button to open Thread Browser"),
        user("Disable Camera 2", "2021-02_android_webrtc_camera2", "Control: Use Camera 2 API if supported", "Treatment 1: Force Camera 1 API"),
        user("MediaSinkWants", "2021-03_android_media_sink_wants", "Control: disabled", "Treatment 1: use MediaSinkWants"),
        guild("Default Invite Expiration", "2021-03_android_extend_invite_expiration", "Control: Default Invite Expiration is 1 day", "Treatment 1: Default Invite Expiration is 7 days"),
        user("Emoji Autocomplete Upsell", "2021-03_nitro_emoji_autocomplete_upsell_android", "Control", "Treatment 1: Show Emoji Autocomplete Upsell"),
        user("Application Command Frecency", "2021-09_android_app_commands_frecency", "Control", "Treatment 1: Show frecent application commands"),
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
        guild("New Thread Perms", "2021-08_threads_permissions", "Control: Use old threads permissions.", "Treatment 1: Use new threads permissions."),
        user("SMS Autofill", "2021-09_android_sms_autofill", "Control: No SMS Autofill", "Treatment 1: SMS Code autofills"),
        user("Guild Member Profiles v2", "2021-10_premium_guild_member_profiles", "Control: No editing of guild member premium profiles.", "Treatment 1: Can edit guild member premium profiles"),
        guild("Guild Communication Disabled - Guilds Experiment", "2021-11_guild_communication_disabled_guilds", "Control: No timing out of malicious users.", "Treatment 1: Can grant the MODERATE_MEMBER permission for timing out malicious users"),
        user("Guild Communication Disabled - Users Experiment", "2021-11_guild_communication_disabled_users", "Control: No timing out of malicious users.", "Treatment 1: Can grant the MODERATE_MEMBER permission for timing out malicious users"),
        user("Email Change Confirmation", "2022-01_email_change_confirmation", "Control: No confirmation on email change", "Treatment 1: Require confirmation for email change"),
        user("Playstation Connected Accounts", "2021-12_connected_accounts_playstation", "Control: User can not link and see linked playstation accounts and can not be shown upsells", "Treatment 1: User can link and see linked playstation accounts and can be shown upsells", "Treatment 2: User can link and see linked playstation accounts but can not be shown upsells"),
        guild("Text In Voice", "2022-03_text_in_voice", "Control: No text-in-voice for the guild.", "Treatment 1: Display text-in-voice in the guild."),
        guild("Create Forum Channels", "2021-07_threads_only_channel", "Control: Cannot create forum channels.", "Treatment 1: Can create forum channels."),
        guild("Forum Channel Redesign", "2022-03_android_forum_channel_redesign", "Control: Threads-only view.", "Treatment 1: Forum view."),
        user("Display highlights notification settings", "2022-03_highlights_settings", "Control: Highlights settings not visible", "Treatment 1: Highlights settings visible"),
    ).associateByTo(linkedMapOf(), RegisteredExperiment::name)

    private fun user(readableName: String, name: String, vararg buckets: String) =
        RegisteredExperiment(readableName, name, RegisteredExperiment.Type.USER, buckets.toList())

    private fun guild(readableName: String, name: String, vararg buckets: String) =
        RegisteredExperiment(readableName, name, RegisteredExperiment.Type.GUILD, buckets.toList())
}
