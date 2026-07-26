package com.discord.api.message

/**
 * Values of [Message.type].
 *
 * Non-negative values come from the gateway; negative values are client-local states used for
 * messages that never reached (or never will reach) the server.
 */
object MessageTypes {
    const val LOCAL_AUTOMOD_BLOCKED_MESSAGE = -8
    const val LOCAL_THREAD_STARTER_MESSAGE_NOT_FOUND = -7
    const val LOCAL_CAPTCHA_REQUIRED = -6
    const val LOCAL_APPLICATION_COMMAND = -5
    const val LOCAL_APPLICATION_COMMAND_SEND_FAILED = -4
    const val LOCAL_INVALID_ATTACHMENTS = -3
    const val LOCAL_SEND_FAILED = -2
    const val LOCAL = -1

    const val DEFAULT = 0
    const val RECIPIENT_ADD = 1
    const val RECIPIENT_REMOVE = 2
    const val CALL = 3
    const val CHANNEL_NAME_CHANGE = 4
    const val CHANNEL_ICON_CHANGE = 5
    const val CHANNEL_PINNED_MESSAGE = 6
    const val USER_JOIN = 7
    const val USER_PREMIUM_GUILD_SUBSCRIPTION = 8
    const val USER_PREMIUM_GUILD_SUBSCRIPTION_TIER_1 = 9
    const val USER_PREMIUM_GUILD_SUBSCRIPTION_TIER_2 = 10
    const val USER_PREMIUM_GUILD_SUBSCRIPTION_TIER_3 = 11
    const val CHANNEL_FOLLOW_ADD = 12
    const val GUILD_STREAM = 13
    const val GUILD_DISCOVERY_DISQUALIFIED = 14
    const val GUILD_DISCOVERY_REQUALIFIED = 15
    const val GUILD_DISCOVERY_GRACE_PERIOD_INITIAL_WARNING = 16
    const val GUILD_DISCOVERY_GRACE_PERIOD_FINAL_WARNING = 17
    const val THREAD_CREATED = 18
    const val REPLY = 19
    const val CHAT_INPUT_COMMAND = 20
    const val THREAD_STARTER_MESSAGE = 21
    const val GUILD_INVITE_REMINDER = 22
    const val CONTEXT_MENU_COMMAND = 23
    const val AUTOMOD_SYSTEM_MESSAGE = 24
    const val ROLE_SUBSCRIPTION_PURCHASE_MESSAGE = 25
}
