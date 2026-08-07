package com.discord.api.notification

/** The account's notification flags, from READY and `NOTIFICATION_SETTINGS_UPDATE`. */
data class AccountNotificationSettings(val flags: Int = 0) {
    val useNewNotifications: Boolean
        get() = AccountNotificationFlags.has(flags, AccountNotificationFlags.USE_NEW_NOTIFICATIONS)

    val mentionOnAllMessages: Boolean
        get() = AccountNotificationFlags.has(flags, AccountNotificationFlags.MENTION_ON_ALL_MESSAGES)
}

/**
 * Account-wide notification flags, delivered as `notification_settings.flags` on READY and updated
 * by `NOTIFICATION_SETTINGS_UPDATE`.
 *
 * Server-driven, not experiment-driven: there is no client bucket for these.
 */
object AccountNotificationFlags {
    /**
     * Separates unread tracking from the notification level, enabling [UnreadSetting] everywhere.
     * While it is clear every channel resolves to [UnreadSetting.ALL_MESSAGES].
     */
    const val USE_NEW_NOTIFICATIONS = 1 shl 4

    /** Counts every message in an all-messages channel as a mention, marked low importance. */
    const val MENTION_ON_ALL_MESSAGES = 1 shl 5

    fun has(flags: Int, flag: Int) = flags and flag == flag
}

/**
 * Bits on a read state's `flags`.
 *
 * The two classification bits are written for the server's benefit and are mutually exclusive — a
 * read state is a thread, a guild channel, or neither (a DM, which sends `0`).
 */
object ReadStateFlags {
    const val IS_GUILD_CHANNEL = 1 shl 0
    const val IS_THREAD = 1 shl 1
    const val IS_MENTION_LOW_IMPORTANCE = 1 shl 2

    fun classify(isThread: Boolean, isGuildChannel: Boolean): Int = when {
        isThread -> IS_THREAD
        isGuildChannel -> IS_GUILD_CHANNEL
        else -> 0
    }
}

/**
 * What a read state tracks. Non-channel entries arrive in the same `read_state` list and key on the
 * guild or user id rather than a channel id, so they have to be told apart before use.
 */
object ReadStateTypes {
    const val CHANNEL = 0
    const val GUILD_EVENT = 1
    const val NOTIFICATION_CENTER = 2
    const val GUILD_HOME = 3
    const val GUILD_ONBOARDING_QUESTION = 4
    const val MESSAGE_REQUESTS = 5
}

/** Bits on `user_guild_settings.flags`. */
object GuildNotificationSettingsFlags {
    const val UNREADS_ALL_MESSAGES = 1 shl 11
    const val UNREADS_ONLY_MENTIONS = 1 shl 12
    const val OPT_IN_CHANNELS_OFF = 1 shl 13
    const val OPT_IN_CHANNELS_ON = 1 shl 14

    private const val UNREADS_MASK = UNREADS_ALL_MESSAGES or UNREADS_ONLY_MENTIONS

    fun unreadSetting(flags: Int): UnreadSetting = when {
        flags and UNREADS_ALL_MESSAGES == UNREADS_ALL_MESSAGES -> UnreadSetting.ALL_MESSAGES
        flags and UNREADS_ONLY_MENTIONS == UNREADS_ONLY_MENTIONS -> UnreadSetting.ONLY_MENTIONS
        else -> UnreadSetting.UNSET
    }

    fun withFlag(flags: Int, flag: Int, enabled: Boolean): Int =
        if (enabled) flags or flag else flags and flag.inv()

    /** The two unread bits are mutually exclusive, so writing one always clears the other. */
    fun withUnreadSetting(flags: Int, setting: UnreadSetting): Int {
        val cleared = flags and UNREADS_MASK.inv()
        return when (setting) {
            UnreadSetting.ALL_MESSAGES -> cleared or UNREADS_ALL_MESSAGES
            UnreadSetting.ONLY_MENTIONS -> cleared or UNREADS_ONLY_MENTIONS
            UnreadSetting.UNSET -> cleared
        }
    }
}

/** Bits on `user_guild_settings.channel_overrides[].flags`. The unread bits sit lower than the guild set's. */
object ChannelNotificationSettingsFlags {
    const val UNREADS_ONLY_MENTIONS = 1 shl 9
    const val UNREADS_ALL_MESSAGES = 1 shl 10
    const val FAVORITED = 1 shl 11
    const val OPT_IN_ENABLED = 1 shl 12
    const val NEW_FORUM_THREADS_OFF = 1 shl 13
    const val NEW_FORUM_THREADS_ON = 1 shl 14

    private const val UNREADS_MASK = UNREADS_ALL_MESSAGES or UNREADS_ONLY_MENTIONS

    fun withFlag(flags: Int, flag: Int, enabled: Boolean): Int =
        if (enabled) flags or flag else flags and flag.inv()

    fun unreadSetting(flags: Int): UnreadSetting = when {
        flags and UNREADS_ALL_MESSAGES == UNREADS_ALL_MESSAGES -> UnreadSetting.ALL_MESSAGES
        flags and UNREADS_ONLY_MENTIONS == UNREADS_ONLY_MENTIONS -> UnreadSetting.ONLY_MENTIONS
        else -> UnreadSetting.UNSET
    }

    fun withUnreadSetting(flags: Int, setting: UnreadSetting): Int {
        val cleared = flags and UNREADS_MASK.inv()
        return when (setting) {
            UnreadSetting.ALL_MESSAGES -> cleared or UNREADS_ALL_MESSAGES
            UnreadSetting.ONLY_MENTIONS -> cleared or UNREADS_ONLY_MENTIONS
            UnreadSetting.UNSET -> cleared
        }
    }
}

/** What a channel counts as unread: every message, or only messages that mention you. */
enum class UnreadSetting(val value: Int) {
    UNSET(0),
    ALL_MESSAGES(1),
    ONLY_MENTIONS(2),
    ;

    companion object {
        fun from(value: Int) = entries.firstOrNull { it.value == value } ?: UNSET
    }
}
