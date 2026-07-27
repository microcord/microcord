package com.discord.widgets.channels.settings

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelNotificationSettings

/**
 * A channel plus the mute state that applies to *it*, resolved out of the guild-level notification
 * settings.
 *
 * The settings payload carries the guild's own mute state and a list of per-channel overrides; the
 * channel-scoped settings screens only care about whichever one wins, so the resolution happens
 * once in [createFromNotificationSettings] instead of at every read site.
 */
data class ChannelSettings(
    val channel: Channel,
    val isMuted: Boolean,
    val muteEndTime: String?,
) {
    companion object {
        /**
         * Picks the channel override for [channel] if there is one, otherwise falls back to the
         * guild-level mute state in [notificationSettings].
         */
        @JvmStatic
        fun createFromNotificationSettings(
            channel: Channel,
            notificationSettings: ModelNotificationSettings,
        ): ChannelSettings {
            val override = notificationSettings.getChannelOverride(channel.id)
            return ChannelSettings(
                channel = channel,
                isMuted = override?.isMuted ?: notificationSettings.isMuted,
                muteEndTime = override?.muteEndTime ?: notificationSettings.muteEndTime,
            )
        }
    }
}
