package com.discord.utilities.channel

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild

/**
 * Resolves the notification frequency actually in force for a channel.
 *
 * Reference: `com.discord.utilities.channel.ChannelNotificationSettingsUtils`.
 */
object ChannelNotificationSettingsUtils {

    /**
     * The effective `message_notifications` value for [channel].
     *
     * Three levels, each falling through to the next when it is
     * [ModelNotificationSettings.FREQUENCY_UNSET]: the per-channel override, then the user's
     * setting for the guild, then the guild's own default. The result is never `FREQUENCY_UNSET`
     * because `Guild.defaultMessageNotifications` always carries a concrete value.
     */
    fun computeNotificationSetting(
        guild: Guild,
        channel: Channel,
        guildSettings: ModelNotificationSettings,
    ): Int {
        val override = guildSettings.getChannelOverride(channel.id)?.messageNotifications
            ?: ModelNotificationSettings.FREQUENCY_UNSET
        if (override != ModelNotificationSettings.FREQUENCY_UNSET) return override

        val guildSetting = guildSettings.messageNotifications
        if (guildSetting != ModelNotificationSettings.FREQUENCY_UNSET) return guildSetting

        return guild.defaultMessageNotifications
    }
}
