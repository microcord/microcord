package com.discord.utilities.channel

import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isThread
import com.discord.api.notification.UnreadSetting
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild

/**
 * Resolves the notification frequency and unread behaviour actually in force for a channel.
 *
 * Reference: `com.discord.utilities.channel.ChannelNotificationSettingsUtils`, and
 * `UserGuildSettingsStore.resolvedMessageNotifications` / `resolveUnreadSetting`.
 */
object ChannelNotificationSettingsUtils {

    /**
     * The effective `message_notifications` value for [channel].
     *
     * Four levels, each falling through to the next while it is
     * [ModelNotificationSettings.FREQUENCY_UNSET]: the per-channel override, the override on the
     * channel's category, the user's setting for the guild, then the guild's own default. The
     * result is never `FREQUENCY_UNSET` because `Guild.defaultMessageNotifications` always carries
     * a concrete value.
     */
    fun computeNotificationSetting(
        guild: Guild,
        channel: Channel,
        guildSettings: ModelNotificationSettings,
    ): Int = resolveMessageNotifications(channel, guildSettings, guild.defaultMessageNotifications)

    /** As [computeNotificationSetting], for callers holding only the guild's default. */
    fun resolveMessageNotifications(
        channel: Channel,
        guildSettings: ModelNotificationSettings?,
        guildDefault: Int,
    ): Int {
        val unset = ModelNotificationSettings.FREQUENCY_UNSET
        val override = guildSettings?.getChannelOverride(channel.id)?.messageNotifications ?: unset
        if (override != unset) return override

        val parentId = channel.parentId
        if (parentId != 0L) {
            val parent = guildSettings?.getChannelOverride(parentId)?.messageNotifications ?: unset
            if (parent != unset) return parent
        }

        val guildSetting = guildSettings?.messageNotifications ?: unset
        if (guildSetting != unset) return guildSetting

        return guildDefault
    }

    /**
     * Whether [channel] is silenced by the guild, by its category, or in its own right.
     *
     * Muting a category is not inherited by its channels anywhere else — a channel inside one
     * still reads as unread and still dims normally — so the three levels have to be asked for
     * explicitly wherever "the user asked not to be bothered" is the question.
     */
    fun isGuildCategoryOrChannelMuted(
        channel: Channel,
        guildSettings: ModelNotificationSettings?,
    ): Boolean {
        guildSettings ?: return false
        if (guildSettings.isMuted) return true
        if (guildSettings.getChannelOverride(channel.id)?.isMuted == true) return true
        val parentId = channel.parentId
        return parentId != 0L && guildSettings.getChannelOverride(parentId)?.isMuted == true
    }

    /**
     * What marks [channel] unread: every message, or only ones that mention you.
     *
     * Resolved channel → category → guild, each level skipped while it is [UnreadSetting.UNSET],
     * then derived from the notification level. Threads and DMs always track every message.
     *
     * Without [useNewNotifications] everything resolves to [UnreadSetting.ALL_MESSAGES], which is
     * what leaves accounts that lack the flag on the previous behaviour.
     */
    fun resolveUnreadSetting(
        channel: Channel,
        guildSettings: ModelNotificationSettings?,
        guildDefaultMessageNotifications: Int,
        useNewNotifications: Boolean,
    ): UnreadSetting {
        if (channel.isThread() || channel.isPrivate()) return UnreadSetting.ALL_MESSAGES
        if (!useNewNotifications) return UnreadSetting.ALL_MESSAGES

        val own = guildSettings?.getChannelOverride(channel.id)?.unreadSetting ?: UnreadSetting.UNSET
        if (own != UnreadSetting.UNSET) return own

        val parentId = channel.parentId
        if (parentId != 0L) {
            val parent = guildSettings?.getChannelOverride(parentId)?.unreadSetting ?: UnreadSetting.UNSET
            if (parent != UnreadSetting.UNSET) return parent
        }

        val guild = guildSettings?.unreadSetting ?: UnreadSetting.UNSET
        if (guild != UnreadSetting.UNSET) return guild

        val notifications =
            resolveMessageNotifications(channel, guildSettings, guildDefaultMessageNotifications)
        return if (notifications == ModelNotificationSettings.FREQUENCY_ALL) {
            UnreadSetting.ALL_MESSAGES
        } else {
            UnreadSetting.ONLY_MENTIONS
        }
    }
}
