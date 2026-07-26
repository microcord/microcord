package com.discord.stores

/**
 * Which surface selected the channel.
 *
 * Carried on the channel selection itself (`StoreChannelsSelected`) rather than passed at the call
 * site, because the analytics event is raised on the store thread once the selection resolves, long
 * after the tap that caused it. Reported as `location` on `channel_opened` and
 * `text_in_voice_opened`; absent when the selection came from somewhere with no distinct location,
 * such as a deep link or restored state.
 */
enum class SelectedChannelAnalyticsLocation(val analyticsValue: String) {
    EMBED("Embed"),
    CHANNEL_LIST("Channel List"),
    THREAD_BROWSER("Thread Browser"),
    TEXT_IN_VOICE("Text In Voice"),
}
