package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.message.MessageFlags
import com.discord.api.permission.Permission
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.models.message.Message
import com.discord.models.notification.NotificationSettingsV2

object NotificationPolicy {
    enum class Delivery { FOREGROUND, SYSTEM }

    data class Input(
        val message: Message,
        val channel: Channel,
        val parentChannel: Channel? = null,
        val guild: Guild? = null,
        val currentUserId: Long,
        /** True when the author is blocked or ignored; either silences the notification. */
        val isAuthorBlockedOrIgnored: Boolean = false,
        val effectivePermissions: Long? = null,
        val currentUserRoleIds: Set<Long> = emptySet(),
        val guildSettings: ModelNotificationSettings? = null,
        val selectedChannelId: Long? = null,
        val isDnd: Boolean = false,
        /** Backgrounded messages go to the system tray; foregrounded ones to the in-app banner. */
        val isForeground: Boolean = true,
        val appSettings: NotificationSettingsV2 = NotificationSettingsV2(),
    )

    fun evaluate(input: Input): Set<Delivery> {
        val message = input.message
        val channel = input.channel
        if (message.author?.id == input.currentUserId ||
            input.isAuthorBlockedOrIgnored ||
            input.isDnd || !isAccessible(channel, input.effectivePermissions) ||
            // An `@silent` message is delivered but never announced.
            (message.flags ?: 0L) and MessageFlags.SUPPRESS_NOTIFICATIONS != 0L
        ) return emptySet()

        val guildSettings = input.guildSettings
        val override = guildSettings?.getChannelOverride(channel.id)
            ?: input.parentChannel?.let { guildSettings?.getChannelOverride(it.id) }
        if ((override?.isMuted ?: guildSettings?.isMuted) == true) return emptySet()

        if (channel.guildId != 0L || message.guildId != null) {
            val frequency = override?.messageNotifications
                ?.takeUnless { it == ModelNotificationSettings.FREQUENCY_UNSET }
                ?: guildSettings?.messageNotifications
                    ?.takeUnless { it == ModelNotificationSettings.FREQUENCY_UNSET }
                ?: input.guild?.defaultMessageNotifications
                ?: ModelNotificationSettings.FREQUENCY_ALL
            if (frequency == ModelNotificationSettings.FREQUENCY_NOTHING ||
                frequency == ModelNotificationSettings.FREQUENCY_MENTIONS && !isMention(input)
            ) return emptySet()
        }

        if (!input.isForeground) {
            // `mobile_push` is the per-guild switch for exactly this case: notify on this device
            // while it is not in front of the user.
            val mobilePush = input.guildSettings?.isMobilePush ?: true
            return if (input.appSettings.isEnabled && mobilePush) setOf(Delivery.SYSTEM) else emptySet()
        }
        return if (input.appSettings.isEnabledInApp &&
            input.selectedChannelId != channel.id &&
            (input.parentChannel == null || input.selectedChannelId != input.parentChannel.id)
        ) setOf(Delivery.FOREGROUND) else emptySet()
    }

    private fun isAccessible(channel: Channel, permissions: Long?): Boolean {
        if (channel.guildId == 0L) return true
        if (channel.type !in setOf(Channel.GUILD_VOICE, Channel.GUILD_STAGE_VOICE)) return true
        return permissions?.and(Permission.CONNECT) != 0L
    }

    private fun isMention(input: Input): Boolean {
        val settings = input.guildSettings
        if (input.message.mentions.orEmpty().any { it.id == input.currentUserId }) return true
        if (input.message.mentionEveryone == true && settings?.isSuppressEveryone != true) return true
        return settings?.isSuppressRoles != true &&
            input.message.mentionRoles.orEmpty().any(input.currentUserRoleIds::contains)
    }
}
